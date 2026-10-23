package dev.doreview.ide;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.doreview.core.EvidenceRef;
import dev.doreview.core.ReviewHistory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/** User-facing history navigation. Matching and editor resources belong to other components. */
final class ReviewHistoryActions {
    interface Queries {
        boolean shared();
        String authors(String path, List<EvidenceRef> refs);
        void versions(String path, Consumer<List<SharedHistoryRepository.Version>> callback);
        void snapshot(String path, String hash, Consumer<String> callback);
        void paths(Consumer<List<String>> callback);
        void move(String oldPath, String newPath, Consumer<Boolean> callback);
    }

    interface View {
        <T> void choose(List<T> choices, String title, Consumer<T> selected);
        void diff(Document document, String snapshotText, String label);
    }

    private record VersionChoice(String snapshotId, long createdAt, String authors) {
        @Override public String toString() { return ReviewVersion.label(snapshotId, createdAt, authors); }
    }

    private final Project project;
    private final Queries queries;
    private final View view;
    private final ReviewFileAccess files;
    private final Function<Document, ReviewHistory> histories;
    private final Runnable historyChanged;
    private final Consumer<String> notifyInfo;
    private final Map<Document, Long> versionRequests = new WeakHashMap<>();
    private final Map<Document, Long> attachmentRequests = new WeakHashMap<>();
    private long nextRequest;

    ReviewHistoryActions(Project project, ReviewHistoryStore store, ReviewFileAccess files,
                         Function<Document, ReviewHistory> histories, Runnable historyChanged, Consumer<String> notifyInfo) {
        this(project, new Queries() {
            @Override public boolean shared() { return store.isShared(); }
            @Override public String authors(String path, List<EvidenceRef> refs) { return store.authors(project, path, refs); }
            @Override public void versions(String path, Consumer<List<SharedHistoryRepository.Version>> callback) { store.queryVersions(project, path, callback); }
            @Override public void snapshot(String path, String hash, Consumer<String> callback) { store.readSnapshot(project, path, hash, callback); }
            @Override public void paths(Consumer<List<String>> callback) { store.queryPaths(project, callback); }
            @Override public void move(String oldPath, String newPath, Consumer<Boolean> callback) { store.movePathAsync(project, oldPath, newPath, callback); }
        }, files, histories, historyChanged, notifyInfo, null);
    }

    ReviewHistoryActions(Project project, Queries queries, ReviewFileAccess files,
            Function<Document, ReviewHistory> histories, Runnable historyChanged, Consumer<String> notifyInfo, View view) {
        this.project = project;
        this.queries = queries;
        this.files = files;
        this.histories = histories;
        this.historyChanged = historyChanged;
        this.notifyInfo = notifyInfo;
        this.view = view == null ? new View() {
            @Override public <T> void choose(List<T> choices, String title, Consumer<T> selected) {
                JBPopupFactory.getInstance().createPopupChooserBuilder(choices).setTitle(title)
                        .setItemChosenCallback(selected::accept).createPopup().showInFocusCenter();
            }
            @Override public void diff(Document document, String snapshotText, String label) { showDiff(document, snapshotText, label); }
        } : view;
    }

    List<ReviewVersion> versions(Document document) {
        var history = histories.apply(document);
        Map<String, Long> dates = new HashMap<>();
        Map<String, List<EvidenceRef>> refs = new HashMap<>();
        for (var evidence : history.evidence()) {
            dates.merge(evidence.snapshotId(), evidence.createdAt(), Math::max);
            var range = evidence.ranges().getFirst();
            refs.computeIfAbsent(evidence.snapshotId(), ignored -> new ArrayList<>())
                    .add(new EvidenceRef(evidence.id(), range.start(), range.end()));
        }
        String path = files.relativePath(document);
        return dates.entrySet().stream().filter(e -> history.snapshots().containsKey(e.getKey()))
                .map(e -> new ReviewVersion(history.snapshots().get(e.getKey()), e.getValue(),
                        path == null ? "" : queries.authors(path, refs.get(e.getKey()))))
                .sorted(Comparator.comparingLong(ReviewVersion::createdAt).reversed()
                        .thenComparing(version -> version.snapshot().id())).toList();
    }
    public void showChanges(Document document) {
        String path = files.relativePath(document);
        if (path == null || project.isDisposed()) return;
        long request = ++nextRequest;
        versionRequests.put(document, request);
        if (!queries.shared()) {
            chooseVersion(versions(document), version -> {
                if (current(document, path, versionRequests, request)) view.diff(document, version.snapshot().text(), version.toString());
            });
            return;
        }
        queries.versions(path, metadata -> {
            if (!current(document, path, versionRequests, request)) return;
            var choices = metadata.stream().map(version -> new VersionChoice(version.snapshotId(), version.createdAt(),
                            version.author() == null ? "" : version.author().name()))
                    .sorted(Comparator.comparingLong(VersionChoice::createdAt).reversed().thenComparing(VersionChoice::snapshotId)).toList();
            chooseVersion(choices, selected -> {
                if (!current(document, path, versionRequests, request)) return;
                queries.snapshot(path, selected.snapshotId(), text -> {
                    if (!current(document, path, versionRequests, request)) return;
                    if (text == null) { notifyInfo.accept("The saved review version is unavailable."); return; }
                    view.diff(document, text, selected.toString());
                });
            });
        });
    }

    private <T> void chooseVersion(List<T> choices, Consumer<T> selected) {
        if (choices.isEmpty()) { notifyInfo.accept("This file has no saved review versions."); return; }
        if (choices.size() == 1) selected.accept(choices.getFirst());
        else view.choose(choices, "DoReview — Compare with a reviewed version", selected);
    }

    private void showDiff(Document document, String snapshotText, String label) {
        var file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !file.isValid() || project.isDisposed()) return;
        var factory = DiffContentFactory.getInstance();
        DiffManager.getInstance().showDiff(project, new SimpleDiffRequest("DoReview — Changes — " + file.getName(),
                factory.create(project, snapshotText, file.getFileType()),
                factory.create(project, document.getText(), file.getFileType()), label, "Current file"));
    }
    public void reattachHistory(Document document) {
        String target = files.relativePath(document);
        String base = project.getBasePath();
        if (target == null || base == null || project.isDisposed()) return;
        long request = ++nextRequest;
        attachmentRequests.put(document, request);
        queries.paths(paths -> {
            if (!current(document, target, attachmentRequests, request)) return;
            AppExecutorUtil.getAppExecutorService().execute(() -> {
                var candidates = paths.stream().filter(path -> !path.equals(target))
                        .filter(path -> !Files.exists(Path.of(base).resolve(path))).distinct().sorted().toList();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!current(document, target, attachmentRequests, request)) return;
                    if (candidates.isEmpty()) { notifyInfo.accept("There is no missing file with saved review history to attach."); return; }
                    view.choose(candidates, "DoReview — Attach review history", path -> {
                        if (!current(document, target, attachmentRequests, request)) return;
                        queries.move(path, target, moved -> {
                            if (!current(document, target, attachmentRequests, request)) return;
                            if (!moved) notifyInfo.accept("History was not attached: this path already has independent history.");
                            historyChanged.run();
                        });
                    });
                }, project.getDisposed());
            });
        });
    }

    private boolean current(Document document, String path, Map<Document, Long> requests, long request) {
        return !project.isDisposed() && Long.valueOf(request).equals(requests.get(document))
                && path.equals(files.relativePath(document));
    }
}
