package dev.doreview.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Known local row identity wins over heuristic rematching, including duplicates. */
public record ProjectionHints(Map<EvidenceRef, ReviewedRange> livePositions,
                              Set<EvidenceRef> authoritativeRefs, Set<EvidenceRef> deletedRefs,
                              Map<EvidenceRef, DeletedAnchor> deletedAnchors,
                              Map<EvidenceRef, String> baselineTexts,
                              Map<EvidenceRef, UnresolvedPosition> unresolvedPositions) {
    public ProjectionHints {
        livePositions = Map.copyOf(livePositions);
        authoritativeRefs = Set.copyOf(authoritativeRefs);
        deletedRefs = Set.copyOf(deletedRefs);
        deletedAnchors = Map.copyOf(deletedAnchors);
        baselineTexts = Map.copyOf(baselineTexts);
        unresolvedPositions = Map.copyOf(unresolvedPositions);
        if (!authoritativeRefs.containsAll(livePositions.keySet()) || !authoritativeRefs.containsAll(deletedRefs)
                || !deletedRefs.containsAll(deletedAnchors.keySet())
                || !authoritativeRefs.containsAll(unresolvedPositions.keySet())) {
            throw new IllegalArgumentException("All known positions/deletions must be authoritative");
        }
    }

    public ProjectionHints(Map<EvidenceRef, ReviewedRange> positions, Set<EvidenceRef> authoritative) {
        this(positions, authoritative, Set.of(), Map.of(), Map.of(), Map.of());
    }

    public ProjectionHints(Map<EvidenceRef, ReviewedRange> positions, Set<EvidenceRef> authoritative,
            Set<EvidenceRef> deleted) {
        this(positions, authoritative, deleted, Map.of(), Map.of(), Map.of());
    }

    public static ProjectionHints empty() {
        return new ProjectionHints(Map.of(), Set.of(), Set.of());
    }

    /**
     * Content-keyed cache of the facts that cold text matching cannot recover.
     * Unique unchanged rows need no checkpoint; changed identities, lost origins,
     * deletions and duplicate-row identity keep their complete provenance.
     */
    public ProjectionHints compactForCheckpoint(String current) {
        if (!FileSnapshot.normalize(current).equals(current)) {
            throw new IllegalArgumentException("Checkpoint text must use normalized LF offsets");
        }
        return compactForCheckpoint(current, SnapshotIndex.prepare(current, () -> { }), () -> { });
    }

    private ProjectionHints compactForCheckpoint(String current, SnapshotIndex currentIndex, Runnable cancellationCheck) {
        var retained = new LinkedHashSet<EvidenceRef>();
        int processed = 0;
        for (var ref : authoritativeRefs) {
            if ((processed++ & 255) == 0) cancellationCheck.run();
            var position = livePositions.get(ref);
            String baseline = baselineTexts.get(ref);
            boolean fullRow = position != null && position.end() <= current.length()
                    && (position.start() == 0 || current.charAt(position.start() - 1) == '\n')
                    && (position.end() == current.length() || current.charAt(position.end() - 1) == '\n');
            boolean exactUnique = fullRow && baseline != null && position.end() - position.start() == baseline.length()
                    && current.regionMatches(position.start(), baseline, 0, baseline.length())
                    && currentIndex.count(baseline) == 1;
            if (!exactUnique || deletedRefs.contains(ref) || unresolvedPositions.containsKey(ref)) retained.add(ref);
        }
        return retainOnly(retained);
    }

    /**
     * Further removes unchanged boilerplate whose cold correspondence agrees
     * with its known position. Intended for a settled/background checkpoint;
     * the current-only overload stays cheap and conservative during typing.
     */
    public ProjectionHints compactForCheckpoint(String current, ReviewHistory history) {
        return compactForCheckpoint(current, history, () -> { });
    }

    public ProjectionHints compactForCheckpoint(String current, ReviewHistory history, Runnable cancellationCheck) {
        return compactForCheckpoint(current, history, ProjectionWorkBudget.defaults(), cancellationCheck);
    }

    /** May share a projection's remaining quadratic allowance; exhaustion only keeps more provenance. */
    public ProjectionHints compactForCheckpoint(String current, ReviewHistory history,
            ProjectionWorkBudget workBudget, Runnable cancellationCheck) {
        Objects.requireNonNull(workBudget, "workBudget");
        if (!FileSnapshot.normalize(current).equals(current)) {
            throw new IllegalArgumentException("Checkpoint text must use normalized LF offsets");
        }
        return compactForCheckpoint(history, new ReviewProjectionWorkspace(current, workBudget, cancellationCheck));
    }

    /** Uses the same current preparation and mapping allowance as the preceding projection. */
    public ProjectionHints compactForCheckpoint(ReviewHistory history, ReviewProjectionWorkspace workspace) {
        workspace.checkCanceled();
        var cancellationCheck = workspace.cancellationCheck();
        String current = workspace.current().text();
        var currentIndex = workspace.currentIndex();
        var conservative = compactForCheckpoint(current, currentIndex, cancellationCheck);
        var currentLines = currentIndex.lines();
        var byId = ReviewHistoryIndex.of(history, cancellationCheck).byId();
        var retained = new LinkedHashSet<>(conservative.authoritativeRefs);
        String currentId = workspace.current().id();
        int processed = 0;
        for (var ref : conservative.authoritativeRefs) {
            if ((processed++ & 255) == 0) cancellationCheck.run();
            var position = conservative.livePositions.get(ref);
            var proof = byId.get(ref.evidenceId());
            if (position == null || proof == null || conservative.deletedRefs.contains(ref)
                    || conservative.unresolvedPositions.containsKey(ref)) continue;
            var baseline = history.snapshots().get(proof.snapshotId());
            if (baseline == null || ref.baselineEnd() > baseline.text().length()) continue;
            var baselineIndex = SnapshotIndex.of(baseline, cancellationCheck);
            var lines = baselineIndex.lines();
            int line = lines.lineAt(ref.baselineStart());
            if (line < 0 || ref.baselineStart() != lines.starts()[line] || ref.baselineEnd() != lines.end(line)) continue;
            String source = lines.text().get(line);
            if (position.end() > current.length() || position.end() - position.start() != source.length()
                    || !current.regionMatches(position.start(), source, 0, source.length())) continue;
            if (baseline.id().equals(currentId)) {
                retained.remove(ref); // Whole-snapshot restoration already fixes original row positions.
                continue;
            }
            if (currentIndex.count(source) > baselineIndex.count(source)) continue;
            var mapping = workspace.mapping(baseline);
            int target = mapping.currentLine(line);
            if (target >= 0 && position.start() == currentLines.starts()[target] && position.end() == currentLines.end(target)) {
                retained.remove(ref);
            }
        }
        return conservative.retainOnly(retained);
    }

    private ProjectionHints retainOnly(Set<EvidenceRef> retained) {
        var positions = new LinkedHashMap<EvidenceRef, ReviewedRange>();
        var deleted = new LinkedHashSet<EvidenceRef>();
        var anchors = new HashMap<EvidenceRef, DeletedAnchor>();
        var text = new HashMap<EvidenceRef, String>();
        var unresolved = new HashMap<EvidenceRef, UnresolvedPosition>();
        for (var ref : retained) {
            if (livePositions.containsKey(ref)) positions.put(ref, livePositions.get(ref));
            if (deletedRefs.contains(ref)) deleted.add(ref);
            if (deletedAnchors.containsKey(ref)) anchors.put(ref, deletedAnchors.get(ref));
            if (baselineTexts.containsKey(ref)) text.put(ref, baselineTexts.get(ref));
            if (unresolvedPositions.containsKey(ref)) unresolved.put(ref, unresolvedPositions.get(ref));
        }
        return new ProjectionHints(positions, retained, deleted, anchors, text, unresolved);
    }

    /** Only actually resolved refs become authoritative; filtered fast projections are safe. */
    public static ProjectionHints fromProjection(ReviewHistory history, FileProjection projection) {
        var positions = new LinkedHashMap<EvidenceRef, ReviewedRange>();
        var authoritative = new LinkedHashSet<EvidenceRef>();
        var duplicate = new HashSet<EvidenceRef>();
        var byId = ReviewHistoryIndex.of(history, () -> { }).byId();
        var baselineTexts = new HashMap<EvidenceRef, String>();
        var canonicalGroups = new IdentityHashMap<FileProjection.PossibleEvidence, FileProjection.PossibleEvidence>();
        var groupsByValue = new HashMap<FileProjection.PossibleEvidence, FileProjection.PossibleEvidence>();
        var seenGroups = Collections.newSetFromMap(new IdentityHashMap<FileProjection.PossibleEvidence, Boolean>());
        var groupRanges = new IdentityHashMap<FileProjection.PossibleEvidence, ReviewedRange>();
        var groupCoverage = new IdentityHashMap<FileProjection.PossibleEvidence, java.util.List<ReviewedRange>>();
        var unresolved = new HashMap<EvidenceRef, UnresolvedPosition>();
        for (var row : projection.rows()) {
            for (var candidate : row.evidence().possibleGroups()) {
                // Sharing is an optimization only: a copied/decoded equal value has identical meaning.
                var group = canonicalGroups.computeIfAbsent(candidate,
                        value -> groupsByValue.computeIfAbsent(value, ignored -> value));
                var bounds = groupRanges.get(group);
                groupRanges.put(group, new ReviewedRange(bounds == null ? row.start() : Math.min(bounds.start(), row.start()),
                        bounds == null ? row.end() : Math.max(bounds.end(), row.end())));
                groupCoverage.computeIfAbsent(group, ignored -> new ArrayList<>()).add(new ReviewedRange(row.start(), row.end()));
                if (seenGroups.add(group)) {
                    authoritative.addAll(group.refs());
                    captureTexts(group.refs(), history, byId, baselineTexts);
                }
            }
            for (var ref : row.evidence().direct()) {
                authoritative.add(ref);
                var proof = byId.get(ref.evidenceId());
                if (proof != null) baselineTexts.put(ref, history.snapshots().get(proof.snapshotId()).text()
                        .substring(ref.baselineStart(), ref.baselineEnd()));
                if (positions.putIfAbsent(ref, new ReviewedRange(row.start(), row.end())) != null) duplicate.add(ref);
            }
        }
        groupRanges.forEach((group, bounds) -> {
            java.util.List<ReviewedRange> holes = java.util.List.of(bounds);
            for (var covered : ReviewRanges.normalized(groupCoverage.get(group))) {
                holes = ReviewRanges.clear(holes, covered.start(), covered.end());
            }
            var position = new UnresolvedPosition(bounds, group.reason(), null, 0, 0, holes);
            for (var ref : group.refs()) unresolved.put(ref, position);
        });
        duplicate.forEach(positions::remove);
        authoritative.addAll(projection.deletedRefs());
        var anchors = new HashMap<EvidenceRef, DeletedAnchor>();
        var tables = new HashMap<String, LineTable>();
        for (var deletion : projection.deletions()) {
            for (var ref : deletion.evidenceRefs()) {
                var proof = byId.get(ref.evidenceId());
                if (proof == null) continue;
                var snapshot = history.snapshots().get(proof.snapshotId());
                String text = snapshot.text();
                var lines = tables.computeIfAbsent(proof.snapshotId(), ignored -> SnapshotIndex.of(snapshot, () -> { }).lines());
                int row = lines.lineAt(ref.baselineStart());
                int gapStart = Math.max(0, row - deletion.ordinal());
                String source = text.substring(ref.baselineStart(), ref.baselineEnd());
                baselineTexts.put(ref, source);
                anchors.put(ref, new DeletedAnchor(deletion.currentOffset(),
                        ref.baselineStart() - lines.starts()[gapStart], deletion.ordinal(), source, source));
            }
        }
        projection.deletedRefs().forEach(unresolved::remove);
        return new ProjectionHints(positions, authoritative, Set.copyOf(projection.deletedRefs()), anchors, baselineTexts, unresolved);
    }

    private static void captureTexts(java.util.List<EvidenceRef> refs, ReviewHistory history,
            Map<String, ReviewEvidence> byId, Map<EvidenceRef, String> texts) {
        for (var ref : refs) {
            var proof = byId.get(ref.evidenceId());
            if (proof != null) texts.putIfAbsent(ref, history.snapshots().get(proof.snapshotId()).text()
                    .substring(ref.baselineStart(), ref.baselineEnd()));
        }
    }

    /** Fresh resolved positions win; unlocated authoritative identities and live tombstones survive. */
    public ProjectionHints mergeResolved(ProjectionHints fresh) {
        var authoritative = new LinkedHashSet<>(authoritativeRefs);
        authoritative.addAll(fresh.authoritativeRefs);
        var positions = new LinkedHashMap<>(livePositions);
        fresh.authoritativeRefs.forEach(positions::remove);
        positions.putAll(fresh.livePositions);
        var deleted = new LinkedHashSet<>(deletedRefs);
        deleted.addAll(fresh.deletedRefs);
        var anchors = new HashMap<>(fresh.deletedAnchors);
        anchors.putAll(deletedAnchors); // The known local deletion anchor is stronger than a diff guess.
        var unresolved = new HashMap<>(unresolvedPositions);
        fresh.authoritativeRefs.forEach(unresolved::remove);
        unresolved.putAll(fresh.unresolvedPositions);
        for (var entry : unresolvedPositions.entrySet()) {
            var next = fresh.unresolvedPositions.get(entry.getKey());
            if (entry.getValue().originalText() != null && next != null
                    && entry.getValue().range().equals(next.range())) {
                unresolved.put(entry.getKey(), entry.getValue());
            }
        }
        for (var ref : fresh.livePositions.keySet()) {
            deleted.remove(ref);
            anchors.remove(ref);
            unresolved.remove(ref);
        }
        var text = new HashMap<>(baselineTexts);
        text.putAll(fresh.baselineTexts);
        return new ProjectionHints(positions, authoritative, deleted, anchors, text, unresolved);
    }

    /** Unknown old proofs cannot attach to edited-row copies while projection is pending. */
    public ProjectionHints guardUnresolvedEdit(ReviewHistory history, String previous, String current,
            int offset, int oldLength, int newLength) {
        var window = affectedWindow(previous, current, offset, oldLength, newLength);
        var affected = new HashSet<>(LineTable.from(current.substring(window.start, window.newEnd)).text());
        if (affected.isEmpty()) return this;
        var authoritative = new LinkedHashSet<>(authoritativeRefs);
        var texts = new HashMap<>(baselineTexts);
        for (var proof : ReviewHistoryEngine.proofRows(history)) {
            if (authoritative.contains(proof.ref()) || !affected.contains(proof.lines().text().get(proof.line()))) continue;
            authoritative.add(proof.ref());
            texts.put(proof.ref(), history.snapshots().get(proof.proof().snapshotId()).text()
                    .substring(proof.ref().baselineStart(), proof.ref().baselineEnd()));
        }
        return new ProjectionHints(livePositions, authoritative, deletedRefs, deletedAnchors, texts, unresolvedPositions);
    }

    /** Retains exact rows plus a changed 1:1 row locator; split/join/copy cannot inherit a proof. */
    public ProjectionHints applyEdit(String previous, String current, int offset, int oldLength, int newLength) {
        // Validates coordinates and snapshots even when there are no positions.
        var window = affectedWindow(previous, current, offset, oldLength, newLength);
        if (previous.equals(current)) return this;
        int delta = newLength - oldLength;
        var positions = new LinkedHashMap<EvidenceRef, ReviewedRange>();
        var deleted = new LinkedHashSet<>(deletedRefs);
        var anchors = new HashMap<EvidenceRef, DeletedAnchor>();
        var unresolved = new HashMap<EvidenceRef, UnresolvedPosition>();
        var oldLines = LineTable.from(previous.substring(window.start, window.oldEnd));
        var newLines = LineTable.from(current.substring(window.start, window.newEnd));
        var mapping = SnapshotReconciler.map(previous.substring(window.start, window.oldEnd),
                current.substring(window.start, window.newEnd));
        var insertedRows = LineReviewTracker.provenInsertedRows(previous, current, offset, oldLength, newLength,
                window.start, window.oldEnd);
        var known = LineReviewTracker.knownExactRows(previous, offset, oldLength, newLength,
                window.start, oldLines, newLines, insertedRows);
        for (var entry : livePositions.entrySet()) {
            var range = entry.getValue();
            if (range.end() > previous.length()) continue;
            if (range.end() <= window.start) {
                positions.put(entry.getKey(), range);
            } else if (range.start() >= window.oldEnd) {
                positions.put(entry.getKey(), new ReviewedRange(range.start() + delta, range.end() + delta));
            } else if (range.start() >= window.start && range.end() <= window.oldEnd) {
                int oldLine = oldLines.lineAt(range.start() - window.start);
                int newLine = mapping.currentLine(oldLine);
                var change = mapping.changeForOldLine(oldLine);
                if (newLine >= 0 && (insertedRows.contains(new ReviewedRange(window.start + newLines.starts()[newLine],
                        window.start + newLines.end(newLine))) || known.conflictedRows().contains(newLine))) {
                    newLine = -1; // Exact text on a newly inserted copy is not the original reviewed row.
                    change = new SnapshotReconciler.Change(oldLine, oldLine + 1, 0, newLines.text().size(),
                            SnapshotReconciler.ChangeReason.AMBIGUOUS);
                }
                if (newLine < 0 && known.exactRows().containsKey(oldLine)) newLine = known.exactRows().get(oldLine);
                if (newLine < 0 && change != null && change.oldEndLine() - change.oldStartLine() == 1
                        && change.currentEndLine() - change.currentStartLine() == 1
                        && change.reason() == SnapshotReconciler.ChangeReason.TEXT_CHANGED) {
                    newLine = change.currentStartLine();
                }
                if (newLine >= 0) {
                    positions.put(entry.getKey(), new ReviewedRange(window.start + newLines.starts()[newLine],
                            window.start + newLines.end(newLine)));
                } else if (change != null && change.reason() == SnapshotReconciler.ChangeReason.DELETED) {
                    deleted.add(entry.getKey());
                    int anchor = change.currentStartLine() < newLines.text().size()
                            ? window.start + newLines.starts()[change.currentStartLine()] : window.newEnd;
                    int relative = range.start() - window.start - oldLines.starts()[change.oldStartLine()];
                    String removed = previous.substring(range.start(), range.end());
                    anchors.put(entry.getKey(), new DeletedAnchor(anchor, relative,
                            oldLine - change.oldStartLine(), baselineTexts.getOrDefault(entry.getKey(), removed), removed));
                } else if (change != null && change.currentStartLine() < change.currentEndLine()) {
                    var bounds = new ReviewedRange(window.start + newLines.starts()[change.currentStartLine()],
                            window.start + newLines.end(change.currentEndLine() - 1));
                    var reason = change.reason() == SnapshotReconciler.ChangeReason.AMBIGUOUS
                            ? FileProjection.Reason.AMBIGUOUS : change.reason() == SnapshotReconciler.ChangeReason.DIFF_LIMIT
                            ? FileProjection.Reason.DIFF_LIMIT : FileProjection.Reason.TEXT_CHANGED;
                    int originalStart = window.start + oldLines.starts()[change.oldStartLine()];
                    int originalEnd = window.start + oldLines.end(change.oldEndLine() - 1);
                    unresolved.put(entry.getKey(), new UnresolvedPosition(bounds, reason,
                            previous.substring(originalStart, originalEnd), range.start() - originalStart,
                            range.end() - originalStart, insertedRows.stream()
                            .filter(inserted -> inserted.start() >= bounds.start() && inserted.end() <= bounds.end()).toList()));
                }
            }
        }
        for (var entry : deletedAnchors.entrySet()) {
            var anchor = entry.getValue();
            int restoredStart = offset + anchor.relativeStart();
            String restored = null;
            if (oldLength == 0 && offset == anchor.offset()) {
                for (String candidate : new String[]{anchor.baselineText(), anchor.removedText()}) {
                    int end = restoredStart + candidate.length();
                    if (end <= offset + newLength && current.regionMatches(restoredStart, candidate, 0, candidate.length())
                            && (restoredStart == 0 || current.charAt(restoredStart - 1) == '\n')
                            && (end == current.length() || current.charAt(end - 1) == '\n')) {
                        restored = candidate;
                        break;
                    }
                }
            }
            if (restored != null) {
                positions.put(entry.getKey(), new ReviewedRange(restoredStart, restoredStart + restored.length()));
                deleted.remove(entry.getKey());
            } else {
                int moved = anchor.offset() < offset ? anchor.offset()
                        : anchor.offset() >= offset + oldLength ? anchor.offset() + delta : offset + newLength;
                anchors.put(entry.getKey(), new DeletedAnchor(moved, anchor.relativeStart(),
                        anchor.ordinal(), anchor.baselineText(), anchor.removedText()));
            }
        }
        for (var entry : unresolvedPositions.entrySet()) {
            var moved = moveRange(entry.getValue().range(), current, offset, oldLength, newLength);
            if (moved == null) continue;
            var prior = entry.getValue();
            boolean restored = prior.originalText() != null && moved.end() - moved.start() == prior.originalText().length()
                    && current.regionMatches(moved.start(), prior.originalText(), 0, prior.originalText().length());
            if (restored) {
                positions.put(entry.getKey(), new ReviewedRange(moved.start() + prior.relativeStart(),
                        moved.start() + prior.relativeEnd()));
                deleted.remove(entry.getKey());
                anchors.remove(entry.getKey());
            } else {
                var exclusions = new ArrayList<ReviewedRange>();
                for (var excluded : prior.excludedRows()) {
                    var shifted = moveExcludedRow(excluded, current, offset, oldLength, newLength);
                    if (shifted != null) exclusions.add(shifted);
                }
                insertedRows.stream().filter(inserted -> inserted.start() >= moved.start() && inserted.end() <= moved.end())
                        .forEach(exclusions::add);
                unresolved.put(entry.getKey(), new UnresolvedPosition(moved, prior.reason(), prior.originalText(),
                        prior.relativeStart(), prior.relativeEnd(), exclusions));
            }
        }
        return new ProjectionHints(positions, authoritativeRefs, deleted, anchors, baselineTexts, unresolved);
    }

    private static Window affectedWindow(String previous, String current, int offset, int oldLength, int newLength) {
        // Existing line engine is the validation authority for real document events.
        LineReviewTracker.applyEdit(previous, current, java.util.List.of(), offset, oldLength, newLength);
        int start = previous.lastIndexOf('\n', offset - 1) + 1;
        int oldEnd;
        if (oldLength == 0 && offset == start) oldEnd = offset;
        else {
            int last = oldLength == 0 ? offset : offset + oldLength - 1;
            int newline = previous.indexOf('\n', last);
            oldEnd = newline < 0 ? previous.length() : newline + 1;
        }
        int newEnd = oldEnd + newLength - oldLength;
        if (newEnd > 0 && newEnd < current.length() && current.charAt(newEnd - 1) != '\n') {
            int newline = current.indexOf('\n', newEnd);
            int fullEnd = newline < 0 ? current.length() : newline + 1;
            oldEnd += fullEnd - newEnd;
            newEnd = fullEnd;
        }
        return new Window(start, oldEnd, newEnd);
    }

    private record Window(int start, int oldEnd, int newEnd) { }

    private static ReviewedRange moveRange(ReviewedRange range, String current, int offset, int oldLength, int newLength) {
        int delta = newLength - oldLength;
        int start;
        int end;
        if (range.end() <= offset) return range;
        if (range.start() >= offset + oldLength) {
            start = range.start() + delta;
            end = range.end() + delta;
        } else {
            start = Math.min(range.start(), offset);
            end = range.end() >= offset + oldLength ? range.end() + delta : offset + newLength;
        }
        if (start >= end || start >= current.length()) return null;
        start = current.lastIndexOf('\n', start - 1) + 1;
        if (end < current.length() && current.charAt(end - 1) != '\n') {
            int newline = current.indexOf('\n', end);
            end = newline < 0 ? current.length() : newline + 1;
        }
        return new ReviewedRange(start, end);
    }

    private static ReviewedRange moveExcludedRow(ReviewedRange range, String current, int offset, int oldLength, int newLength) {
        var moved = moveRange(range, current, offset, oldLength, newLength);
        if (moved == null) return null;
        int delta = newLength - oldLength;
        if (range.end() <= offset || range.start() >= offset + oldLength) return moved;
        // If deleting LF joined it with original text outside the new-only row, ancestry is uncertain again.
        if (offset < range.start() || offset + oldLength > range.end()
                || moved.start() != range.start() || moved.end() != range.end() + delta) return null;
        return moved;
    }

    public record UnresolvedPosition(ReviewedRange range, FileProjection.Reason reason,
                                     String originalText, int relativeStart, int relativeEnd,
                                     java.util.List<ReviewedRange> excludedRows) {
        public UnresolvedPosition {
            excludedRows = ReviewRanges.normalized(excludedRows);
        }

        public UnresolvedPosition(ReviewedRange range, FileProjection.Reason reason,
                String originalText, int relativeStart, int relativeEnd) {
            this(range, reason, originalText, relativeStart, relativeEnd, java.util.List.of());
        }

        public UnresolvedPosition(ReviewedRange range, FileProjection.Reason reason) {
            this(range, reason, null, 0, 0, java.util.List.of());
        }
    }

    public record DeletedAnchor(int offset, int relativeStart, int ordinal, String baselineText, String removedText) {
        public DeletedAnchor {
            if (offset < 0 || relativeStart < 0 || ordinal < 0 || baselineText == null || baselineText.isEmpty()
                    || removedText == null || removedText.isEmpty()) {
                throw new IllegalArgumentException("Invalid deleted row anchor");
            }
        }
    }
}
