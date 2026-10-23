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
import dev.doreview.ide.context.ContextAnalysisManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captures language-specific immutable contexts, then computes the platform-independent projection. */
public final class ReviewProjectionComputer {
    public record CapturedContexts(ContextMap current, Map<String, ContextMap> baselines) {
        public CapturedContexts { baselines = Map.copyOf(baselines); }
    }
    public record Result(FileProjection projection, ProjectionHints hints, ProjectionHints checkpoint) { }
    record Limits(int historicalSnapshots, long totalCharacters, int snapshotCharacters) {
        static Limits defaults() { return new Limits(64, 10_000_000, 2_000_000); }
    }

    private final Project project;
    private final Limits limits;
    public ReviewProjectionComputer(Project project) { this(project, Limits.defaults()); }
    ReviewProjectionComputer(Project project, Limits limits) { this.project = project; this.limits = limits; }

    /** Called only by the scheduler's read phase; no PSI or adapter object escapes this method. */
    public CapturedContexts capture(ReviewHistory history, VirtualFile file, String text) {
        ApplicationManager.getApplication().assertReadAccessAllowed();
        var manager = project.getService(ContextAnalysisManager.class);
        ContextMap current = text.length() <= limits.snapshotCharacters && text.length() <= limits.totalCharacters
                ? manager.analyzeUnderRead(file, text) : limited();
        long consumed = text.length() <= limits.snapshotCharacters && text.length() <= limits.totalCharacters ? text.length() : 0;
        var sequences = new HashMap<String, Long>();
        history.evidence().forEach(proof -> sequences.merge(proof.snapshotId(), proof.sequence(), Math::max));
        var snapshots = new ArrayList<>(history.snapshots().values());
        snapshots.sort(Comparator.<dev.doreview.core.FileSnapshot>comparingLong(snapshot -> sequences.getOrDefault(snapshot.id(), 0L))
                .reversed().thenComparing(dev.doreview.core.FileSnapshot::id));
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
        return ReviewHistoryEngine.project(history, text, contexts.baselines(), contexts.current(), hints,
                ProjectionWorkBudget.defaults(), ProgressManager::checkCanceled);
    }

    public Result computeResult(ReviewHistory history, String text, ProjectionHints hints, CapturedContexts contexts) {
        requirePurePhase();
        var budget = ProjectionWorkBudget.defaults();
        var projection = ReviewHistoryEngine.project(history, text, contexts.baselines(), contexts.current(), hints,
                budget, ProgressManager::checkCanceled);
        ProgressManager.checkCanceled();
        var resolved = hints.mergeResolved(ProjectionHints.fromProjection(history, projection));
        ProgressManager.checkCanceled();
        var checkpoint = resolved.compactForCheckpoint(text, history, budget, ProgressManager::checkCanceled);
        return new Result(projection, resolved, checkpoint);
    }

    private static void requirePurePhase() {
        if (ApplicationManager.getApplication().isReadAccessAllowed()) {
            throw new IllegalStateException("Pure review computation must run outside a read action");
        }
    }
    private static ContextMap limited() { return new ContextMap("analysis-limit", List.of()); }
}
