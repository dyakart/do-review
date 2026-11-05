package dev.doreview.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.doreview.core.ContextMap;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ProjectionWorkBudget;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewProjectionWorkspace;
import dev.doreview.ide.context.ContextAnalysisManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Selects Git line correspondence for repositories and syntax context for other files. */
public final class ReviewProjectionComputer {
    public record CapturedContexts(ContextMap current, Map<String, ContextMap> baselines, Path gitRoot) {
        public CapturedContexts { baselines = Map.copyOf(baselines); }
        public CapturedContexts(ContextMap current, Map<String, ContextMap> baselines) {
            this(current, baselines, null);
        }
        public boolean gitBased() { return gitRoot != null; }
    }
    public record Result(FileProjection projection, ProjectionHints hints, ProjectionHints checkpoint) { }
    record Limits(int historicalSnapshots, long totalCharacters, int snapshotCharacters) {
        static Limits defaults() { return new Limits(64, 10_000_000, 2_000_000); }
    }

    private final Project project;
    private final Limits limits;
    private final ReviewGitDiff gitDiff = new ReviewGitDiff();
    public ReviewProjectionComputer(Project project) { this(project, Limits.defaults()); }
    ReviewProjectionComputer(Project project, Limits limits) { this.project = project; this.limits = limits; }

    /** Called only by the scheduler's read phase; no PSI or adapter object escapes this method. */
    public CapturedContexts capture(ReviewHistory history, VirtualFile file, String text) {
        ApplicationManager.getApplication().assertReadAccessAllowed();
        Path gitRoot = gitRoot(file);
        if (gitRoot != null) {
            // Repository reviews follow Git's line diff. PSI headers and whole-file
            // fingerprints must not invalidate untouched rows outside Git hunks.
            var contexts = new LinkedHashMap<String, ContextMap>();
            for (String snapshot : history.snapshots().keySet()) {
                ProgressManager.checkCanceled();
                contexts.put(snapshot, ContextMap.textOnly());
            }
            return new CapturedContexts(gitContext(text), contexts, gitRoot);
        }
        var manager = project.getService(ContextAnalysisManager.class);
        ContextMap current = text.length() <= limits.snapshotCharacters && text.length() <= limits.totalCharacters
                ? manager.analyzeUnderRead(file, text) : limited();
        long consumed = text.length() <= limits.snapshotCharacters && text.length() <= limits.totalCharacters ? text.length() : 0;
        var snapshots = ReviewHistoryEngine.snapshotsByRecency(history, ProgressManager::checkCanceled);
        var contexts = new LinkedHashMap<String, ContextMap>();
        int historical = 0;
        for (var snapshot : snapshots) {
            ProgressManager.checkCanceled();
            if (snapshot.text().equals(text)) contexts.put(snapshot.id(), current);
            else if (historical < limits.historicalSnapshots && snapshot.text().length() <= limits.snapshotCharacters
                    && consumed + snapshot.text().length() <= limits.totalCharacters) {
                contexts.put(snapshot.id(), manager.analyzeUnderRead(file, snapshot.text()));
                historical++;
                consumed += snapshot.text().length();
            } else contexts.put(snapshot.id(), limited());
        }
        return new CapturedContexts(current, contexts);
    }

    public FileProjection project(ReviewHistory history, String text, ProjectionHints hints, CapturedContexts contexts) {
        requirePurePhase();
        if (contexts.gitBased()) {
            return projectWithGit(history, text, contexts);
        }
        return ReviewHistoryEngine.project(history, text, contexts.baselines(), contexts.current(), hints,
                ProjectionWorkBudget.defaults(), ProgressManager::checkCanceled);
    }

    public Result computeResult(ReviewHistory history, String text, ProjectionHints hints, CapturedContexts contexts) {
        requirePurePhase();
        if (contexts.gitBased()) {
            var projection = projectWithGit(history, text, contexts);
            // Git can reconstruct correspondence from immutable review text. Persisting
            // edit-derived denials here would poison another checkout and add noise.
            return new Result(projection, ProjectionHints.empty(), ProjectionHints.empty());
        }
        var budget = ProjectionWorkBudget.defaults();
        var workspace = new ReviewProjectionWorkspace(text, budget, ProgressManager::checkCanceled);
        var projection = ReviewHistoryEngine.project(history, contexts.baselines(), contexts.current(), hints, workspace);
        ProgressManager.checkCanceled();
        var resolved = hints.mergeResolved(ProjectionHints.fromProjection(history, projection));
        ProgressManager.checkCanceled();
        var checkpoint = resolved.compactForCheckpoint(history, workspace);
        return new Result(projection, resolved, checkpoint);
    }

    private FileProjection projectWithGit(ReviewHistory history, String text, CapturedContexts contexts) {
        try (var mapper = gitDiff.mapper(contexts.gitRoot(), text, ProgressManager::checkCanceled)) {
            var workspace = new ReviewProjectionWorkspace(text, ProjectionWorkBudget.defaults(),
                    ProgressManager::checkCanceled, mapper);
            return ReviewHistoryEngine.project(history, contexts.baselines(), contexts.current(),
                    ProjectionHints.empty(), workspace);
        } catch (IOException unavailable) {
            throw new UncheckedIOException(unavailable);
        }
    }

    boolean isGitFile(VirtualFile file) { return gitRoot(file) != null; }

    private Path gitRoot(VirtualFile file) {
        if (file == null || !file.isInLocalFileSystem() || project.getBasePath() == null) return null;
        try {
            Path root = ReviewRepositoryPaths.rootFor(file.toNioPath(), Path.of(project.getBasePath()));
            return Files.exists(root.resolve(".git")) ? root : null;
        } catch (IllegalArgumentException | UnsupportedOperationException unavailable) {
            return null;
        }
    }

    static ContextMap gitContext(String text) {
        return ContextMap.textOnly();
    }

    private static void requirePurePhase() {
        if (ApplicationManager.getApplication().isReadAccessAllowed()) {
            throw new IllegalStateException("Pure review computation must run outside a read action");
        }
    }
    private static ContextMap limited() { return new ContextMap("analysis-limit", List.of()); }
}
