package dev.doreview.ide;

import dev.doreview.core.ReviewHistory;
import dev.doreview.ide.SharedHistoryRepository.Author;
import dev.doreview.ide.SharedHistoryRepository.Event;
import dev.doreview.ide.SharedHistoryRepository.LoadedFile;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static dev.doreview.ide.SharedHistoryCodec.JSON;
import static dev.doreview.ide.SharedHistoryCodec.canonicalEvidence;
import static dev.doreview.ide.SharedHistoryCodec.canonicalRevocation;
import static dev.doreview.ide.SharedHistoryCodec.copy;

/** Deterministic event-DAG projection; storage supplies snapshot text without exposing its connection. */
final class SharedHistoryMaterializer {
    private SharedHistoryMaterializer() { }

    static LoadedFile materialize(String fileId, String path, List<Event> events, Map<String, String> pendingSnapshots,
                                  Function<String, String> snapshots) throws SQLException {
        var result = new ReviewHistoryStore.FileState(); result.path = path;
        var graph = new HashMap<String, Event>(); events.forEach(event -> graph.put(event.id, event));
        var aliases = new TreeSet<String>();
        var authors = new TreeMap<String, Author>();
        var evidence = new LinkedHashMap<String, ReviewHistoryStore.EvidenceState>();
        var origins = new HashMap<String, Set<String>>();
        var revocations = new LinkedHashMap<String, ReviewHistoryStore.RevocationState>();
        var candidates = new TreeMap<String, List<CheckpointEvent>>();
        long revision = 0;
        for (Event event : events) {
            aliases.add(event.path); aliases.addAll(event.aliases); revision++;
            for (var proof : event.evidence) {
                var old = evidence.get(proof.id);
                if (old != null && !canonicalEvidence(old).equals(canonicalEvidence(proof))) throw new IllegalStateException("Conflicting review evidence " + proof.id);
                origins.computeIfAbsent(proof.id, ignored -> new HashSet<>()).add(event.id);
                if (old == null) {
                    var value = copy(proof, ReviewHistoryStore.EvidenceState.class); value.sequence = revision;
                    evidence.put(value.id, value); authors.put(value.id, new Author(event.authorName, event.authorEmail));
                    if (!result.snapshots.containsKey(value.snapshotId)) {
                        String text = pendingSnapshots.containsKey(value.snapshotId) ? pendingSnapshots.get(value.snapshotId) : snapshots.apply(value.snapshotId);
                        if (text == null) throw new IllegalStateException("Missing review snapshot");
                        result.snapshots.put(value.snapshotId, text);
                    }
                }
            }
            for (var revoked : event.revocations) {
                if (!evidence.containsKey(revoked.evidenceId)) throw new IllegalStateException("Revocation without a causal review");
                if (origins.get(revoked.evidenceId).stream().noneMatch(origin -> event.id.equals(origin) || ancestor(origin, event.id, graph)))
                    throw new IllegalStateException("Revocation does not observe its review evidence");
                var value = copy(revoked, ReviewHistoryStore.RevocationState.class); value.sequence = revision;
                revocations.putIfAbsent(canonicalRevocation(value), value);
            }
            for (var checkpoint : event.checkpoints.entrySet()) {
                var list = candidates.computeIfAbsent(checkpoint.getKey(), ignored -> new ArrayList<>());
                list.removeIf(old -> ancestor(old.event.id, event.id, graph));
                list.add(new CheckpointEvent(event, checkpoint.getValue()));
            }
        }
        result.evidence.addAll(evidence.values()); result.revocations.addAll(revocations.values()); result.revision = revision;
        ReviewHistory history = ReviewJournalCodec.read(result);
        for (var entry : candidates.entrySet()) {
            var values = entry.getValue();
            var unique = new LinkedHashMap<String, ReviewHistoryStore.CheckpointState>();
            for (var candidate : values) {
                ProjectionCheckpointCodec.validate(ProjectionCheckpointCodec.index(history), candidate.checkpoint);
                unique.put(JSON.toJson(candidate.checkpoint), candidate.checkpoint);
            }
            if (unique.size() == 1) result.checkpoints.put(entry.getKey(), copy(unique.values().iterator().next(), ReviewHistoryStore.CheckpointState.class));
            else {
                // No last-writer-wins for concurrent provenance. Keep all evidence restricted.
                int length = unique.values().iterator().next().payload.textLength;
                if (unique.values().stream().anyMatch(checkpoint -> checkpoint.payload.textLength != length)) throw new IllegalStateException("Conflicting checkpoint snapshot length");
                var conservative = new ReviewHistoryStore.CheckpointState();
                conservative.payload.version = 2; conservative.payload.textHash = entry.getKey(); conservative.payload.textLength = length;
                var refs = new ArrayList<>(ProjectionCheckpointCodec.index(history).rows().keySet());
                refs.sort(Comparator.comparing(dev.doreview.core.EvidenceRef::evidenceId).thenComparingInt(dev.doreview.core.EvidenceRef::baselineStart));
                for (var ref : refs) {
                    var stored = new ReviewHistoryStore.CheckpointRefState(); stored.evidenceId = ref.evidenceId();
                    stored.start = ref.baselineStart(); stored.end = ref.baselineEnd(); stored.authoritative = true;
                    conservative.payload.refs.add(stored);
                }
                conservative.checksum = ProjectionCheckpointCodec.checksum(conservative.payload);
                ProjectionCheckpointCodec.validate(ProjectionCheckpointCodec.index(history), conservative);
                result.checkpoints.put(entry.getKey(), conservative);
            }
        }
        var frontier = new TreeSet<String>(); events.forEach(event -> frontier.add(event.id));
        events.forEach(event -> frontier.removeAll(event.parents));
        return new LoadedFile(fileId, result, Set.copyOf(aliases), Map.copyOf(authors), Set.copyOf(frontier));
    }

    private record CheckpointEvent(Event event, ReviewHistoryStore.CheckpointState checkpoint) { }

    private static boolean ancestor(String candidate, String id, Map<String, Event> byId) {
        var queue = new ArrayList<String>(); queue.add(id); var seen = new HashSet<String>();
        for (int i = 0; i < queue.size(); i++) {
            String current = queue.get(i); if (!seen.add(current)) continue;
            Event event = byId.get(current); if (event == null) continue;
            if (event.parents.contains(candidate)) return true;
            queue.addAll(event.parents);
        }
        return false;
    }

    static List<Event> topological(Map<String, Event> events) {
        for (Event event : events.values()) for (String parent : event.parents) {
            if (!events.containsKey(parent)) throw new IllegalStateException("Missing causal dependency " + parent);
            if (!events.get(parent).fileId.equals(event.fileId)) throw new IllegalStateException("Cross-file causal dependency");
        }
        return topologicalSubset(events);
    }

    static List<Event> topologicalSubset(Map<String, Event> events) {
        var degrees = new HashMap<String, Integer>(); var children = new HashMap<String, List<String>>();
        for (Event event : events.values()) {
            int degree = 0;
            for (String parent : event.parents) if (events.containsKey(parent)) { degree++; children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(event.id); }
            degrees.put(event.id, degree);
        }
        var ready = new TreeSet<String>(); degrees.forEach((id, count) -> { if (count == 0) ready.add(id); });
        var ordered = new ArrayList<Event>();
        while (!ready.isEmpty()) {
            String id = ready.pollFirst(); ordered.add(events.get(id));
            for (String child : children.getOrDefault(id, List.of())) if (degrees.compute(child, (ignored, n) -> n - 1) == 0) ready.add(child);
        }
        if (ordered.size() != events.size()) throw new IllegalStateException("Cyclic review history dependencies");
        return ordered;
    }

}
