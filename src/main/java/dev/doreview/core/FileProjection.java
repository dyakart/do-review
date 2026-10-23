package dev.doreview.core;

import java.util.List;
import java.util.Objects;

/** Disposable current view; all evidence and revocations remain in ReviewHistory. */
public record FileProjection(String currentSnapshotId, long historyRevision, List<Row> rows,
                             List<ReviewedRange> reviewedRanges, List<ReviewedRange> yellowRanges,
                             List<ReviewedRange> changedRanges, List<DeletedRow> deletions) {
    public FileProjection {
        rows = List.copyOf(rows);
        reviewedRanges = List.copyOf(reviewedRanges);
        yellowRanges = List.copyOf(yellowRanges);
        changedRanges = List.copyOf(changedRanges);
        deletions = List.copyOf(deletions);
    }

    public enum Status { REVIEWED, NEEDS_REVIEW, CHANGED, UNREVIEWED }

    public enum Reason {
        EXACT, TEXT_CHANGED, CONTEXT_CHANGED, UNKNOWN_CONTEXT, AMBIGUOUS, DIFF_LIMIT, UNREVIEWED
    }

    /** A value preserves definite correspondence separately from possible hunk origins. */
    public record Row(int line, int start, int end, Status status, Reason reason,
                      RowEvidence evidence) {
        public Row {
            Objects.requireNonNull(evidence, "evidence");
        }

        /** Convenience for rows having only definite correspondence, including render-only rows. */
        public Row(int line, int start, int end, Status status, Reason reason, List<EvidenceRef> directRefs) {
            this(line, start, end, status, reason, new RowEvidence(directRefs, List.of()));
        }

        /** All possible origins for inspection; use revocableRefs for a manual row clear. */
        public List<EvidenceRef> evidenceRefs() {
            return evidence.allRefs();
        }

        /** Definite row correspondence only; possible hunk origins must not erase unrelated history. */
        public List<EvidenceRef> revocableRefs() {
            return evidence.direct();
        }
    }

    /** Immutable provenance; shared possible groups avoid rows * historical proofs storage. */
    public record RowEvidence(List<EvidenceRef> direct, List<PossibleEvidence> possibleGroups) {
        public RowEvidence {
            direct = List.copyOf(direct);
            possibleGroups = List.copyOf(possibleGroups);
        }

        public List<EvidenceRef> allRefs() {
            return possibleGroups.isEmpty() ? direct : new EvidenceList(direct, possibleGroups);
        }
    }

    /** Possible origins of an unresolved hunk; these are never precise row-clear targets. */
    public record PossibleEvidence(Reason reason, List<EvidenceRef> refs) {
        public PossibleEvidence {
            Objects.requireNonNull(reason, "reason");
            refs = List.copyOf(refs);
        }
    }

    /** Groups historical versions/proofs of a deleted row at one current diff anchor. */
    public record DeletedRow(int currentOffset, int ordinal, List<EvidenceRef> evidenceRefs) {
        public DeletedRow {
            evidenceRefs = List.copyOf(evidenceRefs);
        }
    }

    public int deletedCount() {
        return deletions.size();
    }

    public List<EvidenceRef> deletedRefs() {
        return deletions.stream().flatMap(deleted -> deleted.evidenceRefs().stream()).distinct().toList();
    }
}
