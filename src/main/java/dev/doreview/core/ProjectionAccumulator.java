package dev.doreview.core;

import dev.doreview.core.ReviewHistoryEngine.ProofRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Operation-local accumulation and finalization of immutable projection rows and diagnostics. */
final class ProjectionAccumulator {
    private final LineTable currentLines;
    private final List<RowBuilder> rowBuilders = new ArrayList<>();
    private final Map<DeletionKey, LinkedHashSet<EvidenceRef>> deletions = new LinkedHashMap<>();
    private final Map<Object, ChangeGroup> changeGroups = new LinkedHashMap<>();

    ProjectionAccumulator(LineTable currentLines) {
        this.currentLines = currentLines;
        for (int i = 0; i < currentLines.text().size(); i++) rowBuilders.add(new RowBuilder());
    }

    void addRow(int line, FileProjection.Status status, FileProjection.Reason reason, ProofRow proof) {
        rowBuilders.get(line).add(status, reason, proof);
    }

    void addLocalChange(ReviewedRange range, FileProjection.Reason reason, List<ReviewedRange> excludedRows,
            SnapshotReconciler.Change change, FileProjection.Status status, ProofRow proof) {
        var key = new LocalChangeKey(range, reason, excludedRows);
        changeGroups.computeIfAbsent(key, ignored -> new ChangeGroup(change, status, reason, excludedRows)).add(proof);
    }

    void addChange(String snapshotId, SnapshotReconciler.Change change,
            FileProjection.Status status, FileProjection.Reason reason, ProofRow proof) {
        var key = new ChangeKey(snapshotId, change);
        changeGroups.computeIfAbsent(key, ignored -> new ChangeGroup(change, status, reason)).add(proof);
    }

    FileProjection finish(String snapshotId, long historyRevision, Runnable cancellationCheck) {
        for (var group : changeGroups.values()) {
            var possible = new FileProjection.PossibleEvidence(group.reason, new ArrayList<>(group.refs));
            for (int line = group.change.currentStartLine(); line < group.change.currentEndLine(); line++) {
                if ((line & 255) == 0) cancellationCheck.run();
                if (!covered(group.excludedRows, currentLines.starts()[line], currentLines.end(line))) {
                    rowBuilders.get(line).addGroup(group.status, group.reason, group.sequence, possible);
                }
            }
        }
        var rows = new ArrayList<FileProjection.Row>();
        var reviewed = new ArrayList<ReviewedRange>();
        var yellow = new ArrayList<ReviewedRange>();
        var changed = new ArrayList<ReviewedRange>();
        for (int i = 0; i < rowBuilders.size(); i++) {
            if ((i & 255) == 0) cancellationCheck.run();
            var builder = rowBuilders.get(i);
            var range = currentLines.range(i);
            if (currentLines.text().get(i).isBlank()) {
                rows.add(new FileProjection.Row(i, range.start(), range.end(), FileProjection.Status.UNREVIEWED,
                        FileProjection.Reason.UNREVIEWED, List.of()));
                continue;
            }
            rows.add(new FileProjection.Row(i, range.start(), range.end(), builder.status,
                    builder.reason, builder.evidence()));
            switch (builder.status) {
                case REVIEWED -> reviewed.add(range);
                case NEEDS_REVIEW -> yellow.add(range);
                case CHANGED -> changed.add(range);
                default -> { }
            }
        }
        var deleted = deletions.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<DeletionKey, LinkedHashSet<EvidenceRef>> entry) -> entry.getKey().offset())
                        .thenComparingInt(entry -> entry.getKey().ordinal()))
                .map(entry -> new FileProjection.DeletedRow(entry.getKey().offset(), entry.getKey().ordinal(),
                        new ArrayList<>(entry.getValue()))).toList();
        return new FileProjection(snapshotId, historyRevision, rows, ReviewRanges.normalized(reviewed),
                ReviewRanges.normalized(yellow), ReviewRanges.normalized(changed), deleted);
    }

    void addPossibleDiagnostic(int line, FileProjection.Status status,
            FileProjection.Reason reason, ProofRow proof) {
        rowBuilders.get(line).addGroup(status, reason, proof.proof().sequence(),
                new FileProjection.PossibleEvidence(reason, List.of(proof.ref())));
    }

    void contribute(ProofRow proof,
            int currentLine, ContextMap baselineContext, ContextMap currentContext) {
        if (!proof.lines().text().get(proof.line()).equals(currentLines.text().get(currentLine))) {
            rowBuilders.get(currentLine).add(FileProjection.Status.CHANGED, FileProjection.Reason.TEXT_CHANGED, proof);
            return;
        }
        String oldFingerprint = baselineContext.fingerprint(proof.line());
        String newFingerprint = currentContext.fingerprint(currentLine);
        if (!baselineContext.provider().equals(currentContext.provider())
                || oldFingerprint == null || newFingerprint == null || !proof.complete()) {
            rowBuilders.get(currentLine).add(FileProjection.Status.NEEDS_REVIEW, FileProjection.Reason.UNKNOWN_CONTEXT, proof);
        } else if (!oldFingerprint.equals(newFingerprint)) {
            rowBuilders.get(currentLine).add(FileProjection.Status.NEEDS_REVIEW, FileProjection.Reason.CONTEXT_CHANGED, proof);
        } else {
            rowBuilders.get(currentLine).add(FileProjection.Status.REVIEWED, FileProjection.Reason.EXACT, proof);
        }
    }

    void addDeletion(int anchor, int ordinal, EvidenceRef ref) {
        deletions.computeIfAbsent(new DeletionKey(anchor, ordinal), ignored -> new LinkedHashSet<>()).add(ref);
    }

    private static boolean covered(List<ReviewedRange> ranges, int start, int end) {
        int low = 0;
        int high = ranges.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ranges.get(middle).end() <= start) low = middle + 1;
            else high = middle;
        }
        return low < ranges.size() && ranges.get(low).start() <= start && ranges.get(low).end() >= end;
    }

    private record DeletionKey(int offset, int ordinal) { }
    private record ChangeKey(String snapshotId, SnapshotReconciler.Change change) { }
    private record LocalChangeKey(ReviewedRange range, FileProjection.Reason reason, List<ReviewedRange> excludedRows) { }

    private static final class ChangeGroup {
        private final SnapshotReconciler.Change change;
        private final FileProjection.Status status;
        private final FileProjection.Reason reason;
        private final LinkedHashSet<EvidenceRef> refs = new LinkedHashSet<>();
        private final List<ReviewedRange> excludedRows;
        private long sequence;

        private ChangeGroup(SnapshotReconciler.Change change, FileProjection.Status status, FileProjection.Reason reason) {
            this(change, status, reason, List.of());
        }

        private ChangeGroup(SnapshotReconciler.Change change, FileProjection.Status status,
                FileProjection.Reason reason, List<ReviewedRange> excludedRows) {
            this.change = change;
            this.status = status;
            this.reason = reason;
            this.excludedRows = excludedRows;
        }

        private void add(ProofRow proof) {
            refs.add(proof.ref());
            sequence = Math.max(sequence, proof.proof().sequence());
        }
    }

    private static final class RowBuilder {
        private FileProjection.Status status = FileProjection.Status.UNREVIEWED;
        private FileProjection.Reason reason = FileProjection.Reason.UNREVIEWED;
        private long sequence = -1;
        private final LinkedHashSet<EvidenceRef> refs = new LinkedHashSet<>();
        private final List<FileProjection.PossibleEvidence> groups = new ArrayList<>();

        private void add(FileProjection.Status candidate, FileProjection.Reason why, ProofRow proof) {
            refs.add(proof.ref());
            select(candidate, why, proof.proof().sequence());
        }

        private void addGroup(FileProjection.Status candidate, FileProjection.Reason why, long sequence,
                FileProjection.PossibleEvidence possible) {
            groups.add(possible);
            select(candidate, why, sequence);
        }

        private FileProjection.RowEvidence evidence() {
            return new FileProjection.RowEvidence(new ArrayList<>(refs), groups);
        }

        private void select(FileProjection.Status candidate, FileProjection.Reason why, long candidateSequence) {
            if (rank(candidate) > rank(status) || rank(candidate) == rank(status) && candidateSequence >= sequence) {
                status = candidate;
                reason = why;
                sequence = candidateSequence;
            }
        }

        private static int rank(FileProjection.Status status) {
            return switch (status) {
                case REVIEWED -> 3;
                case NEEDS_REVIEW -> 2;
                case CHANGED -> 1;
                case UNREVIEWED -> 0;
            };
        }
    }
}
