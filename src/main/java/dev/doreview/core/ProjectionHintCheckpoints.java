package dev.doreview.core;

import dev.doreview.core.ProjectionHints.DeletedAnchor;
import dev.doreview.core.ProjectionHints.UnresolvedPosition;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Content-addressed checkpoint compaction without changing saved provenance. */
final class ProjectionHintCheckpoints {
    private ProjectionHintCheckpoints() {
    }

    /**
     * Content-keyed cache of the facts that cold text matching cannot recover.
     * Unique unchanged rows need no checkpoint; changed identities, lost origins,
     * deletions and duplicate-row identity keep their complete provenance.
     */
    static ProjectionHints compact(ProjectionHints hints, String current) {
        if (!FileSnapshot.normalize(current).equals(current)) {
            throw new IllegalArgumentException("Checkpoint text must use normalized LF offsets");
        }
        return compact(hints, current, SnapshotIndex.prepare(current, () -> { }), () -> { });
    }

    private static ProjectionHints compact(ProjectionHints hints, String current,
            SnapshotIndex currentIndex, Runnable cancellationCheck) {
        var retained = new LinkedHashSet<EvidenceRef>();
        int processed = 0;
        for (var ref : hints.authoritativeRefs()) {
            if ((processed++ & 255) == 0) cancellationCheck.run();
            var position = hints.livePositions().get(ref);
            String baseline = hints.baselineTexts().get(ref);
            boolean fullRow = position != null && position.end() <= current.length()
                    && (position.start() == 0 || current.charAt(position.start() - 1) == '\n')
                    && (position.end() == current.length() || current.charAt(position.end() - 1) == '\n');
            boolean exactUnique = fullRow && baseline != null && position.end() - position.start() == baseline.length()
                    && current.regionMatches(position.start(), baseline, 0, baseline.length())
                    && currentIndex.count(baseline) == 1;
            if (!exactUnique || hints.deletedRefs().contains(ref)
                    || hints.unresolvedPositions().containsKey(ref)) retained.add(ref);
        }
        return retainOnly(hints, retained);
    }

    /**
     * Further removes unchanged boilerplate whose cold correspondence agrees
     * with its known position. Intended for a settled/background checkpoint;
     * the current-only overload stays cheap and conservative during typing.
     */
    static ProjectionHints compact(ProjectionHints hints, String current, ReviewHistory history) {
        return compact(hints, current, history, () -> { });
    }

    static ProjectionHints compact(ProjectionHints hints, String current,
            ReviewHistory history, Runnable cancellationCheck) {
        return compact(hints, current, history, ProjectionWorkBudget.defaults(), cancellationCheck);
    }

    /** May share a projection's remaining quadratic allowance; exhaustion only keeps more provenance. */
    static ProjectionHints compact(ProjectionHints hints, String current, ReviewHistory history,
            ProjectionWorkBudget workBudget, Runnable cancellationCheck) {
        Objects.requireNonNull(workBudget, "workBudget");
        if (!FileSnapshot.normalize(current).equals(current)) {
            throw new IllegalArgumentException("Checkpoint text must use normalized LF offsets");
        }
        return compact(hints, history, new ReviewProjectionWorkspace(current, workBudget, cancellationCheck));
    }

    /** Uses the same current preparation and mapping allowance as the preceding projection. */
    static ProjectionHints compact(ProjectionHints hints, ReviewHistory history, ReviewProjectionWorkspace workspace) {
        workspace.checkCanceled();
        var cancellationCheck = workspace.cancellationCheck();
        String current = workspace.current().text();
        var currentIndex = workspace.currentIndex();
        var conservative = compact(hints, current, currentIndex, cancellationCheck);
        var currentLines = currentIndex.lines();
        var byId = ReviewHistoryIndex.of(history, cancellationCheck).byId();
        var retained = new LinkedHashSet<>(conservative.authoritativeRefs());
        String currentId = workspace.current().id();
        int processed = 0;
        for (var ref : conservative.authoritativeRefs()) {
            if ((processed++ & 255) == 0) cancellationCheck.run();
            var position = conservative.livePositions().get(ref);
            var proof = byId.get(ref.evidenceId());
            if (position == null || proof == null || conservative.deletedRefs().contains(ref)
                    || conservative.unresolvedPositions().containsKey(ref)) continue;
            var baseline = history.snapshots().get(proof.snapshotId());
            if (baseline == null || ref.baselineEnd() > baseline.text().length()) continue;
            var baselineIndex = SnapshotIndex.of(baseline, cancellationCheck);
            var lines = baselineIndex.lines();
            int line = lines.lineAt(ref.baselineStart());
            if (line < 0 || ref.baselineStart() != lines.starts()[line] || ref.baselineEnd() != lines.end(line)) continue;
            String source = lines.text().get(line);
            if (position.end() > current.length() || position.end() - position.start() != source.length()
                    || !current.regionMatches(position.start(), source, 0, source.length())) continue;
            if (baseline.id().equals(currentId)) {
                retained.remove(ref); // Whole-snapshot restoration already fixes original row positions.
                continue;
            }
            if (currentIndex.count(source) > baselineIndex.count(source)) continue;
            var mapping = workspace.mapping(baseline);
            int target = mapping.currentLine(line);
            if (target >= 0 && position.start() == currentLines.starts()[target] && position.end() == currentLines.end(target)) {
                retained.remove(ref);
            }
        }
        return retainOnly(conservative, retained);
    }

    private static ProjectionHints retainOnly(ProjectionHints hints, Set<EvidenceRef> retained) {
        var positions = new LinkedHashMap<EvidenceRef, ReviewedRange>();
        var deleted = new LinkedHashSet<EvidenceRef>();
        var anchors = new HashMap<EvidenceRef, DeletedAnchor>();
        var text = new HashMap<EvidenceRef, String>();
        var unresolved = new HashMap<EvidenceRef, UnresolvedPosition>();
        for (var ref : retained) {
            if (hints.livePositions().containsKey(ref)) positions.put(ref, hints.livePositions().get(ref));
            if (hints.deletedRefs().contains(ref)) deleted.add(ref);
            if (hints.deletedAnchors().containsKey(ref)) anchors.put(ref, hints.deletedAnchors().get(ref));
            if (hints.baselineTexts().containsKey(ref)) text.put(ref, hints.baselineTexts().get(ref));
            if (hints.unresolvedPositions().containsKey(ref)) unresolved.put(ref, hints.unresolvedPositions().get(ref));
        }
        return new ProjectionHints(positions, retained, deleted, anchors, text, unresolved);
    }
}
