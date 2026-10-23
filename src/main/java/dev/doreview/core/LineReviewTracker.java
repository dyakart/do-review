package dev.doreview.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Applies a known document replacement while retaining review coverage on exact
 * unchanged rows. Compressed spans are independent reviewed lines, not a single
 * indivisible mark. The edit's known coordinates preserve the identity of repeated
 * lines outside its affected window without diffing the whole file.
 *
 * <p>Only complete old/current lines enter the affected window; bounded snapshot
 * reconciliation retains unchanged rows inside a bulk replacement. Complete-line
 * insertion/deletion preserves adjacent original lines. Splitting, joining, or
 * modifying a row removes its coverage unless its complete text, including LF,
 * still has an unambiguous exact match. An added LF changes an unterminated EOF
 * row. New rows begin unreviewed. There is no history that could restore a mark
 * after undo. Legacy partial coverage is preserved only on unchanged whole rows.
 */
public final class LineReviewTracker {
    private LineReviewTracker() {
    }

    public static List<ReviewedRange> applyEdit(String previous, String current,
            List<ReviewedRange> marks, int offset, int oldLength, int newLength) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        validateEdit(previous, current, offset, oldLength, newLength);
        var reviewed = ReviewRanges.normalized(marks);
        for (var range : reviewed) {
            if (range.end() > previous.length()) {
                throw new IllegalArgumentException("Reviewed range exceeds the previous snapshot");
            }
        }
        if (reviewed.isEmpty() || previous.equals(current)) {
            return reviewed;
        }

        int oldEditEnd = offset + oldLength;
        int delta = newLength - oldLength;
        int windowStart = previous.lastIndexOf('\n', offset - 1) + 1;
        int oldWindowEnd;
        if (oldLength == 0 && offset == windowStart) {
            oldWindowEnd = offset;
        } else {
            int finalTouched = oldLength == 0 ? offset : oldEditEnd - 1;
            int newline = previous.indexOf('\n', finalTouched);
            oldWindowEnd = newline < 0 ? previous.length() : newline + 1;
        }
        int newWindowEnd = oldWindowEnd + delta;
        // A consumed LF or inserted partial row can join the following old row.
        // Include that row on both sides so a cropped prefix cannot appear exact.
        if (newWindowEnd > 0 && newWindowEnd < current.length()
                && current.charAt(newWindowEnd - 1) != '\n') {
            int newline = current.indexOf('\n', newWindowEnd);
            int fullLineEnd = newline < 0 ? current.length() : newline + 1;
            oldWindowEnd += fullLineEnd - newWindowEnd;
            newWindowEnd = fullLineEnd;
        }
        var insertedRows = provenInsertedRows(previous, current, offset, oldLength, newLength, windowStart, oldWindowEnd);

        var retained = new ArrayList<ReviewedRange>();
        var affected = new ArrayList<ReviewedRange>();
        for (var range : reviewed) {
            if (range.start() < windowStart) {
                retained.add(new ReviewedRange(range.start(), Math.min(range.end(), windowStart)));
            }
            if (range.end() > oldWindowEnd) {
                retained.add(new ReviewedRange(Math.max(range.start(), oldWindowEnd) + delta,
                        range.end() + delta));
            }
            int clippedStart = Math.max(range.start(), windowStart);
            int clippedEnd = Math.min(range.end(), oldWindowEnd);
            if (clippedStart < clippedEnd) {
                affected.add(new ReviewedRange(clippedStart - windowStart, clippedEnd - windowStart));
            }
        }
        if (!affected.isEmpty()) {
            String oldWindow = previous.substring(windowStart, oldWindowEnd);
            String newWindow = current.substring(windowStart, newWindowEnd);
            var reconciled = SnapshotReconciler.reconcile(oldWindow, newWindow, affected);
            var oldLines = LineTable.from(oldWindow);
            var currentLines = LineTable.from(newWindow);
            var known = knownExactRows(previous, offset, oldLength, newLength, windowStart, oldLines, currentLines, insertedRows);
            for (var range : reconciled) {
                for (int line = currentLines.lineAt(range.start()); line <= currentLines.lineAt(range.end() - 1); line++) {
                    var full = new ReviewedRange(windowStart + currentLines.starts()[line], windowStart + currentLines.end(line));
                    if (!insertedRows.contains(full) && !known.conflictedRows().contains(line)) {
                        retained.add(new ReviewedRange(windowStart + Math.max(range.start(), currentLines.starts()[line]),
                                windowStart + Math.min(range.end(), currentLines.end(line))));
                    }
                }
            }
            for (var range : affected) {
                for (int line = oldLines.lineAt(range.start()); line <= oldLines.lineAt(range.end() - 1); line++) {
                    Integer mapped = known.exactRows().get(line);
                    if (mapped == null) continue;
                    int start = Math.max(range.start(), oldLines.starts()[line]);
                    int end = Math.min(range.end(), oldLines.end(line));
                    int shifted = windowStart + currentLines.starts()[mapped] + start - oldLines.starts()[line];
                    retained.add(new ReviewedRange(shifted, shifted + end - start));
                }
            }
        }
        return ReviewRanges.normalized(retained);
    }

    /** Complete rows created by insertion or a partial edit that retained original row text. */
    static List<ReviewedRange> provenInsertedRows(String previous, String current, int offset,
            int oldLength, int newLength, int windowStart, int oldWindowEnd) {
        if (newLength == 0) return List.of();
        int firstNewline = previous.indexOf('\n', offset);
        if (oldLength > 0 && firstNewline >= 0 && firstNewline < offset + oldLength - 1) return List.of();
        boolean partialRow = false;
        int oldEnd = offset + oldLength;
        for (int at = windowStart; at < offset && !partialRow; at++) partialRow = previous.charAt(at) != '\n';
        int touchedRowEnd = firstNewline < 0 ? previous.length() : firstNewline + 1;
        for (int at = oldEnd; at < Math.min(oldWindowEnd, touchedRowEnd) && !partialRow; at++) partialRow = previous.charAt(at) != '\n';
        if (oldLength > 0 && !partialRow) return List.of();
        var rows = new ArrayList<ReviewedRange>();
        int start = offset == 0 || current.charAt(offset - 1) == '\n' ? offset : current.indexOf('\n', offset) + 1;
        if (start == 0 && offset > 0) return List.of();
        while (start < offset + newLength) {
            int newline = current.indexOf('\n', start);
            int end = newline < 0 ? current.length() : newline + 1;
            if (end > offset + newLength) break;
            if (start < end) rows.add(new ReviewedRange(start, end));
            start = end;
        }
        return List.copyOf(rows);
    }

    /** Exact row text plus known retained character origins resolves copies without guessing joins. */
    static KnownRows knownExactRows(String previous, int offset, int oldLength, int newLength,
            int windowStart, LineTable oldLines, LineTable currentLines, List<ReviewedRange> insertedRows) {
        int[] owners = new int[currentLines.text().size()];
        java.util.Arrays.fill(owners, -1);
        int oldEnd = offset + oldLength;
        int delta = newLength - oldLength;
        for (int line = 0; line < oldLines.text().size(); line++) {
            int start = windowStart + oldLines.starts()[line];
            int end = windowStart + oldLines.end(line);
            int contentEnd = oldLines.text().get(line).endsWith("\n") && oldLines.text().get(line).length() > 1 ? end - 1 : end;
            retainOrigin(owners, currentLines, windowStart, line, start, Math.min(contentEnd, offset), 0);
            retainOrigin(owners, currentLines, windowStart, line, Math.max(start, oldEnd), contentEnd, delta);
        }
        var inserted = new HashSet<>(insertedRows);
        var candidates = new HashMap<Integer, Integer>();
        var conflicted = new HashSet<Integer>();
        for (int line = 0; line < owners.length; line++) {
            if (owners[line] == -2) { conflicted.add(line); continue; }
            if (owners[line] < 0 || inserted.contains(new ReviewedRange(windowStart + currentLines.starts()[line],
                    windowStart + currentLines.end(line)))) continue;
            int source = owners[line];
            if (oldLines.text().get(source).equals(currentLines.text().get(line))) {
                candidates.merge(source, line, (first, second) -> -1);
            }
        }
        candidates.values().removeIf(line -> line < 0);
        return new KnownRows(Map.copyOf(candidates), Set.copyOf(conflicted));
    }

    private static void retainOrigin(int[] owners, LineTable current, int windowStart, int oldLine,
            int start, int end, int delta) {
        if (start >= end) return;
        int first = current.lineAt(start + delta - windowStart);
        int last = current.lineAt(end + delta - windowStart - 1);
        for (int line = Math.max(0, first); line <= last && line < owners.length; line++) {
            if (owners[line] == -1) owners[line] = oldLine;
            else if (owners[line] != oldLine) owners[line] = -2;
        }
    }

    record KnownRows(Map<Integer, Integer> exactRows, Set<Integer> conflictedRows) { }

    private static void validateEdit(String previous, String current, int offset, int oldLength, int newLength) {
        if (offset < 0 || oldLength < 0 || newLength < 0
                || (long) offset + oldLength > previous.length()
                || (long) offset + newLength > current.length()
                || (long) previous.length() - oldLength + newLength != current.length()) {
            throw new IllegalArgumentException("Invalid document edit coordinates");
        }
        int suffix = previous.length() - offset - oldLength;
        if (!previous.regionMatches(0, current, 0, offset)
                || !previous.regionMatches(offset + oldLength, current, offset + newLength, suffix)) {
            throw new IllegalArgumentException("Snapshots do not match the supplied document edit");
        }
    }
}
