package dev.doreview.ide;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewedRange;
import dev.doreview.ide.SharedHistoryRepository.Author;
import dev.doreview.ide.SharedHistoryRepository.Event;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** JSON boundary for portable events and local pending envelopes; persisted field names stay unchanged. */
final class SharedHistoryCodec {
    private SharedHistoryCodec() { }

    static final Gson JSON = new GsonBuilder().disableHtmlEscaping()
            .addSerializationExclusionStrategy(new RuntimeFields())
            .addDeserializationExclusionStrategy(new RuntimeFields()).create();
    static final Gson PORTABLE_JSON = new GsonBuilder().disableHtmlEscaping()
            .addSerializationExclusionStrategy(new RuntimeFields())
            .addSerializationExclusionStrategy(new ExclusionStrategy() {
                @Override public boolean shouldSkipField(FieldAttributes field) {
                    return field.getName().equals("sequence") && (field.getDeclaringClass() == ReviewHistoryStore.EvidenceState.class
                            || field.getDeclaringClass() == ReviewHistoryStore.RevocationState.class);
                }
                @Override public boolean shouldSkipClass(Class<?> type) { return false; }
            }).create();
    private static final class RuntimeFields implements ExclusionStrategy {
        @Override public boolean shouldSkipField(FieldAttributes field) {
            return field.getDeclaringClass() == ReviewHistoryStore.FileState.class && !field.hasModifier(Modifier.PUBLIC);
        }
        @Override public boolean shouldSkipClass(Class<?> type) { return false; }
    }

    static final class PendingState {
        public int version = 1;
        public ReviewHistoryStore.FileState state;
        public Set<String> parents = new TreeSet<>();
        public Set<String> snapshotIds = new TreeSet<>();
        public long createdAt;
    }

    static Event parseEvent(String payload) {
        try {
            Event event = JSON.fromJson(payload, Event.class);
            if (event == null || event.version != 1) throw new IllegalArgumentException("Unsupported review event version");
            UUID.fromString(event.id); validateIdentifier(event.fileId); ReviewRepositoryPaths.validateRelativePath(event.path);
            new Author(event.authorName, event.authorEmail);
            if (event.createdAt < 0 || event.authorName == null || event.authorName.isBlank() || event.parents == null || event.aliases == null
                    || event.evidence == null || event.revocations == null || event.checkpoints == null || event.snapshots == null) throw new IllegalArgumentException("Incomplete review event");
            if (new HashSet<>(event.parents).size() != event.parents.size() || event.parents.contains(event.id)) throw new IllegalArgumentException("Invalid review parents");
            event.parents.forEach(UUID::fromString); event.aliases.forEach(ReviewRepositoryPaths::validateRelativePath); event.snapshots.forEach(SharedHistoryCodec::validateHash);
            for (var proof : event.evidence) {
                validateIdentifier(proof.id); validateHash(proof.snapshotId);
                if (!event.snapshots.contains(proof.snapshotId) || proof.createdAt < 0 || proof.ranges == null || proof.ranges.isEmpty()) throw new IllegalArgumentException("Invalid review evidence");
                proof.ranges.forEach(range -> new ReviewedRange(range.start, range.end));
            }
            for (var revoked : event.revocations) {
                validateIdentifier(revoked.evidenceId);
                if (revoked.ranges == null || revoked.ranges.isEmpty()) throw new IllegalArgumentException("Invalid review revocation");
                revoked.ranges.forEach(range -> new ReviewedRange(range.start, range.end));
            }
            event.checkpoints.forEach((hash, checkpoint) -> {
                validateHash(hash);
                if (checkpoint == null || checkpoint.payload == null || !hash.equals(checkpoint.payload.textHash)) throw new IllegalArgumentException("Invalid checkpoint key");
            });
            return event;
        } catch (JsonParseException | NullPointerException failure) { throw new IllegalArgumentException("Invalid review event JSON", failure); }
    }

    static void validateFile(ReviewHistoryStore.FileState state) {
        ReviewHistory history = ReviewJournalCodec.read(state);
        var index = ProjectionCheckpointCodec.index(history);
        if (state.checkpoints == null) throw new IllegalArgumentException("Missing checkpoint collection");
        state.checkpoints.forEach((hash, checkpoint) -> {
            if (checkpoint == null || checkpoint.payload == null || !hash.equals(checkpoint.payload.textHash)) throw new IllegalArgumentException("Checkpoint key mismatch");
            ProjectionCheckpointCodec.validate(index, checkpoint);
        });
        if (state.pendingCheckpoint != null) ProjectionCheckpointCodec.validate(index, state.pendingCheckpoint);
    }
    static void validatePendingHeader(PendingState pending) {
        if (pending == null || (pending.version != 1 && pending.version != 2) || pending.state == null || pending.parents == null || pending.createdAt < 0)
            throw new IllegalArgumentException("Incomplete or unsupported pending provenance");
        pending.parents.forEach(UUID::fromString);
    }

    static void validateHash(String hash) { if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid snapshot hash"); }
    static void validateIdentifier(String id) { if (id == null || !id.matches("[A-Za-z0-9._-]{1,160}")) throw new IllegalArgumentException("Invalid review identifier"); }
    static String canonicalEvidence(ReviewHistoryStore.EvidenceState state) { var value = copy(state, ReviewHistoryStore.EvidenceState.class); value.sequence = 0; return JSON.toJson(value); }
    static String canonicalRevocation(ReviewHistoryStore.RevocationState state) { var value = copy(state, ReviewHistoryStore.RevocationState.class); value.sequence = 0; return JSON.toJson(value); }
    static ReviewHistoryStore.FileState copyFile(ReviewHistoryStore.FileState file) {
        var result = new ReviewHistoryStore.FileState();
        result.path = file.path; result.revision = file.revision; result.snapshots = new TreeMap<>(file.snapshots);
        for (var proof : file.evidence) result.evidence.add(copy(proof, ReviewHistoryStore.EvidenceState.class));
        for (var revoked : file.revocations) result.revocations.add(copy(revoked, ReviewHistoryStore.RevocationState.class));
        file.checkpoints.forEach((hash, checkpoint) -> result.checkpoints.put(hash, copy(checkpoint, ReviewHistoryStore.CheckpointState.class)));
        result.pendingCheckpoint = copy(file.pendingCheckpoint, ReviewHistoryStore.CheckpointState.class);
        return result;
    }
    static <T> T copy(T value, Class<T> type) { return JSON.fromJson(JSON.toJson(value), type); }
}
