package dev.doreview.ide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** Detached persistence beans without an XML serialization round trip. Strings are immutable. */
final class ReviewHistoryStateCopies {
    private ReviewHistoryStateCopies() { }

    static ReviewHistoryStore.StoreState copy(ReviewHistoryStore.StoreState source) {
        var target = new ReviewHistoryStore.StoreState();
        target.schemaVersion = source.schemaVersion;
        target.projects = map(source.projects, ReviewHistoryStateCopies::project);
        target.quarantinedProjects = map(source.quarantinedProjects, ReviewHistoryStateCopies::project);
        return target;
    }

    private static ReviewHistoryStore.ProjectState project(ReviewHistoryStore.ProjectState source) {
        var target = new ReviewHistoryStore.ProjectState();
        target.files = map(source.files, ReviewHistoryStateCopies::file);
        target.aliases = map(source.aliases, Function.identity());
        target.legacyImported = source.legacyImported;
        target.warnings = list(source.warnings, Function.identity());
        return target;
    }

    private static ReviewHistoryStore.FileState file(ReviewHistoryStore.FileState source) {
        var target = new ReviewHistoryStore.FileState();
        target.path = source.path; target.revision = source.revision;
        target.snapshots = map(source.snapshots, Function.identity());
        target.evidence = list(source.evidence, proof -> {
            var result = new ReviewHistoryStore.EvidenceState();
            result.id = proof.id; result.snapshotId = proof.snapshotId; result.ranges = ranges(proof.ranges);
            result.sequence = proof.sequence; result.createdAt = proof.createdAt; return result;
        });
        target.revocations = list(source.revocations, revoke -> {
            var result = new ReviewHistoryStore.RevocationState();
            result.evidenceId = revoke.evidenceId; result.ranges = ranges(revoke.ranges); result.sequence = revoke.sequence; return result;
        });
        target.checkpoints = map(source.checkpoints, ReviewHistoryStateCopies::checkpoint);
        target.pendingCheckpoint = source.pendingCheckpoint == null ? null : checkpoint(source.pendingCheckpoint);
        return target;
    }

    static ReviewHistoryStore.CheckpointState checkpoint(ReviewHistoryStore.CheckpointState source) {
        var target = new ReviewHistoryStore.CheckpointState();
        target.checksum = source.checksum;
        if (source.payload == null) { target.payload = null; return target; }
        var payload = target.payload;
        payload.version = source.payload.version; payload.textHash = source.payload.textHash; payload.textLength = source.payload.textLength;
        payload.refs = list(source.payload.refs, ref -> {
            var result = new ReviewHistoryStore.CheckpointRefState();
            result.evidenceId = ref.evidenceId; result.start = ref.start; result.end = ref.end;
            result.allowRematch = ref.allowRematch;
            result.authoritative = ref.authoritative; result.deleted = ref.deleted;
            result.live = range(ref.live); result.baselineText = ref.baselineText;
            if (ref.anchor != null) {
                result.anchor = new ReviewHistoryStore.DeletedAnchorState();
                result.anchor.offset = ref.anchor.offset; result.anchor.relativeStart = ref.anchor.relativeStart;
                result.anchor.ordinal = ref.anchor.ordinal; result.anchor.baselineText = ref.anchor.baselineText;
                result.anchor.removedText = ref.anchor.removedText;
            }
            if (ref.unresolved != null) {
                result.unresolved = new ReviewHistoryStore.UnresolvedState();
                result.unresolved.range = range(ref.unresolved.range); result.unresolved.reason = ref.unresolved.reason;
                result.unresolved.originalText = ref.unresolved.originalText;
                result.unresolved.relativeStart = ref.unresolved.relativeStart; result.unresolved.relativeEnd = ref.unresolved.relativeEnd;
                result.unresolved.excludedRows = ranges(ref.unresolved.excludedRows);
            }
            return result;
        });
        return target;
    }

    private static ReviewHistoryStore.RangeState range(ReviewHistoryStore.RangeState source) {
        return source == null ? null : new ReviewHistoryStore.RangeState(source.start, source.end);
    }
    private static List<ReviewHistoryStore.RangeState> ranges(List<ReviewHistoryStore.RangeState> source) {
        return list(source, ReviewHistoryStateCopies::range);
    }
    private static <T, R> List<R> list(List<T> source, Function<T, R> copy) {
        if (source == null) return null;
        var result = new ArrayList<R>(source.size());
        for (var value : source) result.add(value == null ? null : copy.apply(value));
        return result;
    }
    private static <T, R> Map<String, R> map(Map<String, T> source, Function<T, R> copy) {
        if (source == null) return null;
        var result = new TreeMap<String, R>();
        source.forEach((key, value) -> result.put(key, value == null ? null : copy.apply(value)));
        return result;
    }
}
