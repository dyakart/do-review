package dev.doreview.core;

import dev.doreview.core.ProjectionHints.DeletedAnchor;
import dev.doreview.core.ProjectionHints.UnresolvedPosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Reconstructs and merges immutable row provenance from completed projections. */
final class ProjectionHintOrigins {
    private ProjectionHintOrigins() {
    }

    /** Only actually resolved refs become authoritative; filtered fast projections are safe. */
    static ProjectionHints fromProjection(ReviewHistory history, FileProjection projection) {
        var positions = new LinkedHashMap<EvidenceRef, ReviewedRange>();
        var authoritative = new LinkedHashSet<EvidenceRef>();
        var duplicate = new HashSet<EvidenceRef>();
        var byId = ReviewHistoryIndex.of(history, () -> { }).byId();
        var baselineTexts = new HashMap<EvidenceRef, String>();
        var canonicalGroups = new IdentityHashMap<FileProjection.PossibleEvidence, FileProjection.PossibleEvidence>();
        var groupsByValue = new HashMap<FileProjection.PossibleEvidence, FileProjection.PossibleEvidence>();
        var seenGroups = Collections.newSetFromMap(new IdentityHashMap<FileProjection.PossibleEvidence, Boolean>());
        var groupRanges = new IdentityHashMap<FileProjection.PossibleEvidence, ReviewedRange>();
        var groupCoverage = new IdentityHashMap<FileProjection.PossibleEvidence, java.util.List<ReviewedRange>>();
        var unresolved = new HashMap<EvidenceRef, UnresolvedPosition>();
        for (var row : projection.rows()) {
            for (var candidate : row.evidence().possibleGroups()) {
                // Sharing is an optimization only: a copied/decoded equal value has identical meaning.
                var group = canonicalGroups.computeIfAbsent(candidate,
                        value -> groupsByValue.computeIfAbsent(value, ignored -> value));
                var bounds = groupRanges.get(group);
                groupRanges.put(group, new ReviewedRange(bounds == null ? row.start() : Math.min(bounds.start(), row.start()),
                        bounds == null ? row.end() : Math.max(bounds.end(), row.end())));
                groupCoverage.computeIfAbsent(group, ignored -> new ArrayList<>()).add(new ReviewedRange(row.start(), row.end()));
                if (seenGroups.add(group)) {
                    authoritative.addAll(group.refs());
                    captureTexts(group.refs(), history, byId, baselineTexts);
                }
            }
            for (var ref : row.evidence().direct()) {
                authoritative.add(ref);
                var proof = byId.get(ref.evidenceId());
                if (proof != null) baselineTexts.put(ref, history.snapshots().get(proof.snapshotId()).text()
                        .substring(ref.baselineStart(), ref.baselineEnd()));
                if (positions.putIfAbsent(ref, new ReviewedRange(row.start(), row.end())) != null) duplicate.add(ref);
            }
        }
        groupRanges.forEach((group, bounds) -> {
            java.util.List<ReviewedRange> holes = java.util.List.of(bounds);
            for (var covered : ReviewRanges.normalized(groupCoverage.get(group))) {
                holes = ReviewRanges.clear(holes, covered.start(), covered.end());
            }
            var position = new UnresolvedPosition(bounds, group.reason(), null, 0, 0, holes);
            for (var ref : group.refs()) unresolved.put(ref, position);
        });
        duplicate.forEach(positions::remove);
        authoritative.addAll(projection.deletedRefs());
        var anchors = new HashMap<EvidenceRef, DeletedAnchor>();
        var tables = new HashMap<String, LineTable>();
        for (var deletion : projection.deletions()) {
            for (var ref : deletion.evidenceRefs()) {
                var proof = byId.get(ref.evidenceId());
                if (proof == null) continue;
                var snapshot = history.snapshots().get(proof.snapshotId());
                String text = snapshot.text();
                var lines = tables.computeIfAbsent(proof.snapshotId(), ignored -> SnapshotIndex.of(snapshot, () -> { }).lines());
                int row = lines.lineAt(ref.baselineStart());
                int gapStart = Math.max(0, row - deletion.ordinal());
                String source = text.substring(ref.baselineStart(), ref.baselineEnd());
                baselineTexts.put(ref, source);
                anchors.put(ref, new DeletedAnchor(deletion.currentOffset(),
                        ref.baselineStart() - lines.starts()[gapStart], deletion.ordinal(), source, source));
            }
        }
        projection.deletedRefs().forEach(unresolved::remove);
        return new ProjectionHints(positions, authoritative, Set.copyOf(projection.deletedRefs()), anchors, baselineTexts, unresolved);
    }

    private static void captureTexts(java.util.List<EvidenceRef> refs, ReviewHistory history,
            Map<String, ReviewEvidence> byId, Map<EvidenceRef, String> texts) {
        for (var ref : refs) {
            var proof = byId.get(ref.evidenceId());
            if (proof != null) texts.putIfAbsent(ref, history.snapshots().get(proof.snapshotId()).text()
                    .substring(ref.baselineStart(), ref.baselineEnd()));
        }
    }

    /** Fresh resolved positions win; unlocated authoritative identities and live tombstones survive. */
    static ProjectionHints mergeResolved(ProjectionHints hints, ProjectionHints fresh) {
        var authoritative = new LinkedHashSet<>(hints.authoritativeRefs());
        authoritative.addAll(fresh.authoritativeRefs());
        var positions = new LinkedHashMap<>(hints.livePositions());
        fresh.authoritativeRefs().forEach(positions::remove);
        positions.putAll(fresh.livePositions());
        var deleted = new LinkedHashSet<>(hints.deletedRefs());
        deleted.addAll(fresh.deletedRefs());
        var anchors = new HashMap<>(fresh.deletedAnchors());
        anchors.putAll(hints.deletedAnchors()); // The known local deletion anchor is stronger than a diff guess.
        var unresolved = new HashMap<>(hints.unresolvedPositions());
        fresh.authoritativeRefs().forEach(unresolved::remove);
        unresolved.putAll(fresh.unresolvedPositions());
        for (var entry : hints.unresolvedPositions().entrySet()) {
            var next = fresh.unresolvedPositions().get(entry.getKey());
            if (entry.getValue().originalText() != null && next != null
                    && entry.getValue().range().equals(next.range())) {
                unresolved.put(entry.getKey(), entry.getValue());
            }
        }
        for (var ref : fresh.livePositions().keySet()) {
            deleted.remove(ref);
            anchors.remove(ref);
            unresolved.remove(ref);
        }
        var text = new HashMap<>(hints.baselineTexts());
        text.putAll(fresh.baselineTexts());
        return new ProjectionHints(positions, authoritative, deleted, anchors, text, unresolved);
    }
}
