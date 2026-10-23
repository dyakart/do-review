package dev.doreview.ide.context;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.util.messages.Topic;
import dev.doreview.core.ContextMap;
import kotlinx.coroutines.CoroutineScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, project-local cache of immutable context maps for current and historical snapshots. */
@Service(Service.Level.PROJECT)
public final class ContextAnalysisManager implements Disposable {
    public interface Listener { void contextChanged(); }
    public static final Topic<Listener> CHANGED = Topic.create("DoReview context providers changed", Listener.class);
    private static final Logger LOG = Logger.getInstance(ContextAnalysisManager.class);
    private final Project project;
    private final Map<String, ContextMap> cache = new LinkedHashMap<>(32, 0.75f, true);
    private int cachedRows;
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean disposed;

    public ContextAnalysisManager(Project project, CoroutineScope scope) {
        this.project = project;
        ContextProvider.EP.addChangeListener(scope, this::providersChanged);
    }

    public long generation() { return generation.get(); }

    private void providersChanged() {
        generation.incrementAndGet();
        synchronized (cache) { cache.clear(); cachedRows = 0; }
        Runnable publish = () -> {
            if (!disposed && !project.isDisposed()) project.getMessageBus().syncPublisher(CHANGED).contextChanged();
        };
        if (ApplicationManager.getApplication().isDispatchThread()) publish.run();
        else ApplicationManager.getApplication().invokeLater(publish, project.getDisposed());
    }

    public static ContextMap analyze(Project project, VirtualFile file, String snapshot) {
        return project.getService(ContextAnalysisManager.class).analyze(file, snapshot);
    }

    public ContextMap analyze(VirtualFile file, String snapshot) {
        return ReadAction.nonBlocking(() -> analyzeUnderRead(file, snapshot)).executeSynchronously();
    }

    /** The scheduler captures contexts under a cancellable read action before pure computation. */
    public ContextMap analyzeUnderRead(VirtualFile file, String snapshot) {
        ApplicationManager.getApplication().assertReadAccessAllowed();
        long analysisGeneration = generation.get();
        if (file == null || !file.isValid()) return ContextMap.fallback(snapshot);
        PsiFile original = PsiManager.getInstance(project).findFile(file);
        if (original == null) return ContextMap.fallback(snapshot);
        ContextProvider provider = null;
        String providerId = "unavailable:context-provider";
        try {
            for (ContextProvider candidate : ContextProvider.EP.getExtensionList()) {
                providerId = "unavailable:" + candidate.getClass().getName();
                providerId = candidate.id();
                if (candidate.supports(original)) {
                    provider = candidate;
                    break;
                }
            }
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn("Review context provider unavailable for " + providerId, failure);
            return unknown(providerId, snapshot);
        }
        if (provider == null) return ContextMap.fallback(snapshot);
        String key = analysisGeneration + ":" + providerId + ':' + file.getUrl() + ':' + digest(snapshot);
        synchronized (cache) {
            if (generation.get() != analysisGeneration || disposed) return unknown(providerId, snapshot);
            ContextMap cached = cache.get(key);
            if (cached != null) return cached;
        }
        ContextMap result;
        try {
            // No disk writes, document replacement, symbol resolution or index queries.
            PsiFile copy = PsiFileFactory.getInstance(project).createFileFromText(snapshot, original);
            result = copy != null && provider.supports(copy)
                    ? provider.analyze(copy, snapshot) : unknown(providerId, snapshot);
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn("Review context analysis unavailable for " + providerId, failure);
            result = unknown(providerId, snapshot);
        }
        synchronized (cache) {
            // An extension may be replaced while an earlier analysis is completing. Its
            // immutable result can be rejected by the caller's generation check, never cached anew.
            if (generation.get() != analysisGeneration || disposed) return result;
            ContextMap previous = cache.put(key, result);
            cachedRows += result.fingerprints().size() - (previous == null ? 0 : previous.fingerprints().size());
            var oldest = cache.entrySet().iterator();
            while ((cache.size() > 256 || cachedRows > 200_000) && oldest.hasNext()) {
                cachedRows -= oldest.next().getValue().fingerprints().size();
                oldest.remove();
            }
        }
        return result;
    }

    @Override public void dispose() {
        disposed = true;
        synchronized (cache) { cache.clear(); cachedRows = 0; }
    }

    static ContextMap unknown(String provider, String text) {
        return new ContextMap(provider, new ArrayList<>(Collections.nCopies(lineStarts(text).length, null)));
    }

    static int[] lineStarts(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        int[] starts = new int[lines];
        int line = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts[line++] = i + 1;
        return starts;
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
