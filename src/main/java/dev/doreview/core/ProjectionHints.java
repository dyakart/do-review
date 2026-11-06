package dev.doreview.core;

import java.util.Map;
import java.util.Set;

/** Known local row identity wins over heuristic rematching, including duplicates. */
public record ProjectionHints(Map<EvidenceRef, ReviewedRange> livePositions,
                              Set<EvidenceRef> authoritativeRefs, Set<EvidenceRef> deletedRefs,
                              Map<EvidenceRef, DeletedAnchor> deletedAnchors,
                              Map<EvidenceRef, String> baselineTexts,
                              Map<EvidenceRef, UnresolvedPosition> unresolvedPositions) {
    public ProjectionHints {
        livePositions = Map.copyOf(livePositions);
        authoritativeRefs = Set.copyOf(authoritativeRefs);
        deletedRefs = Set.copyOf(deletedRefs);
        deletedAnchors = Map.copyOf(deletedAnchors);
        baselineTexts = Map.copyOf(baselineTexts);
        unresolvedPositions = Map.copyOf(unresolvedPositions);
        if (!authoritativeRefs.containsAll(livePositions.keySet()) || !authoritativeRefs.containsAll(deletedRefs)
                || !deletedRefs.containsAll(deletedAnchors.keySet())
                || !authoritativeRefs.containsAll(unresolvedPositions.keySet())) {
            throw new IllegalArgumentException("All known positions/deletions must be authoritative");
        }
    }

    public ProjectionHints(Map<EvidenceRef, ReviewedRange> positions, Set<EvidenceRef> authoritative) {
        this(positions, authoritative, Set.of(), Map.of(), Map.of(), Map.of());
    }

    public ProjectionHints(Map<EvidenceRef, ReviewedRange> positions, Set<EvidenceRef> authoritative,
            Set<EvidenceRef> deleted) {
        this(positions, authoritative, deleted, Map.of(), Map.of(), Map.of());
    }

    public static ProjectionHints empty() {
        return new ProjectionHints(Map.of(), Set.of(), Set.of());
    }

    /** Compacts facts that cold text matching cannot recover, retaining complete provenance. */
    public ProjectionHints compactForCheckpoint(String current) {
        return ProjectionHintCheckpoints.compact(this, current);
    }

    /** Settled checkpoints may also omit boilerplate with the same cold correspondence. */
    public ProjectionHints compactForCheckpoint(String current, ReviewHistory history) {
        return ProjectionHintCheckpoints.compact(this, current, history);
    }

    public ProjectionHints compactForCheckpoint(String current, ReviewHistory history, Runnable cancellationCheck) {
        return ProjectionHintCheckpoints.compact(this, current, history, cancellationCheck);
    }

    /** May share a projection's remaining quadratic allowance; exhaustion only keeps more provenance. */
    public ProjectionHints compactForCheckpoint(String current, ReviewHistory history,
            ProjectionWorkBudget workBudget, Runnable cancellationCheck) {
        return ProjectionHintCheckpoints.compact(this, current, history, workBudget, cancellationCheck);
    }

    /** Uses the same current preparation and mapping allowance as the preceding projection. */
    public ProjectionHints compactForCheckpoint(ReviewHistory history, ReviewProjectionWorkspace workspace) {
        return ProjectionHintCheckpoints.compact(this, history, workspace);
    }

    /** Only actually resolved refs become authoritative; filtered fast projections are safe. */
    public static ProjectionHints fromProjection(ReviewHistory history, FileProjection projection) {
        return ProjectionHintOrigins.fromProjection(history, projection);
    }

    /** Fresh resolved positions win; unlocated authoritative identities and live tombstones survive. */
    public ProjectionHints mergeResolved(ProjectionHints fresh) {
        return ProjectionHintOrigins.mergeResolved(this, fresh);
    }

    /** Unknown old proofs cannot attach to edited-row copies while projection is pending. */
    public ProjectionHints guardUnresolvedEdit(ReviewHistory history, String previous, String current,
            int offset, int oldLength, int newLength) {
        return ProjectionHintEdits.guardUnresolvedEdit(this, history, previous, current, offset, oldLength, newLength);
    }

    /** Retains exact rows plus a changed 1:1 row locator; split/join/copy cannot inherit a proof. */
    public ProjectionHints applyEdit(String previous, String current, int offset, int oldLength, int newLength) {
        return ProjectionHintEdits.applyEdit(this, previous, current, offset, oldLength, newLength);
    }

    public record UnresolvedPosition(ReviewedRange range, FileProjection.Reason reason,
                                     String originalText, int relativeStart, int relativeEnd,
                                     java.util.List<ReviewedRange> excludedRows) {
        public UnresolvedPosition {
            excludedRows = ReviewRanges.normalized(excludedRows);
        }

        public UnresolvedPosition(ReviewedRange range, FileProjection.Reason reason,
                String originalText, int relativeStart, int relativeEnd) {
            this(range, reason, originalText, relativeStart, relativeEnd, java.util.List.of());
        }

        public UnresolvedPosition(ReviewedRange range, FileProjection.Reason reason) {
            this(range, reason, null, 0, 0, java.util.List.of());
        }
    }

    public record DeletedAnchor(int offset, int relativeStart, int ordinal, String baselineText, String removedText) {
        public DeletedAnchor {
            if (offset < 0 || relativeStart < 0 || ordinal < 0 || baselineText == null || baselineText.isEmpty()
                    || removedText == null || removedText.isEmpty()) {
                throw new IllegalArgumentException("Invalid deleted row anchor");
            }
        }
    }
}
