package dev.doreview.core;

import dev.doreview.core.ProjectionHints.DeletedAnchor;
import dev.doreview.core.ProjectionHints.UnresolvedPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Moves known row origins, tombstones and unresolved diagnostics through local edits. */
final class ProjectionHintEdits {
    private ProjectionHintEdits() {
    }

    /** Unknown old proofs cannot attach to edited-row copies while projection is pending. */
    static ProjectionHints guardUnresolvedEdit(ProjectionHints hints, ReviewHistory history,
            String previous, String current, int offset, int oldLength, int newLength) {
        var window = DocumentEditWindow.forEdit(previous, current, offset, oldLength, newLength);
        var affected = new HashSet<>(LineTable.from(current.substring(window.start(), window.newEnd())).text());
        if (affected.isEmpty()) return hints;
        var authoritative = new LinkedHashSet<>(hints.authoritativeRefs());
        var texts = new HashMap<>(hints.baselineTexts());
        for (var proof : ReviewHistoryEngine.proofRows(history)) {
            if (authoritative.contains(proof.ref()) || !affected.contains(proof.lines().text().get(proof.line()))) continue;
            authoritative.add(proof.ref());
            texts.put(proof.ref(), history.snapshots().get(proof.proof().snapshotId()).text()
                    .substring(proof.ref().baselineStart(), proof.ref().baselineEnd()));
        }
        return new ProjectionHints(hints.livePositions(), authoritative, hints.deletedRefs(),
                hints.deletedAnchors(), texts, hints.unresolvedPositions());
    }

    /** Retains exact rows plus a changed 1:1 row locator; split/join/copy cannot inherit a proof. */
    static ProjectionHints applyEdit(ProjectionHints hints, String previous, String current,
            int offset, int oldLength, int newLength) {
        // Validates coordinates and snapshots even when there are no positions.
        var window = DocumentEditWindow.forEdit(previous, current, offset, oldLength, newLength);
        if (previous.equals(current)) return hints;
        int delta = newLength - oldLength;
        var positions = new LinkedHashMap<EvidenceRef, ReviewedRange>();
        var deleted = new LinkedHashSet<>(hints.deletedRefs());
        var anchors = new HashMap<EvidenceRef, DeletedAnchor>();
        var unresolved = new HashMap<EvidenceRef, UnresolvedPosition>();
        var oldLines = LineTable.from(previous.substring(window.start(), window.oldEnd()));
        var newLines = LineTable.from(current.substring(window.start(), window.newEnd()));
        var mapping = SnapshotReconciler.map(previous.substring(window.start(), window.oldEnd()),
                current.substring(window.start(), window.newEnd()));
        var insertedRows = LineReviewTracker.provenInsertedRows(previous, current, offset, oldLength, newLength,
                window.start(), window.oldEnd());
        var known = LineReviewTracker.knownExactRows(previous, offset, oldLength, newLength,
                window.start(), oldLines, newLines, insertedRows);
        for (var entry : hints.livePositions().entrySet()) {
            var range = entry.getValue();
            if (range.end() > previous.length()) continue;
            if (range.end() <= window.start()) {
                positions.put(entry.getKey(), range);
            } else if (range.start() >= window.oldEnd()) {
                positions.put(entry.getKey(), new ReviewedRange(range.start() + delta, range.end() + delta));
            } else if (range.start() >= window.start() && range.end() <= window.oldEnd()) {
                int oldLine = oldLines.lineAt(range.start() - window.start());
                int newLine = mapping.currentLine(oldLine);
                var change = mapping.changeForOldLine(oldLine);
                if (newLine >= 0 && (insertedRows.contains(new ReviewedRange(window.start() + newLines.starts()[newLine],
                        window.start() + newLines.end(newLine))) || known.conflictedRows().contains(newLine))) {
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
                    positions.put(entry.getKey(), new ReviewedRange(window.start() + newLines.starts()[newLine],
                            window.start() + newLines.end(newLine)));
                } else if (change != null && change.reason() == SnapshotReconciler.ChangeReason.DELETED) {
                    deleted.add(entry.getKey());
                    int anchor = change.currentStartLine() < newLines.text().size()
                            ? window.start() + newLines.starts()[change.currentStartLine()] : window.newEnd();
                    int relative = range.start() - window.start() - oldLines.starts()[change.oldStartLine()];
                    String removed = previous.substring(range.start(), range.end());
                    anchors.put(entry.getKey(), new DeletedAnchor(anchor, relative,
                            oldLine - change.oldStartLine(), hints.baselineTexts().getOrDefault(entry.getKey(), removed), removed));
                } else if (change != null && change.currentStartLine() < change.currentEndLine()) {
                    var bounds = new ReviewedRange(window.start() + newLines.starts()[change.currentStartLine()],
                            window.start() + newLines.end(change.currentEndLine() - 1));
                    var reason = change.reason() == SnapshotReconciler.ChangeReason.AMBIGUOUS
                            ? FileProjection.Reason.AMBIGUOUS : change.reason() == SnapshotReconciler.ChangeReason.DIFF_LIMIT
                            ? FileProjection.Reason.DIFF_LIMIT : FileProjection.Reason.TEXT_CHANGED;
                    int originalStart = window.start() + oldLines.starts()[change.oldStartLine()];
                    int originalEnd = window.start() + oldLines.end(change.oldEndLine() - 1);
                    unresolved.put(entry.getKey(), new UnresolvedPosition(bounds, reason,
                            previous.substring(originalStart, originalEnd), range.start() - originalStart,
                            range.end() - originalStart, insertedRows.stream()
                            .filter(inserted -> inserted.start() >= bounds.start() && inserted.end() <= bounds.end()).toList()));
                }
            }
        }
        for (var entry : hints.deletedAnchors().entrySet()) {
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
        for (var entry : hints.unresolvedPositions().entrySet()) {
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
        return new ProjectionHints(positions, hints.authoritativeRefs(), deleted, anchors, hints.baselineTexts(), unresolved);
    }

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
}
