package dev.doreview.ide;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import dev.doreview.core.ReviewHistory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** User-facing history navigation. Matching and editor resources belong to other components. */
final class ReviewHistoryActions {
    private final Project project;
    private final ReviewHistoryStore store;
    private final ReviewFileAccess files;
    private final Function<Document, ReviewHistory> histories;
    private final Runnable historyChanged;
    private final Consumer<String> notifyInfo;

    ReviewHistoryActions(Project project, ReviewHistoryStore store, ReviewFileAccess files,
                         Function<Document, ReviewHistory> histories, Runnable historyChanged, Consumer<String> notifyInfo) {
        this.project = project;
        this.store = store;
        this.files = files;
        this.histories = histories;
        this.historyChanged = historyChanged;
        this.notifyInfo = notifyInfo;
    }

    List<ReviewVersion> versions(Document document) {
        var history = histories.apply(document);
        Map<String, Long> dates = new HashMap<>();
        for (var evidence : history.evidence()) dates.merge(evidence.snapshotId(), evidence.createdAt(), Math::max);
        return dates.entrySet().stream().filter(e -> history.snapshots().containsKey(e.getKey()))
                .map(e -> new ReviewVersion(history.snapshots().get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparingLong(ReviewVersion::createdAt).reversed()).toList();
    }
    public void showChanges(Document document) {
        var versions = versions(document);
        if (versions.isEmpty()) { notifyInfo.accept("This file has no saved review versions."); return; }
        if (versions.size() == 1) { showDiff(document, versions.getFirst()); return; }
        JBPopupFactory.getInstance().createPopupChooserBuilder(versions).setTitle("DoReview — Compare with a reviewed version")
                .setItemChosenCallback(version -> showDiff(document, version)).createPopup().showInFocusCenter();
    }
    private void showDiff(Document document, ReviewVersion version) {
        var file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) return;
        var factory = DiffContentFactory.getInstance();
        DiffManager.getInstance().showDiff(project, new SimpleDiffRequest("DoReview — Changes — " + file.getName(),
                factory.create(project, version.snapshot().text(), file.getFileType()),
                factory.create(project, document.getText(), file.getFileType()), version.toString(), "Current file"));
    }
    public void reattachHistory(Document document) {
        String target = files.relativePath(document);
        if (target == null) return;
        var candidates = store.paths(project).stream().filter(path -> !path.equals(target))
                .filter(path -> !Files.exists(Path.of(project.getBasePath()).resolve(path))).sorted().toList();
        if (candidates.isEmpty()) { notifyInfo.accept("There is no missing file with saved review history to attach."); return; }
        JBPopupFactory.getInstance().createPopupChooserBuilder(candidates).setTitle("DoReview — Attach review history")
                .setItemChosenCallback(path -> {
                    if (!store.movePath(project, path, target)) notifyInfo.accept("History was not attached: this path already has independent history.");
                    historyChanged.run();
                })
                .createPopup().showInFocusCenter();
    }
}
