package dev.doreview.ide;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;
import dev.doreview.core.EvidenceRef;
import dev.doreview.core.FileProjection;
import dev.doreview.core.FileSnapshot;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewedRange;
import org.jdom.Element;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Validated provenance cache; it can restrict evidence but never creates review evidence. */
final class ProjectionCheckpointCodec {
    private ProjectionCheckpointCodec() { }

    static ReviewHistoryStore.CheckpointState encode(ReviewHistory history, String text, ProjectionHints hints) {
        return encode(index(history), text, hints);
    }

    static ReviewHistoryStore.CheckpointState encode(EvidenceIndex index, String text, ProjectionHints hints) {
        var state = new ReviewHistoryStore.CheckpointState();
        state.payload.version = 2;
        state.payload.textHash = FileSnapshot.create(text).id();
        state.payload.textLength = text.length();
        var rematchable = rematchable(hints, text);
        var refs = new TreeSet<>(Comparator.comparing(EvidenceRef::evidenceId)
                .thenComparingInt(EvidenceRef::baselineStart).thenComparingInt(EvidenceRef::baselineEnd));
        refs.addAll(hints.authoritativeRefs()); refs.addAll(hints.livePositions().keySet());
        refs.addAll(hints.deletedRefs()); refs.addAll(hints.deletedAnchors().keySet());
        refs.addAll(hints.baselineTexts().keySet()); refs.addAll(hints.unresolvedPositions().keySet());
        for (var ref : refs) {
            var stored = new ReviewHistoryStore.CheckpointRefState();
            stored.evidenceId = ref.evidenceId(); stored.start = ref.baselineStart(); stored.end = ref.baselineEnd();
            stored.authoritative = hints.authoritativeRefs().contains(ref); stored.deleted = hints.deletedRefs().contains(ref);
            stored.live = range(hints.livePositions().get(ref));
            stored.baselineText = hints.baselineTexts().get(ref);
            stored.allowRematch = rematchable.contains(ref);
            var anchor = hints.deletedAnchors().get(ref);
            if (anchor != null) {
                stored.anchor = new ReviewHistoryStore.DeletedAnchorState();
                stored.anchor.offset = anchor.offset(); stored.anchor.relativeStart = anchor.relativeStart();
                stored.anchor.ordinal = anchor.ordinal(); stored.anchor.baselineText = anchor.baselineText();
                stored.anchor.removedText = anchor.removedText();
            }
            var unresolved = hints.unresolvedPositions().get(ref);
            if (unresolved != null) {
                stored.unresolved = new ReviewHistoryStore.UnresolvedState();
                stored.unresolved.range = range(unresolved.range()); stored.unresolved.reason = unresolved.reason().name();
                stored.unresolved.originalText = unresolved.originalText(); stored.unresolved.relativeStart = unresolved.relativeStart();
                stored.unresolved.relativeEnd = unresolved.relativeEnd();
                stored.unresolved.excludedRows = new ArrayList<>(unresolved.excludedRows().stream().map(ProjectionCheckpointCodec::range).toList());
            }
            state.payload.refs.add(stored);
        }
        state.checksum = checksum(state.payload);
        decodePayload(index, text, state, false);
        return state;
    }

    static ProjectionHints decode(ReviewHistory history, String text, ReviewHistoryStore.CheckpointState state) {
        return decodePayload(index(history), text, state);
    }

    static Set<EvidenceRef> authoritative(ReviewHistory history, String hash, ReviewHistoryStore.CheckpointState state) {
        ProjectionHints hints = decodePayload(index(history), null, state);
        require(state.payload.textHash.equals(hash), "Checkpoint key mismatch");
        return hints.authoritativeRefs();
    }

    static ValidatedCheckpoint validate(EvidenceIndex index, ReviewHistoryStore.CheckpointState state) {
        ProjectionHints hints = decodePayload(index, null, state);
        return cached(state, hints);
    }

    static ValidatedCheckpoint cached(ReviewHistoryStore.CheckpointState state, ProjectionHints hints) {
        var rematchable = new HashSet<EvidenceRef>();
        if (state.payload.version == 2) for (var stored : state.payload.refs) {
            if (stored.allowRematch) rematchable.add(new EvidenceRef(stored.evidenceId, stored.start, stored.end));
        }
        return new ValidatedCheckpoint(state.payload.textHash, state.payload.textLength, hints, Set.copyOf(rematchable));
    }

    /** Only changed live originals with no existing exact-text copy may cold-match another version. */
    static Set<EvidenceRef> rematchable(ProjectionHints hints, String text) {
        if (hints.livePositions().isEmpty()) return Set.of();
        var rows = new HashSet<String>();
        for (int start = 0; start < text.length();) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            rows.add(text.substring(start, end));
            start = end;
        }
        var result = new HashSet<EvidenceRef>();
        for (var entry : hints.livePositions().entrySet()) {
            var ref = entry.getKey();
            var live = entry.getValue();
            String baseline = hints.baselineTexts().get(ref);
            int newline = text.indexOf('\n', live.start());
            boolean fullRow = live.end() <= text.length() && (live.start() == 0 || text.charAt(live.start() - 1) == '\n')
                    && live.end() == (newline < 0 ? text.length() : newline + 1);
            if (fullRow && baseline != null && !rows.contains(baseline)) result.add(ref);
        }
        return result;
    }

    private static ProjectionHints decodePayload(EvidenceIndex index, String text, ReviewHistoryStore.CheckpointState state) {
        return decodePayload(index, text, state, true);
    }

    private static ProjectionHints decodePayload(EvidenceIndex index, String text, ReviewHistoryStore.CheckpointState state, boolean verifyChecksum) {
        require(state != null && state.payload != null && (state.payload.version == 1 || state.payload.version == 2),
                "Invalid checkpoint version");
        var payload = state.payload;
        if (verifyChecksum) require(state.checksum != null && state.checksum.equals(checksum(payload)), "Checkpoint checksum mismatch");
        require(payload.textHash != null && payload.textHash.matches("[0-9a-f]{64}") && payload.textLength >= 0, "Invalid checkpoint snapshot");
        if (text != null) require(text.equals(FileSnapshot.normalize(text)) && payload.textHash.equals(FileSnapshot.create(text).id())
                && payload.textLength == text.length(), "Checkpoint snapshot mismatch");
        require(payload.refs != null, "Missing checkpoint references");
        Map<EvidenceRef, String> valid = index.rows();
        var positions = new LinkedHashMap<EvidenceRef, ReviewedRange>();
        var authoritative = new HashSet<EvidenceRef>();
        var deleted = new HashSet<EvidenceRef>();
        var anchors = new HashMap<EvidenceRef, ProjectionHints.DeletedAnchor>();
        var baseline = new HashMap<EvidenceRef, String>();
        var unresolved = new HashMap<EvidenceRef, ProjectionHints.UnresolvedPosition>();
        var seen = new HashSet<EvidenceRef>();
        var rematchable = new HashSet<EvidenceRef>();
        for (var stored : payload.refs) {
            require(stored != null, "Null checkpoint reference");
            var ref = new EvidenceRef(stored.evidenceId, stored.start, stored.end);
            require(valid.containsKey(ref) && seen.add(ref), "Unknown or duplicate checkpoint reference");
            if (stored.authoritative) authoritative.add(ref);
            if (stored.deleted) deleted.add(ref);
            if (stored.live != null) {
                var live = currentRange(stored.live, text, payload.textLength);
                if (text != null) {
                    int newline = text.indexOf('\n', live.start());
                    require(live.end() == (newline < 0 ? text.length() : newline + 1), "Live checkpoint position must be one row");
                }
                positions.put(ref, live);
            }
            if (stored.baselineText != null) {
                require(stored.baselineText.equals(valid.get(ref)), "Checkpoint baseline text mismatch");
                baseline.put(ref, stored.baselineText);
            }
            if (stored.anchor != null) {
                var anchor = stored.anchor;
                require(anchor.offset >= 0 && anchor.offset <= payload.textLength && anchor.baselineText != null
                        && anchor.baselineText.equals(valid.get(ref)), "Invalid checkpoint deletion anchor");
                anchors.put(ref, new ProjectionHints.DeletedAnchor(anchor.offset, anchor.relativeStart, anchor.ordinal,
                        anchor.baselineText, anchor.removedText));
            }
            if (stored.unresolved != null) {
                var item = stored.unresolved;
                var bounds = currentRange(item.range, text, payload.textLength);
                require(item.excludedRows != null && item.reason != null, "Invalid unresolved checkpoint");
                if (item.originalText == null) require(item.relativeStart == 0 && item.relativeEnd == 0, "Invalid absent checkpoint original");
                else require(item.relativeStart >= 0 && item.relativeEnd > item.relativeStart
                        && item.relativeEnd <= item.originalText.length(), "Invalid checkpoint original offsets");
                var excluded = new ArrayList<ReviewedRange>();
                for (var row : item.excludedRows) {
                    var range = currentRange(row, text, payload.textLength);
                    require(range.start() >= bounds.start() && range.end() <= bounds.end(), "Checkpoint exclusion is outside its range");
                    excluded.add(range);
                }
                unresolved.put(ref, new ProjectionHints.UnresolvedPosition(bounds, FileProjection.Reason.valueOf(item.reason),
                        item.originalText, item.relativeStart, item.relativeEnd, excluded));
            }
            require(!(stored.live != null && (stored.deleted || stored.unresolved != null)), "Conflicting checkpoint positions");
            if (stored.allowRematch) {
                require(payload.version == 2 && stored.live != null && stored.baselineText != null,
                        "Invalid checkpoint rematch permission");
                rematchable.add(ref);
            }
        }
        var result = new ProjectionHints(positions, authoritative, deleted, anchors, baseline, unresolved);
        if (text != null && !rematchable.isEmpty()) require(rematchable(result, text).containsAll(rematchable),
                "Invalid checkpoint rematch permission");
        return result;
    }

    static ProjectionHints failClosed(ReviewHistory history) {
        return failClosed(index(history));
    }

    static ProjectionHints failClosed(EvidenceIndex index) {
        return new ProjectionHints(Map.of(), index.rows().keySet());
    }

    static EvidenceIndex index(ReviewHistory history) {
        var result = new HashMap<EvidenceRef, String>();
        for (var proof : history.evidence()) {
            String source = history.snapshots().get(proof.snapshotId()).text();
            for (var range : proof.ranges()) {
                int start = range.start();
                while (start < range.end()) {
                    int newline = source.indexOf('\n', start);
                    int end = Math.min(range.end(), newline < 0 ? source.length() : newline + 1);
                    result.put(new EvidenceRef(proof.id(), start, end), source.substring(start, end)); start = end;
                }
            }
        }
        return new EvidenceIndex(Map.copyOf(result));
    }

    /** Worker preparation validates only requested fragments, avoiding a full row expansion for a tiny checkpoint. */
    static EvidenceIndex index(ReviewHistory history, ProjectionHints hints) {
        var requested = new HashMap<String, Set<EvidenceRef>>();
        var refs = new HashSet<>(hints.authoritativeRefs()); refs.addAll(hints.baselineTexts().keySet());
        for (var ref : refs) requested.computeIfAbsent(ref.evidenceId(), ignored -> new HashSet<>()).add(ref);
        var result = new HashMap<EvidenceRef, String>();
        for (var proof : history.evidence()) {
            var wanted = requested.get(proof.id());
            if (wanted == null) continue;
            String source = history.snapshots().get(proof.snapshotId()).text();
            for (var ref : wanted) {
                if (ref.baselineEnd() > source.length()) continue;
                int rowStart = source.lastIndexOf('\n', ref.baselineStart() - 1) + 1;
                int newline = source.indexOf('\n', ref.baselineStart());
                int rowEnd = newline < 0 ? source.length() : newline + 1;
                for (var range : proof.ranges()) if (Math.max(range.start(), rowStart) == ref.baselineStart()
                        && Math.min(range.end(), rowEnd) == ref.baselineEnd()) {
                    result.put(ref, source.substring(ref.baselineStart(), ref.baselineEnd())); break;
                }
            }
        }
        return new EvidenceIndex(Map.copyOf(result));
    }

    private static ReviewedRange currentRange(ReviewHistoryStore.RangeState stored, String text, int length) {
        require(stored != null, "Missing checkpoint range");
        var range = new ReviewedRange(stored.start, stored.end);
        require(range.end() <= length, "Checkpoint range is outside current text");
        if (text != null) require((range.start() == 0 || text.charAt(range.start() - 1) == '\n')
                && (range.end() == text.length() || text.charAt(range.end() - 1) == '\n'), "Checkpoint range is outside complete current rows");
        return range;
    }

    private static ReviewHistoryStore.RangeState range(ReviewedRange range) {
        return range == null ? null : new ReviewHistoryStore.RangeState(range.start(), range.end());
    }

    static String checksum(ReviewHistoryStore.CheckpointPayload payload) {
        try {
            var canonicalPayload = XmlSerializer.serialize(payload);
            // The v1 canonical form predates this field, including its false default.
            if (payload.version == 1) omitRematchPermission(canonicalPayload);
            byte[] canonical = JDOMUtil.writeElement(canonicalPayload).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void omitRematchPermission(Element element) {
        for (var child : new ArrayList<>(element.getChildren())) {
            if ("option".equals(child.getName()) && "allowRematch".equals(child.getAttributeValue("name"))) child.detach();
            else omitRematchPermission(child);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    record EvidenceIndex(Map<EvidenceRef, String> rows) { }

    record ValidatedCheckpoint(String textHash, int textLength, ProjectionHints hints, Set<EvidenceRef> rematchable) {
        Set<EvidenceRef> restrictions() {
            var refs = new HashSet<>(hints.authoritativeRefs());
            refs.removeAll(rematchable);
            return refs;
        }

        ProjectionHints forText(String text, String hash) {
            require(textHash.equals(hash) && textLength == text.length() && text.equals(FileSnapshot.normalize(text)),
                    "Checkpoint snapshot mismatch");
            for (var live : hints.livePositions().values()) {
                currentRange(range(live), text, textLength);
                int newline = text.indexOf('\n', live.start());
                require(live.end() == (newline < 0 ? textLength : newline + 1), "Live checkpoint position must be one row");
            }
            for (var unresolved : hints.unresolvedPositions().values()) {
                currentRange(range(unresolved.range()), text, textLength);
                for (var excluded : unresolved.excludedRows()) currentRange(range(excluded), text, textLength);
            }
            if (!rematchable.isEmpty()) require(ProjectionCheckpointCodec.rematchable(hints, text).containsAll(rematchable),
                    "Invalid checkpoint rematch permission");
            return hints;
        }
    }
}
