package dev.doreview.ide;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.intellij.util.messages.Topic;
import dev.doreview.core.ContextMap;
import dev.doreview.core.FileProjection;
import dev.doreview.core.FileSnapshot;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ReviewEvidence;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;
import dev.doreview.ide.context.ContextAnalysisManager;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Saved facts are immutable; editor projections are disposable and UI-thread confined. */
@Service(Service.Level.PROJECT)
public final class DoReviewService implements Disposable {
    private static final Logger LOG = Logger.getInstance(DoReviewService.class);
    public interface CoverageListener { void coverageChanged(); }
    public static final Topic<CoverageListener> COVERAGE_CHANGED = Topic.create("DoReview coverage changed", CoverageListener.class);
    public static final TextAttributesKey REVIEWED_ATTRIBUTES = ReviewMarkKeys.REVIEWED;
    public static final TextAttributesKey NEEDS_REVIEW_ATTRIBUTES = ReviewMarkKeys.NEEDS_REVIEW;
    public static final Key<Boolean> REVIEWED_HIGHLIGHTER = ReviewMarkKeys.REVIEWED_HIGHLIGHTER;
    public static final Key<Boolean> NEEDS_REVIEW_HIGHLIGHTER = ReviewMarkKeys.NEEDS_REVIEW_HIGHLIGHTER;
    public static final Key<Boolean> CHANGED_HIGHLIGHTER = ReviewMarkKeys.CHANGED_HIGHLIGHTER;

    private final Project project;
    private ReviewHistoryStore store;
    private ReviewHistoryActions historyActions;
    private final Map<Document, RuntimeProjection> runtime = new IdentityHashMap<>();
    private final ReviewFileAccess files;
    private final ReviewEditorPresentation presentation;
    private final ReviewProjectionScheduler scheduler;
    private final ReviewProjectionComputer computer;
    private final Set<Document> pendingRefresh = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean initialized, allRefreshPending;
    private final AtomicLong storeEpoch = new AtomicLong();
    private int pendingRevocations;
    private volatile boolean disposed;

    public DoReviewService(Project project) {
        this.project = project;
        files = new ReviewFileAccess(project);
        scheduler = new ReviewProjectionScheduler(this);
        computer = new ReviewProjectionComputer(project);
        presentation = new ReviewEditorPresentation(project, this, this::showChanges);
    }
    public static DoReviewService getInstance(Project project) {
        var service = project.getService(DoReviewService.class);
        service.initialize();
        return service;
    }
    public void initialize() {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (initialized || disposed) return;
        initialized = true;
        store = ReviewHistoryStore.getInstance();
        historyActions = new ReviewHistoryActions(project, store, files, this::history, this::historyReloaded, this::notifyInfo);
        var migration = store.importLegacyFromProject(project);
        if (!migration.warnings().isEmpty()) notifyInfo(String.join("\n", migration.warnings()));
        var factory = EditorFactory.getInstance();
        factory.getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void beforeDocumentChange(@NotNull DocumentEvent event) {
                if (!supports(event.getDocument())) return;
                if (event.getOldFragment().toString().contentEquals(event.getNewFragment())) return;
                var previous = projection(event.getDocument());
                var state = runtime.get(event.getDocument());
                if (state.ready) state.hints = state.hints.mergeResolved(ProjectionHints.fromProjection(history(event.getDocument()), previous));
                else {
                    // Seed newly marked exact facts without dropping earlier coordinate evidence
                    // while a worker is still resolving other historical snapshots.
                    var seed = ProjectionHints.fromProjection(exactHistory(history(event.getDocument()), event.getDocument().getText()), previous);
                    state.hints = state.hints.mergeResolved(seed);
                }
                // Range highlighters expand with inserted text. Remove confirmed tint before
                // the edit so newly inserted code cannot appear green while analysis is pending.
                presentation.clearConfirmed(event.getDocument());
            }
            @Override public void documentChanged(@NotNull DocumentEvent event) {
                var document = event.getDocument();
                if (!supports(document) || event.getOldFragment().toString().contentEquals(event.getNewFragment())) return;
                var state = runtime.get(document);
                if (state != null) {
                    String previousText = state.text;
                    state.hints = event.isWholeTextReplaced() ? savedHints(document)
                            : state.hints.applyEdit(state.text, document.getText(), event.getOffset(), event.getOldLength(), event.getNewLength());
                    if (!state.ready && !event.isWholeTextReplaced()) {
                        state.hints = state.hints.guardUnresolvedEdit(history(document), previousText, document.getText(),
                                event.getOffset(), event.getOldLength(), event.getNewLength());
                    }
                    state.stamp = -1;
                    savePendingHints(document, state.hints);
                }
                refreshDocument(document);
            }
        }, this);
        factory.addEditorFactoryListener(new EditorFactoryListener() {
            @Override public void editorCreated(@NotNull EditorFactoryEvent event) {
                if (event.getEditor().getProject() == project) refreshDocument(event.getEditor().getDocument());
            }
            @Override public void editorReleased(@NotNull EditorFactoryEvent event) {
                presentation.release(event.getEditor());
                if (Arrays.stream(factory.getEditors(event.getEditor().getDocument(), project))
                        .noneMatch(editor -> editor != event.getEditor() && !editor.isDisposed())) {
                    scheduler.cancel(event.getEditor().getDocument());
                    runtime.remove(event.getEditor().getDocument());
                }
            }
        }, this);
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(EditorColorsManager.TOPIC, scheme -> refreshAll());
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(ReviewHistoryStore.TOPIC, () -> {
            storeEpoch.incrementAndGet();
            if (ApplicationManager.getApplication().isDispatchThread()) historyReloaded();
            else ApplicationManager.getApplication().invokeLater(this::historyReloaded, project.getDisposed());
        });
        project.getMessageBus().connect(this).subscribe(ContextAnalysisManager.CHANGED, this::contextChanged);
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override public void after(@NotNull List<? extends VFileEvent> events) {
                boolean changed = false;
                for (var event : events) {
                    if (event instanceof VFileMoveEvent move) {
                        movePath(move.getOldPath(), move.getNewPath());
                        invalidatePath(move.getNewPath(), false);
                        changed = true;
                    } else if (event instanceof VFilePropertyChangeEvent property && property.isRename()) {
                        movePath(property.getOldPath(), property.getNewPath());
                        invalidatePath(property.getNewPath(), false);
                        changed = true;
                    } else if (event instanceof VFileDeleteEvent deletion && keyForPath(deletion.getPath()) != null) {
                        invalidatePath(deletion.getPath(), true);
                        changed = true;
                    } else if (event instanceof VFileCreateEvent creation && keyForPath(creation.getPath()) != null) {
                        changed = true;
                    } else if (event instanceof VFileContentChangeEvent content && !content.isFromSave()
                            && keyForPath(content.getPath()) != null) {
                        // The document listener handles its eventual reload. Unrelated VFS events
                        // must not discard known row identities in other open documents.
                        changed = true;
                    }
                }
                // Deletion and Git checkout never remove the history.
                if (changed) refreshAll();
            }
        });
        refreshAll();
    }
    public boolean supports(Document document) { return keyFor(document) != null; }
    public ReviewHistory history(Document document) {
        String key = keyFor(document);
        return key == null ? ReviewHistory.empty() : store.history(project, key);
    }
    public FileProjection projection(Document document) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        var history = history(document);
        var state = runtime.get(document);
        if (state == null) {
            state = new RuntimeProjection();
            state.hints = savedHints(document);
            runtime.put(document, state);
        }
        if (state.projection != null && state.stamp == document.getModificationStamp() && state.revision == history.revision()) return state.projection;
        String text = document.getText();
        state.text = text;
        state.stamp = document.getModificationStamp();
        state.revision = history.revision();
        state.ready = history.evidence().isEmpty();
        // Until PSI finishes, show exact facts as pending, never as unconfirmed green.
        state.projection = exactProjection(history, text, false);
        if (!state.ready) schedule(document, state, history, text);
        return state.projection;
    }
    private static ReviewHistory exactHistory(ReviewHistory history, String text) {
        var snapshot = FileSnapshot.create(text);
        var evidence = history.evidence().stream().filter(e -> e.snapshotId().equals(snapshot.id())).toList();
        var ids = evidence.stream().map(ReviewEvidence::id).collect(java.util.stream.Collectors.toSet());
        return new ReviewHistory(Map.of(snapshot.id(), snapshot), evidence,
                history.revocations().stream().filter(revocation -> ids.contains(revocation.evidenceId())).toList(), history.revision());
    }
    private static FileProjection exactProjection(ReviewHistory history, String text, boolean contextConfirmed) {
        var exact = exactHistory(history, text);
        var snapshot = FileSnapshot.create(text);
        var context = contextConfirmed ? ContextMap.fallback(text)
                : new ContextMap("pending-context", Collections.nCopies(1 + (int) text.chars().filter(c -> c == '\n').count(), null));
        return ReviewHistoryEngine.project(exact, text, Map.of(snapshot.id(), context), context, ProjectionHints.empty());
    }
    private void schedule(Document document, RuntimeProjection target, ReviewHistory history, String text) {
        var file = FileDocumentManager.getInstance().getFile(document);
        long stamp = target.stamp, generation = ++target.generation;
        long epoch = storeEpoch.get();
        long contextGeneration = project.getService(ContextAnalysisManager.class).generation();
        var hints = target.hints;
        scheduler.schedule(document, () -> computer.capture(history, file, text),
                contexts -> {
                    var result = computer.computeResult(history, text, hints, contexts);
                    return new PreparedProjection(result, store.prepareCheckpoint(history, text, result.checkpoint()));
                }, prepared -> {
            if (disposed || storeEpoch.get() != epoch || runtime.get(document) != target || target.generation != generation
                    || document.getModificationStamp() != stamp || history(document).revision() != history.revision()
                    || project.getService(ContextAnalysisManager.class).generation() != contextGeneration) return;
            String key = keyFor(document);
            if (key == null || !store.commitCheckpoint(project, key, prepared.checkpoint())) {
                target.stamp = -1;
                refreshDocument(document);
                return;
            }
            var result = prepared.result();
            target.projection = result.projection();
            target.ready = true;
            target.hints = result.hints();
            refreshDocument(document);
        }, failure -> {
            if (disposed || runtime.get(document) != target || target.generation != generation || storeEpoch.get() != epoch) return;
            LOG.warn("Review projection failed; keeping only unconfirmed exact facts", failure);
            // A terminal worker failure has not resolved historical row identities. Keep the
            // edit guard and clear expansion active; the cached stamp prevents an automatic retry loop.
            target.ready = false;
            notifyInfo("Review state could not be confirmed. Edit or reopen the file to retry; saved history is preserved.");
            refreshDocument(document);
        });
    }
    private ProjectionHints savedHints(Document document) {
        String key = keyFor(document);
        return key == null ? ProjectionHints.empty() : store.checkpoint(project, key, document.getText());
    }
    private void savePendingHints(Document document, ProjectionHints hints) {
        String key = keyFor(document);
        if (key != null) store.checkpointPending(project, key, document.getText(), hints);
    }
    public boolean isProjectionReady(Document document) { projection(document); return runtime.get(document).ready && pendingRevocations == 0; }
    public List<ReviewedRange> getRanges(Document document) { return projection(document).reviewedRanges(); }
    public void mark(Document document, int start, int end) {
        String key = keyFor(document);
        if (key == null || start >= end) return;
        projection(document);
        if (store.mark(project, key, document.getText(), start, end) == null) return;
        var state = runtime.get(document);
        state.lastMarkedStart = start;
        state.lastMarkedEnd = end;
        state.lastMarkedText = document.getText();
        invalidate(document);
    }
    public void toggle(Document document, int start, int end) {
        if (start >= end) return;
        var current = projection(document);
        boolean hasNonblank = false, allReviewed = true;
        for (var row : current.rows()) {
            if (row.start() >= end || row.end() <= start || blank(document, row.line())) continue;
            hasNonblank = true;
            if (row.status() != FileProjection.Status.REVIEWED) allReviewed = false;
        }
        if (!hasNonblank) return;
        var state = runtime.get(document);
        boolean repeatedPendingMark = !state.ready && state.lastMarkedStart <= start && state.lastMarkedStart >= 0
                && state.lastMarkedEnd >= end && document.getText().equals(state.lastMarkedText);
        if (allReviewed || repeatedPendingMark) clear(document, start, end); else mark(document, start, end);
    }
    public void clear(Document document, int start, int end) {
        String key = keyFor(document);
        if (key == null) return;
        var current = projection(document);
        var before = history(document);
        var state = runtime.get(document);
        state.lastMarkedStart = -1;
        var hints = state.hints;
        String text = document.getText();
        var refs = current.rows().stream().filter(row -> row.start() < end && row.end() > start)
                .flatMap(row -> row.revocableRefs().stream()).distinct().toList();
        store.revoke(project, key, refs);
        // A rapid second Ctrl+M may precede the full historical projection. Finish revoking
        // older variants from the captured version, without touching subsequently created proofs.
        if (!state.ready) {
            long epoch = storeEpoch.get();
            var file = FileDocumentManager.getInstance().getFile(document);
            pendingRevocations++;
            scheduler.submit(() -> computer.capture(before, file, text),
                    contexts -> computer.project(before, text, hints, contexts), resolved -> {
                        pendingRevocations--;
                        if (epoch != storeEpoch.get()) return;
                        var related = resolved.rows().stream().filter(row -> row.start() < end && row.end() > start)
                                .flatMap(row -> row.revocableRefs().stream()).distinct().toList();
                        // Proof ownership stays stable when the file is renamed, deleted or its old path reused.
                        store.revokeByEvidence(project, related);
                        invalidate(document);
                    }, failure -> {
                        if (disposed) return;
                        pendingRevocations--;
                        if (epoch == storeEpoch.get()) {
                            LOG.warn("Could not finish revoking historical review variants", failure);
                            notifyInfo("Could not revoke all historical versions. Use Clear All Reviews in File or retry.");
                            refreshDocument(document);
                        }
                    });
        }
        invalidate(document);
    }
    public void clearFile(Document document) {
        String key = keyFor(document);
        if (key == null) return;
        store.clearFile(project, key);
        invalidate(document);
    }
    private void invalidate(Document document) {
        var state = runtime.get(document);
        if (state != null) state.stamp = -1;
        refreshDocument(document);
    }
    public void historyReloaded() {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(this::historyReloaded, project.getDisposed());
            return;
        }
        scheduler.cancelAll();
        pendingRevocations = 0;
        runtime.clear();
        refreshAll();
    }
    private void contextChanged() {
        for (var document : List.copyOf(runtime.keySet())) {
            scheduler.cancel(document);
            runtime.get(document).stamp = -1;
            presentation.clearConfirmed(document);
        }
        refreshAll();
    }
    public Coverage coverage(Document document) {
        var projection = projection(document);
        int total = 0, reviewed = 0, yellow = 0;
        for (int line = 0; line < document.getLineCount(); line++) {
            if (blank(document, line)) continue;
            total++;
            var status = statusAt(projection, line);
            if (status == FileProjection.Status.REVIEWED) reviewed++;
            else if (status == FileProjection.Status.NEEDS_REVIEW) yellow++;
        }
        return new Coverage(reviewed, total, yellow, projection.deletedCount());
    }
    public int nextUnreviewedLine(Document document, int currentLine) {
        return findUnreviewedLine(document, currentLine, 1);
    }
    public int previousUnreviewedLine(Document document, int currentLine) {
        return findUnreviewedLine(document, currentLine, -1);
    }
    private int findUnreviewedLine(Document document, int currentLine, int direction) {
        var projection = projection(document);
        for (int step = 1; step <= document.getLineCount(); step++) {
            int line = Math.floorMod(currentLine + direction * step, document.getLineCount());
            if (!blank(document, line) && statusAt(projection, line) != FileProjection.Status.REVIEWED) return line;
        }
        return -1;
    }
    private static FileProjection.Status statusAt(FileProjection projection, int line) {
        return line < projection.rows().size() ? projection.rows().get(line).status() : FileProjection.Status.UNREVIEWED;
    }
    private static boolean blank(Document document, int line) {
        for (int at = document.getLineStartOffset(line); at < document.getLineEndOffset(line); at++)
            if (!Character.isWhitespace(document.getCharsSequence().charAt(at))) return false;
        return true;
    }
    public List<ReviewVersion> reviewVersions(Document document) { return historyActions.versions(document); }
    public void showChanges(Document document) { historyActions.showChanges(document); }
    public void reattachHistory(Document document) { historyActions.reattachHistory(document); }
    private String keyFor(Document document) { return files.relativePath(document); }
    private String keyForPath(String path) { return files.relativePath(path); }
    private boolean movePath(String oldPath, String newPath) {
        String oldKey = keyForPath(oldPath), newKey = keyForPath(newPath);
        return oldKey != null && newKey != null && store.movePath(project, oldKey, newKey);
    }
    private void invalidatePath(String path, boolean remove) {
        var iterator = runtime.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var file = FileDocumentManager.getInstance().getFile(entry.getKey());
            if (file != null && FileUtil.isAncestor(path, file.getPath(), false)) {
                if (remove) { scheduler.cancel(entry.getKey()); iterator.remove(); }
                else entry.getValue().stamp = -1;
            }
        }
    }
    private void refreshDocument(Document document) {
        if (disposed || !pendingRefresh.add(document)) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            pendingRefresh.remove(document);
            if (disposed || project.isDisposed()) return;
            for (var editor : EditorFactory.getInstance().getEditors(document, project)) render(editor);
            project.getMessageBus().syncPublisher(COVERAGE_CHANGED).coverageChanged();
        }, project.getDisposed());
    }
    private void refreshAll() {
        if (disposed || allRefreshPending) return;
        allRefreshPending = true;
        ApplicationManager.getApplication().invokeLater(() -> {
            allRefreshPending = false;
            if (disposed || project.isDisposed()) return;
            for (var editor : EditorFactory.getInstance().getAllEditors()) if (editor.getProject() == project) render(editor);
            project.getMessageBus().syncPublisher(COVERAGE_CHANGED).coverageChanged();
        }, project.getDisposed());
    }
    private void render(Editor editor) {
        presentation.render(editor, supports(editor.getDocument()) ? projection(editor.getDocument()) : null);
    }
    public void notifyInfo(String text) {
        NotificationGroupManager.getInstance().getNotificationGroup("Review Marks")
                .createNotification(text, NotificationType.INFORMATION).setTitle("DoReview").notify(project);
    }
    @Override public void dispose() {
        disposed = true;
        scheduler.dispose();
        presentation.dispose();
        Runnable cleanup = () -> {
            pendingRefresh.clear();
            allRefreshPending = false;
            runtime.clear();
        };
        if (ApplicationManager.getApplication().isDispatchThread()) cleanup.run();
        else ApplicationManager.getApplication().invokeLater(cleanup);
    }
    public record Coverage(int reviewed, int total, int needsReview, int deleted) {
        public Coverage(int reviewed, int total) { this(reviewed, total, 0, 0); }
        public int percent() { return total == 0 ? 0 : (int) (100L * reviewed / total); }
    }
    private static final class RuntimeProjection {
        String text = "";
        long stamp = -1, revision = -1, generation;
        boolean ready;
        int lastMarkedStart = -1, lastMarkedEnd;
        String lastMarkedText;
        ProjectionHints hints = ProjectionHints.empty();
        FileProjection projection;
    }
    private record PreparedProjection(ReviewProjectionComputer.Result result,
                                      ReviewHistoryStore.PreparedCheckpoint checkpoint) { }
}
