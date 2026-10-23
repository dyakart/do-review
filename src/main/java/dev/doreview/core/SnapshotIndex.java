package dev.doreview.core;

import java.util.HashMap;
import java.util.Map;

/** Immutable linear preparation, cached only for already validated snapshot instances. */
final class SnapshotIndex {
    private static final BoundedIdentityCache<FileSnapshot, SnapshotIndex> CACHE =
            new BoundedIdentityCache<>(64, 16_000_000, SnapshotIndex::weight);

    record Occurrence(int index, int count) { }

    private final LineTable lines;
    private final Map<String, Occurrence> occurrences;
    private final long weight;

    private SnapshotIndex(LineTable lines, Map<String, Occurrence> occurrences, long weight) {
        this.lines = lines;
        this.occurrences = Map.copyOf(occurrences);
        this.weight = weight;
    }

    static SnapshotIndex of(FileSnapshot snapshot, Runnable cancellationCheck) {
        return CACHE.get(snapshot, cancellationCheck, value -> prepare(value.text(), cancellationCheck));
    }

    static SnapshotIndex prepare(String text, Runnable cancellationCheck) {
        var lines = LineTable.from(text, cancellationCheck);
        var counts = new HashMap<String, MutableOccurrence>();
        for (int line = 0; line < lines.text().size(); line++) {
            if ((line & 255) == 0) cancellationCheck.run();
            var row = lines.text().get(line);
            var previous = counts.get(row);
            if (previous == null) counts.put(row, new MutableOccurrence(line));
            else previous.count++;
        }
        var occurrences = new HashMap<String, Occurrence>();
        counts.forEach((row, value) -> occurrences.put(row, new Occurrence(value.index, value.count)));
        return new SnapshotIndex(lines, occurrences, 2L * text.length() + 80L * lines.text().size());
    }

    LineTable lines() { return lines; }
    Occurrence occurrence(String row) { return occurrences.get(row); }
    int count(String row) {
        var occurrence = occurrences.get(row);
        return occurrence == null ? 0 : occurrence.count();
    }
    private long weight() { return weight; }

    private static final class MutableOccurrence {
        private final int index;
        private int count = 1;
        private MutableOccurrence(int index) { this.index = index; }
    }
}
