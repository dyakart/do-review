package dev.doreview.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VirtualFile;
import dev.doreview.core.FileProjection;
import dev.doreview.ide.context.ContextAnalysisManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Read-only, cancellable coverage calculation; it never opens editors or commits checkpoints. */
final class ReviewSelectionCoverage {
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private final Project project;
    private final ReviewProjectionComputer computer;

    ReviewSelectionCoverage(Project project) {
        this.project = project;
        computer = new ReviewProjectionComputer(project);
    }

    record Result(int files, int skipped, long reviewed, long total, long needsReview, long deleted) {
        int percent() {
            return total == 0 ? 0 : java.math.BigInteger.valueOf(reviewed).multiply(java.math.BigInteger.valueOf(100))
                    .divide(java.math.BigInteger.valueOf(total)).intValue();
        }

        String message() {
            if (files == 0) return "No supported text files found in the selection. Skipped entries: " + skipped + ".";
            return "DoReview: reviewed " + reviewed + " / " + total + " non-blank lines (" + percent()
                    + "%) across " + files + " files. Needs review: " + needsReview
                    + "; deleted reviewed lines: " + deleted + "."
                    + (skipped == 0 ? "" : " Skipped entries: " + skipped
                    + " (binary, excluded, metadata, symbolic links or oversized files).");
        }
    }

    record Selection(List<VirtualFile> files, int skipped, Map<VirtualFile, Set<VirtualFile>> directories) {
        Selection { files = List.copyOf(files); directories = Map.copyOf(directories); }
    }

    private record Captured(VirtualFile file, Document document, String path, String text,
                            long stamp, long fileStamp, boolean git) { }
    private record Guard(VirtualFile file, WeakReference<Document> document, String path, long stamp, long fileStamp) { }

    static boolean supports(Project project, VirtualFile file) {
        if (project == null || project.isDisposed() || project.getBasePath() == null || file == null
                || !file.isValid() || !file.isInLocalFileSystem() || file.is(VFileProperty.SYMLINK)
                || !FileUtil.isAncestor(project.getBasePath(), file.getPath(), false)) return false;
        if (ProjectFileIndex.getInstance(project).isExcluded(file)) return false;
        for (var parent = file.getParent(); parent != null
                && FileUtil.isAncestor(project.getBasePath(), parent.getPath(), false); parent = parent.getParent()) {
            if (parent.is(VFileProperty.SYMLINK)) return false;
        }
        String relative = FileUtil.getRelativePath(project.getBasePath(), file.getPath(), '/');
        if (relative == null) return false;
        for (String part : relative.split("/")) {
            if (part.equalsIgnoreCase(".git") || part.equalsIgnoreCase(".doreview") || part.equalsIgnoreCase(".idea")) return false;
        }
        return file.isDirectory() || !file.getFileType().isBinary();
    }

    static Selection collect(Project project, VirtualFile[] selected, ProgressIndicator indicator) {
        return read(project, indicator, () -> {
            var roots = new HashSet<>(Arrays.asList(selected));
            roots.remove(null);
            var queue = new ArrayDeque<VirtualFile>();
            // A selected directory owns its descendants, even if they were also selected.
            for (var root : roots) {
                boolean covered = false;
                for (var parent = root.getParent(); parent != null; parent = parent.getParent()) {
                    if (roots.contains(parent)) { covered = true; break; }
                }
                if (!covered) queue.add(root);
            }
            var visited = new HashSet<VirtualFile>();
            var files = new ArrayList<VirtualFile>();
            var directories = new java.util.HashMap<VirtualFile, Set<VirtualFile>>();
            int skipped = 0;
            while (!queue.isEmpty()) {
                indicator.checkCanceled();
                ProgressManager.checkCanceled();
                var file = queue.removeFirst();
                if (!visited.add(file)) continue;
                if (!supports(project, file)) { skipped++; continue; }
                if (file.isDirectory()) {
                    var children = Arrays.asList(file.getChildren());
                    directories.put(file, Set.copyOf(children));
                    queue.addAll(children);
                } else files.add(file);
            }
            files.sort(java.util.Comparator.comparing(VirtualFile::getPath));
            return new Selection(files, skipped, directories);
        });
    }

    Result calculate(VirtualFile[] selected, ProgressIndicator indicator) {
        if (ApplicationManager.getApplication().isReadAccessAllowed()
                || ApplicationManager.getApplication().isDispatchThread()) {
            throw new IllegalStateException("Selection coverage must run in the background outside a read action");
        }
        var selection = collect(project, selected, indicator);
        var reader = ReviewHistoryStore.getInstance().coverageHistory(project);
        var capturedFiles = new ArrayList<Guard>();
        long generation = project.getService(ContextAnalysisManager.class).generation();
        boolean syntaxUsed = false;
        long reviewed = 0, total = 0, yellow = 0, deleted = 0;
        int included = 0, skipped = selection.skipped();
        indicator.setIndeterminate(false);
        for (var file : selection.files()) {
            indicator.checkCanceled();
            indicator.setText2(file.getPresentableUrl());
            var captured = read(project, indicator, () -> capture(file));
            if (captured == null) { skipped++; }
            else {
                String path = new ReviewFileAccess(project).relativePath(captured.path());
                if (path == null) throw changed();
                var input = await(reader.read(path, captured.text(), captured.git()), indicator);
                if (input.history().evidence().isEmpty()) {
                    // Unmarked files contribute to the denominator without parsing syntax
                    // or diffing snapshots: no evidence can make any row reviewed.
                    total += nonBlankLines(captured.text(), indicator);
                } else {
                    var contexts = read(project, indicator, () -> {
                        requireCurrent(captured);
                        return computer.capture(input.history(), file, captured.text());
                    });
                    var projection = computer.project(input.history(), captured.text(), input.hints(), contexts);
                    for (var row : projection.rows()) {
                        indicator.checkCanceled();
                        if (captured.text().substring(row.start(), row.end()).isBlank()) continue;
                        total++;
                        if (row.status() == FileProjection.Status.REVIEWED) reviewed++;
                        else if (row.status() == FileProjection.Status.NEEDS_REVIEW) yellow++;
                    }
                    deleted += projection.deletedCount();
                    syntaxUsed |= !captured.git();
                }
                // Do not retain every loaded document buffer while inspecting a large tree.
                // Unsaved documents remain owned by FileDocumentManager; saved ones may be collected.
                capturedFiles.add(new Guard(file, new WeakReference<>(captured.document()), captured.path(),
                        captured.stamp(), captured.fileStamp()));
                included++;
            }
            indicator.setFraction((double) (included + skipped - selection.skipped()) / selection.files().size());
        }
        indicator.checkCanceled();
        if (!await(reader.isCurrent(), indicator)) throw changed();
        boolean checkSyntax = syntaxUsed;
        read(project, indicator, () -> {
            // A folder report must also reject additions/removals after traversal.
            for (var directory : selection.directories().entrySet()) {
                indicator.checkCanceled();
                ProgressManager.checkCanceled();
                if (!directory.getKey().isValid() || !directory.getValue().equals(
                        Set.copyOf(Arrays.asList(directory.getKey().getChildren())))) throw changed();
            }
            for (var guard : capturedFiles) {
                indicator.checkCanceled();
                ProgressManager.checkCanceled();
                var document = guard.document().get();
                if (document == null) document = FileDocumentManager.getInstance().getCachedDocument(guard.file());
                if (!guard.file().isValid() || !guard.path().equals(guard.file().getPath())
                        || guard.file().getModificationStamp() != guard.fileStamp()
                        || document != null && document.getModificationStamp() != guard.stamp()) throw changed();
            }
            if (checkSyntax && generation != project.getService(ContextAnalysisManager.class).generation()) throw changed();
            return true;
        });
        return new Result(included, skipped, reviewed, total, yellow, deleted);
    }

    private Captured capture(VirtualFile file) {
        if (!supports(project, file)) throw changed();
        if (file.getLength() > MAX_FILE_BYTES) return null;
        var manager = FileDocumentManager.getInstance();
        var document = manager.getCachedDocument(file);
        if (document == null) document = manager.getDocument(file);
        if (document == null) return null;
        // UTF-8 is at least one byte per UTF-16 code unit after newline normalization.
        if (document.getTextLength() > MAX_FILE_BYTES) return null;
        String text = document.getImmutableCharSequence().toString();
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_FILE_BYTES) return null;
        return new Captured(file, document, file.getPath(), text, document.getModificationStamp(),
                file.getModificationStamp(), computer.isGitFile(file));
    }

    private static void requireCurrent(Captured captured) {
        if (!captured.file().isValid() || !captured.path().equals(captured.file().getPath())
                || captured.document().getModificationStamp() != captured.stamp()
                || captured.file().getModificationStamp() != captured.fileStamp()) throw changed();
    }

    private static long nonBlankLines(String text, ProgressIndicator indicator) {
        long count = 0;
        for (int start = 0; start < text.length();) {
            indicator.checkCanceled();
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline;
            if (!text.substring(start, end).isBlank()) count++;
            start = end + 1;
        }
        return count;
    }

    private static IllegalStateException changed() {
        return new IllegalStateException("Files or review history changed during calculation. Run coverage again.");
    }

    private static <T> T read(Project project, ProgressIndicator indicator, Supplier<T> capture) {
        return ReadAction.nonBlocking(() -> {
            indicator.checkCanceled();
            ProgressManager.checkCanceled();
            return capture.get();
        }).expireWith(project).expireWhen(indicator::isCanceled).executeSynchronously();
    }

    private static <T> T await(CompletableFuture<T> future, ProgressIndicator indicator) {
        try {
            while (true) {
                indicator.checkCanceled();
                try { return future.get(50, TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) { /* Keep cancellation responsive while storage owns its worker. */ }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new com.intellij.openapi.progress.ProcessCanceledException(interrupted);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Cannot load review history", failure.getCause());
        } finally {
            if (!future.isDone()) future.cancel(false);
        }
    }
}
