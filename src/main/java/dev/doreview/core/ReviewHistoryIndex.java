package dev.doreview.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded derived indexes. Equal revisions or equal record values never alias different histories. */
final class ReviewHistoryIndex {
    private static final BoundedIdentityCache<ReviewHistory, ReviewHistoryIndex> INDEXES =
            new BoundedIdentityCache<>(32, 16_000_000, value -> value.weight);
    private static final BoundedIdentityCache<ReviewHistory, ActiveRows> ROWS =
            new BoundedIdentityCache<>(32, 16_000_000, ActiveRows::weight);

    private final Map<String, ReviewEvidence> byId;
    private final List<FileSnapshot> snapshotsByRecency;
    private final long weight;

    private ReviewHistoryIndex(Map<String, ReviewEvidence> byId, List<FileSnapshot> snapshotsByRecency, long weight) {
        this.byId = Map.copyOf(byId);
        this.snapshotsByRecency = List.copyOf(snapshotsByRecency);
        this.weight = weight;
    }

    static ReviewHistoryIndex of(ReviewHistory history, Runnable cancellationCheck) {
        return INDEXES.get(history, cancellationCheck, value -> prepare(value, cancellationCheck));
    }

    private static ReviewHistoryIndex prepare(ReviewHistory history, Runnable cancellationCheck) {
        var byId = new HashMap<String, ReviewEvidence>();
        var sequences = new HashMap<String, Long>();
        long weight = 0;
        for (var snapshot : history.snapshots().values()) {
            cancellationCheck.run();
            weight += 2L * snapshot.text().length() + 64;
        }
        for (var proof : history.evidence()) {
            cancellationCheck.run();
            byId.put(proof.id(), proof);
            sequences.merge(proof.snapshotId(), proof.sequence(), Math::max);
            weight += 96L + 32L * proof.ranges().size();
        }
        for (var item : history.revocations()) {
            cancellationCheck.run();
            weight += 64L + 32L * item.ranges().size();
        }
        // Portable provenance also stores edited, never-reviewed targets. They are not PSI baselines.
        var snapshots = new ArrayList<FileSnapshot>();
        for (var snapshot : history.snapshots().values()) {
            cancellationCheck.run();
            if (sequences.containsKey(snapshot.id())) snapshots.add(snapshot);
        }
        snapshots.sort(Comparator.<FileSnapshot>comparingLong(snapshot -> sequences.getOrDefault(snapshot.id(), 0L))
                .reversed().thenComparing(FileSnapshot::id));
        cancellationCheck.run();
        return new ReviewHistoryIndex(byId, snapshots, weight);
    }

    Map<String, ReviewEvidence> byId() { return byId; }
    List<FileSnapshot> snapshotsByRecency() { return snapshotsByRecency; }

    static List<ReviewHistoryEngine.ProofRow> proofRows(ReviewHistory history, Runnable cancellationCheck) {
        return ROWS.get(history, cancellationCheck, value -> prepareRows(value, cancellationCheck)).rows();
    }

    private static ActiveRows prepareRows(ReviewHistory history, Runnable cancellationCheck) {
        var index = of(history, cancellationCheck);
        var rows = new ArrayList<ReviewHistoryEngine.ProofRow>();
        var tables = new HashMap<String, LineTable>();
        var revokedByProof = new HashMap<String, List<ReviewedRange>>();
        for (var item : history.revocations()) {
            cancellationCheck.run();
            revokedByProof.computeIfAbsent(item.evidenceId(), ignored -> new ArrayList<>()).addAll(item.ranges());
        }
        revokedByProof.replaceAll((id, ranges) -> {
            cancellationCheck.run();
            return ReviewRanges.normalized(ranges);
        });
        long lineStorage = 0;
        for (var proof : history.evidence()) {
            cancellationCheck.run();
            var lines = tables.get(proof.snapshotId());
            if (lines == null) {
                var snapshot = history.snapshots().get(proof.snapshotId());
                lines = SnapshotIndex.of(snapshot, cancellationCheck).lines();
                tables.put(proof.snapshotId(), lines);
                lineStorage += 2L * snapshot.text().length() + 80L * lines.text().size();
            }
            var revoked = revokedByProof.getOrDefault(proof.id(), List.of());
            for (var range : proof.ranges()) {
                for (int line = lines.lineAt(range.start()); line <= lines.lineAt(range.end() - 1); line++) {
                    if ((line & 255) == 0) cancellationCheck.run();
                    if (lines.text().get(line).isBlank()) continue;
                    int start = Math.max(range.start(), lines.starts()[line]);
                    int end = Math.min(range.end(), lines.end(line));
                    if (!overlaps(revoked, start, end)) rows.add(new ReviewHistoryEngine.ProofRow(proof,
                            new EvidenceRef(proof.id(), start, end), line, lines,
                            start == lines.starts()[line] && end == lines.end(line)));
                }
            }
        }
        return new ActiveRows(List.copyOf(rows), index.weight + lineStorage + 80L * rows.size());
    }

    private static boolean overlaps(List<ReviewedRange> ranges, int start, int end) {
        int low = 0, high = ranges.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ranges.get(middle).end() <= start) low = middle + 1;
            else high = middle;
        }
        return low < ranges.size() && ranges.get(low).start() < end;
    }

    private record ActiveRows(List<ReviewHistoryEngine.ProofRow> rows, long weight) { }
}
