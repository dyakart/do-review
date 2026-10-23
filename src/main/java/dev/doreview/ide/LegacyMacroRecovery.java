package dev.doreview.ide;

import dev.doreview.core.FileSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/** Reads schema 1 macro-processed data without guessing the original reviewed source. */
final class LegacyMacroRecovery {
    private LegacyMacroRecovery() { }

    static List<String> recover(ReviewHistoryStore.StoreState state, UnaryOperator<String> expand) {
        var warnings = new ArrayList<String>();
        if (state.projects == null) return warnings;
        if (state.quarantinedProjects == null) state.quarantinedProjects = new TreeMap<>();
        var grouped = new HashMap<String, List<Map.Entry<String, ReviewHistoryStore.ProjectState>>>();
        for (var entry : state.projects.entrySet()) {
            String key = projectKey(entry.getKey(), expand);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
            if (entry.getValue() != null && entry.getValue().files != null) {
                for (var file : entry.getValue().files.values()) if (file != null) recoverFile(file, expand);
            }
        }
        var projects = new TreeMap<String, ReviewHistoryStore.ProjectState>();
        for (var group : grouped.entrySet()) {
            if (group.getValue().size() == 1) projects.put(group.getKey(), group.getValue().getFirst().getValue());
            else {
                for (var old : group.getValue()) {
                    String preserved = old.getKey();
                    while (state.quarantinedProjects.containsKey(preserved)) preserved += "#preserved";
                    state.quarantinedProjects.put(preserved, old.getValue());
                }
                var fresh = new ReviewHistoryStore.ProjectState();
                fresh.legacyImported = true;
                projects.put(group.getKey(), fresh);
                warnings.add("Conflicting legacy project identities were preserved separately; old reviews were not attached automatically");
            }
        }
        state.projects = projects;
        return List.copyOf(warnings);
    }

    private static String projectKey(String key, UnaryOperator<String> expand) {
        if (key == null) throw new IllegalArgumentException("Missing legacy project identity");
        String candidate = key;
        if (key.startsWith("$")) {
            String expanded = expand.apply(key);
            try { if (Path.of(expanded).isAbsolute()) candidate = expanded; }
            catch (RuntimeException invalid) { return key; }
        }
        candidate = candidate.replace('\\', '/');
        return System.getProperty("os.name", "").startsWith("Windows") ? candidate.toLowerCase(Locale.ROOT) : candidate;
    }

    private static void recoverFile(ReviewHistoryStore.FileState file, UnaryOperator<String> expand) {
        if (file.snapshots != null) file.snapshots.replaceAll((id, raw) -> {
            if (raw == null || id == null || matches(id, raw)) return raw;
            String candidate = expand.apply(raw);
            return matches(id, candidate) ? candidate : raw;
        });
        if (file.checkpoints != null) file.checkpoints.replaceAll((hash, value) -> recoverCheckpoint(value, expand));
        file.pendingCheckpoint = recoverCheckpoint(file.pendingCheckpoint, expand);
    }

    private static boolean matches(String hash, String text) {
        return text != null && hash.equals(FileSnapshot.create(text).id());
    }

    private static ReviewHistoryStore.CheckpointState recoverCheckpoint(ReviewHistoryStore.CheckpointState raw, UnaryOperator<String> expand) {
        if (raw == null || raw.payload == null || raw.checksum == null) return raw;
        try {
            if (raw.checksum.equals(ProjectionCheckpointCodec.checksum(raw.payload))) return raw;
            var candidate = ReviewHistoryStateCopies.checkpoint(raw);
            if (candidate.payload.refs == null) return raw;
            for (var ref : candidate.payload.refs) {
                if (ref == null) return raw;
                if (ref.baselineText != null) ref.baselineText = expand.apply(ref.baselineText);
                if (ref.anchor != null) {
                    if (ref.anchor.baselineText != null) ref.anchor.baselineText = expand.apply(ref.anchor.baselineText);
                    if (ref.anchor.removedText != null) ref.anchor.removedText = expand.apply(ref.anchor.removedText);
                }
                if (ref.unresolved != null && ref.unresolved.originalText != null) ref.unresolved.originalText = expand.apply(ref.unresolved.originalText);
            }
            return raw.checksum.equals(ProjectionCheckpointCodec.checksum(candidate.payload)) ? candidate : raw;
        } catch (RuntimeException invalid) { return raw; }
    }
}
