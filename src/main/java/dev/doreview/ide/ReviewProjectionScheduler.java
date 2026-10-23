package dev.doreview.ide;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.concurrency.CancellablePromise;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns cancellable read capture, independent pure work, and guarded EDT delivery. */
public final class ReviewProjectionScheduler implements Disposable {
    public interface Job {
        void cancel();
        boolean isCancelled();
    }

    private final Object lock = new Object();
    private final Map<Object, ScheduledJob> latest = new HashMap<>();
    private final Set<ScheduledJob> jobs = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean disposed;

    public ReviewProjectionScheduler(Disposable parent) { Disposer.register(parent, this); }

    /** Replaces previous work for the same key. Keys should be document identities owned by the caller. */
    public <R, T> Job schedule(Object key, Supplier<R> readCapture, Function<R, T> pureCompute,
                               Consumer<T> successEdt, Consumer<Throwable> errorEdt) {
        if (key == null) throw new IllegalArgumentException("A latest-job key is required");
        return start(key, readCapture, pureCompute, successEdt, errorEdt);
    }

    /** Independent commands (such as a frozen clear action) are not superseded by normal projections. */
    public <R, T> Job submit(Supplier<R> readCapture, Function<R, T> pureCompute,
                             Consumer<T> successEdt, Consumer<Throwable> errorEdt) {
        return start(null, readCapture, pureCompute, successEdt, errorEdt);
    }

    private <R, T> Job start(Object key, Supplier<R> readCapture, Function<R, T> pureCompute,
                              Consumer<T> successEdt, Consumer<Throwable> errorEdt) {
        var job = new ScheduledJob(key);
        ScheduledJob previous;
        synchronized (lock) {
            if (disposed) { job.cancelled = true; return job; }
            jobs.add(job);
            previous = key == null ? null : latest.put(key, job);
        }
        if (previous != null) previous.cancel();
        // Java NBRA remains the platform-supported cancellable API. Capture must be repeatable
        // and return immutable values: unlike PSI, these can safely outlive this read action.
        CancellablePromise<R> read = ReadAction.nonBlocking(readCapture::get)
                .expireWith(this).expireWhen(job::isCancelled).wrapProgress(job.captureIndicator)
                .submit(AppExecutorUtil.getAppExecutorService());
        job.read = read;
        if (job.isCancelled()) read.cancel();
        read.onSuccess(captured -> {
            if (job.isCancelled()) { forget(job); return; }
            // Promise handlers may run inside the read action. A separate pooled task is
            // deliberate: expensive text matching/checkpoint preparation never holds that lock.
            Future<?> compute = AppExecutorUtil.getAppExecutorService().submit(() -> {
                if (job.isCancelled()) { forget(job); return; }
                try {
                    T result = ProgressManager.getInstance().runProcess(() -> {
                        job.computeIndicator.checkCanceled();
                        if (ApplicationManager.getApplication().isReadAccessAllowed()) {
                            throw new IllegalStateException("Pure review computation must run outside a read action");
                        }
                        T value = pureCompute.apply(captured);
                        job.computeIndicator.checkCanceled();
                        return value;
                    }, job.computeIndicator);
                    deliver(job, () -> successEdt.accept(result));
                } catch (Throwable failure) {
                    if (job.isCancelled()) forget(job);
                    else deliver(job, () -> errorEdt.accept(failure));
                }
            });
            job.compute = compute;
            if (job.isCancelled()) compute.cancel(true);
        }).onError(failure -> {
            if (job.isCancelled()) forget(job);
            else deliver(job, () -> errorEdt.accept(failure));
        });
        return job;
    }

    private void deliver(ScheduledJob job, Runnable callback) {
        ApplicationManager.getApplication().invokeLater(() -> {
            boolean eligible;
            synchronized (lock) {
                eligible = !disposed && !job.cancelled && jobs.contains(job)
                        && (job.key == null || latest.get(job.key) == job);
                forgetLocked(job);
            }
            if (eligible) callback.run();
        }, ModalityState.any());
    }

    public void cancel(Object key) {
        ScheduledJob job;
        synchronized (lock) { job = latest.get(key); }
        if (job != null) job.cancel();
    }

    /** Cancellation suppresses callbacks; callers must clear any corresponding pending UI state. */
    public void cancelAll() {
        ScheduledJob[] active;
        synchronized (lock) { active = jobs.toArray(ScheduledJob[]::new); }
        for (var job : active) job.cancel();
    }

    private void forget(ScheduledJob job) { synchronized (lock) { forgetLocked(job); } }
    private void forgetLocked(ScheduledJob job) {
        jobs.remove(job);
        if (job.key != null) latest.remove(job.key, job);
    }

    @Override public void dispose() { disposed = true; cancelAll(); }

    private final class ScheduledJob implements Job {
        private final Object key;
        // NBRA promise handlers can start the pure phase before NBRA has stopped its indicator.
        // Independent indicators avoid concurrent runProcess ownership; explicit cancellation owns both.
        private final ProgressIndicatorBase captureIndicator = new ProgressIndicatorBase();
        private final ProgressIndicatorBase computeIndicator = new ProgressIndicatorBase();
        private volatile boolean cancelled;
        private volatile CancellablePromise<?> read;
        private volatile Future<?> compute;
        private ScheduledJob(Object key) { this.key = key; }
        @Override public boolean isCancelled() { return cancelled || disposed; }
        @Override public void cancel() {
            cancelled = true;
            captureIndicator.cancel();
            computeIndicator.cancel();
            var capture = read;
            if (capture != null) capture.cancel();
            var computation = compute;
            if (computation != null) computation.cancel(true);
            forget(this);
        }
    }
}
