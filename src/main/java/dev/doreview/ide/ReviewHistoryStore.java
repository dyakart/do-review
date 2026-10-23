package dev.doreview.ide;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import dev.doreview.core.EvidenceRef;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ReviewEvidence;
import dev.doreview.core.ReviewHistory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Application facade. XML is a migration input; the live journal is never an IDE settings component. */
@Service(Service.Level.APP)
public final class ReviewHistoryStore extends LegacyReviewHistoryModel implements Disposable {
    static final com.intellij.util.messages.Topic<SharedHistoryListener> SHARED_UPDATES =
            com.intellij.util.messages.Topic.create("DoReview shared history updated", SharedHistoryListener.class);
    public interface SharedHistoryListener { void historyUpdated(Project project, String path, boolean replacement); }
    private SharedReviewHistoryStore shared;
    public ReviewHistoryStore() { super(backupDirectory()); shared = new SharedReviewHistoryStore(); }
    /** Detached model for legacy serialization, recovery and compatibility fixtures. */
    ReviewHistoryStore(Path backupRoot) { super(backupRoot); }
    public static ReviewHistoryStore getInstance() {
        return ApplicationManager.getApplication().getService(ReviewHistoryStore.class);
    }
    boolean isShared() { return shared != null; }
    boolean isLoaded(Project project, String path) { return shared == null || shared.isLoaded(project, path); }
    String checkoutToken(Project project, String path) { return shared == null ? null : shared.checkoutToken(project, path); }
    void whenLoaded(Project project, String path, String text, Runnable callback) {
        if (shared == null) { if (callback != null) callback.run(); } else shared.whenLoaded(project, path, text, callback);
    }
    void reloadShared(Project project) { if (shared != null) shared.reload(project); }
    void flushShared(Project project) { if (shared != null) shared.flush(project); }
    void flushShared(Project project, String path) { if (shared != null) shared.flush(project, path); }
    void release(Project project, String path) { if (shared != null) shared.release(project, path); }
    void projectClosed(Project project) { if (shared != null) shared.projectClosed(project); }
    String authors(Project project, String path, List<EvidenceRef> refs) {
        return shared == null ? "" : shared.authors(project, path, refs);
    }
    SharedReviewHistoryStore sharedStorage() { return shared; }
    java.util.concurrent.CompletableFuture<Void> storageClosed() {
        return shared == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : shared.closed();
    }
    java.util.concurrent.CompletableFuture<Void> storageIdle() {
        return shared == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : shared.idle();
    }
    void queryVersions(Project project, String path, Consumer<List<SharedHistoryRepository.Version>> callback) {
        if (shared != null) { shared.queryVersions(project, path, callback); return; }
        var history = history(project, path);
        callback.accept(history.evidence().stream().map(proof -> new SharedHistoryRepository.Version(proof.snapshotId(),
                proof.createdAt(), SharedHistoryRepository.Author.imported())).distinct().toList());
    }
    void readSnapshot(Project project, String path, String hash, Consumer<String> callback) {
        if (shared != null) shared.readSnapshot(project, path, hash, callback);
        else callback.accept(history(project, path).snapshots().get(hash).text());
    }
    void queryPaths(Project project, Consumer<List<String>> callback) {
        if (shared != null) shared.queryPaths(project, callback); else callback.accept(paths(project));
    }
    void movePathAsync(Project project, String oldPath, String newPath, Consumer<Boolean> callback) {
        if (shared != null) shared.movePathAsync(project, oldPath, newPath, callback);
        else callback.accept(movePath(project, oldPath, newPath));
    }
    void rebuild(Project project, String path, Consumer<Path> callback) {
        if (shared != null) shared.rebuild(project, path, callback);
    }
    @Override public ReviewHistory history(Project p, String path) {
        return shared == null ? super.history(p, path) : shared.history(p, path);
    }
    @Override public ProjectionHints checkpoint(Project p, String path, String text) {
        return shared == null ? super.checkpoint(p, path, text) : shared.checkpoint(p, path, text);
    }
    @Override public void checkpoint(Project p, String path, String text, ProjectionHints hints) {
        if (shared == null) super.checkpoint(p, path, text, hints); else shared.checkpoint(p, path, text, hints, false);
    }
    @Override public boolean commitCheckpoint(Project p, String path, PreparedCheckpoint value) {
        return shared == null ? super.commitCheckpoint(p, path, value) : shared.commitCheckpoint(p, path, value);
    }
    @Override public void checkpointPending(Project p, String path, String text, ProjectionHints hints) {
        if (shared == null) super.checkpointPending(p, path, text, hints); else shared.checkpoint(p, path, text, hints, true);
    }
    @Override public ReviewEvidence mark(Project p, String path, String text, int start, int end) {
        return shared == null ? super.mark(p, path, text, start, end) : shared.mark(p, path, text, start, end);
    }
    @Override public void revoke(Project p, String path, List<EvidenceRef> refs) {
        if (shared == null) super.revoke(p, path, refs); else shared.revoke(p, path, refs);
    }
    @Override public void revokeByEvidence(Project p, List<EvidenceRef> refs) {
        if (shared == null) super.revokeByEvidence(p, refs); else shared.revokeByEvidence(p, refs);
    }
    @Override public void clearFile(Project p, String path) {
        if (shared == null) super.clearFile(p, path); else shared.clearFile(p, path);
    }
    @Override public List<String> paths(Project p) { return shared == null ? super.paths(p) : shared.paths(p); }
    @Override public boolean movePath(Project p, String oldPath, String newPath) {
        return shared == null ? super.movePath(p, oldPath, newPath) : shared.movePath(p, oldPath, newPath);
    }
    @Override public List<String> diagnostics(Project p) { return shared == null ? super.diagnostics(p) : shared.diagnostics(p); }
    @Override public MigrationResult importLegacyFromProject(Project p) {
        if (shared == null) return super.importLegacyFromProject(p);
        shared.initialize(p); return MigrationResult.empty();
    }
    @Override public MigrationResult importLegacy(Project p, Map<String, LegacyFile> files) {
        return shared == null ? super.importLegacy(p, files) : shared.importLegacy(p, files);
    }
    @Override public StoreState getState() { return shared == null ? super.getState() : null; }
    /** Explicit detached-state injection, not a platform persistence callback. */
    @Override public void loadState(StoreState state) {
        if (shared != null) { shared.dispose(); shared = null; }
        super.loadState(state);
    }
    @Override public void dispose() { if (shared != null) shared.dispose(); }
}
