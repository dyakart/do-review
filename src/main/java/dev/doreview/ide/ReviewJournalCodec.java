package dev.doreview.ide;

import dev.doreview.core.FileSnapshot;
import dev.doreview.core.ReviewEvidence;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewedRange;
import dev.doreview.core.Revocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The XML bean format is an adapter; domain history remains immutable and validates its invariants. */
final class ReviewJournalCodec {
    private ReviewJournalCodec() { }

    static ReviewHistory read(ReviewHistoryStore.FileState file) {
        Map<String, FileSnapshot> snapshots = new LinkedHashMap<>();
        file.snapshots.forEach((id, text) -> snapshots.put(id, new FileSnapshot(id, text)));
        List<ReviewEvidence> evidence = new ArrayList<>();
        for (var stored : file.evidence) evidence.add(new ReviewEvidence(stored.id, stored.snapshotId,
                ranges(stored.ranges), stored.sequence, stored.createdAt));
        List<Revocation> revocations = new ArrayList<>();
        for (var stored : file.revocations) revocations.add(new Revocation(stored.evidenceId,
                ranges(stored.ranges), stored.sequence));
        return new ReviewHistory(snapshots, evidence, revocations, file.revision);
    }

    static void write(ReviewHistoryStore.FileState file, ReviewHistory history) {
        file.snapshots = new TreeMap<>();
        history.snapshots().forEach((id, snapshot) -> file.snapshots.put(id, snapshot.text()));
        file.evidence = new ArrayList<>();
        for (var record : history.evidence()) {
            var stored = new ReviewHistoryStore.EvidenceState();
            stored.id = record.id(); stored.snapshotId = record.snapshotId(); stored.ranges = rangeStates(record.ranges());
            stored.sequence = record.sequence(); stored.createdAt = record.createdAt(); file.evidence.add(stored);
        }
        file.revocations = new ArrayList<>();
        for (var record : history.revocations()) {
            var stored = new ReviewHistoryStore.RevocationState();
            stored.evidenceId = record.evidenceId(); stored.ranges = rangeStates(record.ranges()); stored.sequence = record.sequence();
            file.revocations.add(stored);
        }
        file.revision = history.revision();
    }

    private static List<ReviewedRange> ranges(List<ReviewHistoryStore.RangeState> states) {
        return states.stream().map(range -> new ReviewedRange(range.start, range.end)).toList();
    }
    private static List<ReviewHistoryStore.RangeState> rangeStates(List<ReviewedRange> ranges) {
        return new ArrayList<>(ranges.stream().map(range -> new ReviewHistoryStore.RangeState(range.start(), range.end())).toList());
    }
}
