package dev.doreview.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ReviewProjectionSchedulerTest extends BasePlatformTestCase {
    public void testCaptureIsUnderReadPureWorkIsOutsideReadAndDeliveryIsEdt() {
        var scheduler = new ReviewProjectionScheduler(getTestRootDisposable());
        var value = new AtomicReference<String>();
        var failure = new AtomicReference<Throwable>();
        scheduler.schedule(new Object(), () -> {
            assertTrue(ApplicationManager.getApplication().isReadAccessAllowed());
            return "captured";
        }, captured -> {
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed());
            assertFalse(ApplicationManager.getApplication().isDispatchThread());
            return captured + ":computed";
        }, result -> {
            assertTrue(ApplicationManager.getApplication().isDispatchThread());
            value.set(result);
        }, failure::set);
        await(() -> value.get() != null || failure.get() != null);
        assertNull(failure.get());
        assertEquals("captured:computed", value.get());
    }

    public void testSupersededPureJobCannotPublishAndSeesCancellation() {
        var scheduler = new ReviewProjectionScheduler(getTestRootDisposable());
        var firstStarted = new AtomicBoolean();
        var allowFirst = new CountDownLatch(1);
        var callbacks = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        Object key = new Object();
        var first = scheduler.schedule(key, () -> "first", captured -> {
            firstStarted.set(true);
            awaitLatch(allowFirst);
            ProgressManager.checkCanceled();
            return captured;
        }, ignored -> callbacks.addAndGet(100), failure::set);
        try {
            await(firstStarted::get);
            scheduler.schedule(key, () -> "second", captured -> captured,
                    ignored -> callbacks.incrementAndGet(), failure::set);
            allowFirst.countDown();
            await(() -> callbacks.get() == 1 || failure.get() != null);
            assertTrue(first.isCancelled());
            assertNull(failure.get());
            assertEquals(1, callbacks.get());
        } finally { allowFirst.countDown(); }
    }

    public void testIndependentCommandSurvivesReplacingDocumentProjection() {
        var scheduler = new ReviewProjectionScheduler(getTestRootDisposable());
        var commandStarted = new AtomicBoolean();
        var allowCommand = new CountDownLatch(1);
        var callbacks = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var command = scheduler.submit(() -> "frozen", captured -> {
            commandStarted.set(true);
            awaitLatch(allowCommand);
            return captured;
        }, ignored -> callbacks.incrementAndGet(), failure::set);
        try {
            await(commandStarted::get);
            Object key = new Object();
            scheduler.schedule(key, () -> "older", value -> value, ignored -> { }, failure::set);
            scheduler.schedule(key, () -> "latest", value -> value, ignored -> callbacks.incrementAndGet(), failure::set);
            allowCommand.countDown();
            await(() -> callbacks.get() == 2 || failure.get() != null);
            assertFalse(command.isCancelled());
            assertNull(failure.get());
            assertEquals(2, callbacks.get());
        } finally { allowCommand.countDown(); }
    }

    public void testDisposeCancelsActiveWorkAndSuppressesItsCallbacks() {
        var scheduler = new ReviewProjectionScheduler(getTestRootDisposable());
        var started = new AtomicBoolean();
        var release = new CountDownLatch(1);
        var callbacks = new AtomicInteger();
        var job = scheduler.submit(() -> "read", value -> {
            started.set(true);
            awaitLatch(release);
            return value;
        }, ignored -> callbacks.incrementAndGet(), ignored -> callbacks.incrementAndGet());
        try {
            await(started::get);
            Disposer.dispose(scheduler);
            release.countDown();
            assertTrue(job.isCancelled());
            assertTrue(scheduler.submit(() -> "unused", value -> value,
                    ignored -> callbacks.incrementAndGet(), ignored -> callbacks.incrementAndGet()).isCancelled());
            assertEquals(0, callbacks.get());
        } finally { release.countDown(); }
    }

    public void testPureFailureIsDeliveredOnEdt() {
        var scheduler = new ReviewProjectionScheduler(getTestRootDisposable());
        var failure = new AtomicReference<Throwable>();
        scheduler.submit(() -> "read", value -> { throw new IllegalArgumentException("probe"); },
                ignored -> fail("Failure cannot publish a success"), error -> {
                    assertTrue(ApplicationManager.getApplication().isDispatchThread());
                    failure.set(error);
                });
        await(() -> failure.get() != null);
        assertInstanceOf(failure.get(), IllegalArgumentException.class);
    }

    public void testOverlappingReadCompletionAndPureWorkDoNotShareProgressOwnership() {
        var scheduler = new ReviewProjectionScheduler(getTestRootDisposable());
        var completed = new AtomicInteger();
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        // NBRA completes its promise before its own indicator cleanup. Fast independent
        // captures repeatedly exercise the handoff while earlier pure tasks are already running.
        for (int job = 0; job < 24; job++) {
            scheduler.submit(() -> {
                ProgressManager.checkCanceled();
                return "captured";
            }, value -> {
                assertFalse(ApplicationManager.getApplication().isReadAccessAllowed());
                ProgressManager.checkCanceled();
                return value;
            }, value -> completed.incrementAndGet(), failure -> {
                failures.add(failure);
                completed.incrementAndGet();
            });
        }
        await(() -> completed.get() == 24);
        assertEmpty("Progress ownership must remain valid across overlapping phases: " + failures, failures);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        PlatformTestUtil.waitWithEventsDispatching("Projection scheduler did not complete", condition, 10);
    }
    private static void awaitLatch(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ProgressManager.checkCanceled();
            throw new AssertionError(interrupted);
        }
    }
}
