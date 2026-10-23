package dev.doreview.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Reconciles persisted review marks when a file changed while the IDE was closed.
 * Each reviewed line survives independently when it has an unambiguous exact
 * match in the current text. A changed line loses its coverage while unchanged
 * lines from the same compressed span survive. A change outside a partial-line
 * selection invalidates that row conservatively. Inserted rows begin unreviewed.
 *
 * <p>Unique matching lines provide ordered anchors. Ambiguous anchor order is
 * discarded, linear subsequence matching handles pure insertions/deletions, and
 * bounded LCS calculations reconcile the remaining small gaps.
 * Only line pairs required by every optimal matching survive. Unsolved large or
 * ambiguous gaps lose their marks conservatively. This bounds memory and avoids
 * quadratic work over a large file. Lines include their terminating newline, so
 * a line-ending change is an actual change. Callers should supply the same newline
 * normalization for both snapshots, normally IntelliJ's LF representation.
 *
 * <p>This class keeps no history: callers must persist the returned marks with the
 * current snapshot, so subsequent undo or external edits cannot resurrect marks.
 */
public final class SnapshotReconciler {
    private static final long MAX_GAP_CELLS = 1_000_000;

    private SnapshotReconciler() {
    }

    public static List<ReviewedRange> reconcile(
            String previous, String current, List<ReviewedRange> marks) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        var reviewed = ReviewRanges.normalized(marks);
        for (var mark : reviewed) {
            if (mark.end() > previous.length()) {
                throw new IllegalArgumentException("Reviewed range exceeds the previous snapshot");
            }
        }
        if (reviewed.isEmpty() || previous.equals(current)) {
            return reviewed;
        }

        var oldLines = LineTable.from(previous);
        var newLines = LineTable.from(current);
        var mapping = map(previous, current);

        var result = new ArrayList<ReviewedRange>();
        for (var mark : reviewed) {
            int first = oldLines.lineAt(mark.start());
            int last = oldLines.lineAt(mark.end() - 1);
            for (int oldLine = first; oldLine <= last; oldLine++) {
                int newLine = mapping.currentLine(oldLine);
                if (newLine < 0 || !oldLines.text().get(oldLine).equals(newLines.text().get(newLine))) {
                    continue;
                }
                int start = Math.max(mark.start(), oldLines.starts()[oldLine]);
                int end = Math.min(mark.end(), oldLines.starts()[oldLine] + oldLines.text().get(oldLine).length());
                int newStart = newLines.starts()[newLine] + start - oldLines.starts()[oldLine];
                // Verify exact text independently of all matching heuristics.
                if (current.regionMatches(newStart, previous, start, end - start)) {
                    result.add(new ReviewedRange(newStart, newStart + end - start));
                }
            }
        }
        return ReviewRanges.normalized(result);
    }

    public enum ChangeReason { TEXT_CHANGED, AMBIGUOUS, DIFF_LIMIT, DELETED }

    /** Half-open logical row intervals; current interval is empty for a deletion. */
    public record Change(int oldStartLine, int oldEndLine, int currentStartLine,
                         int currentEndLine, ChangeReason reason) {
    }

    /** Exact monotone pairs plus explicitly classified unsolved/changed gaps. */
    public record Mapping(List<Integer> exactLines, List<Change> changes) {
        public Mapping {
            exactLines = List.copyOf(exactLines);
            changes = List.copyOf(changes);
        }

        public int currentLine(int oldLine) {
            return exactLines.get(oldLine);
        }

        public Change changeForOldLine(int oldLine) {
            int low = 0;
            int high = changes.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (changes.get(middle).oldStartLine() <= oldLine) low = middle + 1;
                else high = middle;
            }
            if (low == 0) return null;
            var change = changes.get(low - 1);
            return oldLine < change.oldEndLine() ? change : null;
        }
    }

    /** Bounded line correspondence shared by live provenance and history projection. */
    public static Mapping map(String previous, String current) {
        return map(previous, current, () -> { });
    }

    public static Mapping map(String previous, String current, Runnable cancellationCheck) {
        return map(previous, current, ProjectionWorkBudget.defaults(), cancellationCheck);
    }

    /** Shares the quadratic allowance with other snapshots in one caller operation. */
    public static Mapping map(String previous, String current, ProjectionWorkBudget workBudget,
            Runnable cancellationCheck) {
        cancellationCheck.run();
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(workBudget, "workBudget");
        return map(SnapshotIndex.prepare(previous, cancellationCheck),
                SnapshotIndex.prepare(current, cancellationCheck), workBudget, cancellationCheck);
    }

    static Mapping map(SnapshotIndex previous, SnapshotIndex current, ProjectionWorkBudget workBudget,
            Runnable cancellationCheck) {
        cancellationCheck.run();
        var oldLines = previous.lines();
        var newLines = current.lines();
        int[] mapping = new int[oldLines.text().size()];
        Arrays.fill(mapping, -1);
        var budget = new DiffBudget(workBudget, cancellationCheck);
        int oldGapStart = 0;
        int newGapStart = 0;
        for (var anchor : mandatoryUniqueAnchors(previous, current)) {
            cancellationCheck.run();
            mapGap(oldLines.text(), newLines.text(), oldGapStart, anchor.oldLine(),
                    newGapStart, anchor.newLine(), mapping, budget);
            mapping[anchor.oldLine()] = anchor.newLine();
            oldGapStart = anchor.oldLine() + 1;
            newGapStart = anchor.newLine() + 1;
        }
        mapGap(oldLines.text(), newLines.text(), oldGapStart, oldLines.text().size(),
                newGapStart, newLines.text().size(), mapping, budget);
        var changes = new ArrayList<Change>();
        int oldCursor = 0;
        int newCursor = 0;
        for (int old = 0; old < mapping.length; old++) {
            if ((old & 255) == 0) cancellationCheck.run();
            if (mapping[old] >= 0) {
                addChange(changes, oldLines.text(), newLines.text(), oldCursor, old,
                        newCursor, mapping[old], budget);
                oldCursor = old + 1;
                newCursor = mapping[old] + 1;
            }
        }
        addChange(changes, oldLines.text(), newLines.text(), oldCursor, mapping.length,
                newCursor, newLines.text().size(), budget);
        return new Mapping(Arrays.stream(mapping).boxed().toList(), changes);
    }

    private static void addChange(List<Change> result, List<String> oldLines, List<String> newLines,
            int oldStart, int oldEnd, int newStart, int newEnd, DiffBudget budget) {
        if (oldStart == oldEnd) return;
        ChangeReason reason;
        if (newStart == newEnd) {
            reason = ChangeReason.DELETED;
        } else if (budget.limited.stream().anyMatch(range -> range[0] < oldEnd && range[1] > oldStart)) {
            reason = ChangeReason.DIFF_LIMIT;
        } else {
            var oldText = new HashSet<>(oldLines.subList(oldStart, oldEnd));
            reason = oldEnd - oldStart != 1 || newEnd - newStart != 1
                    || newLines.subList(newStart, newEnd).stream().anyMatch(oldText::contains)
                    ? ChangeReason.AMBIGUOUS : ChangeReason.TEXT_CHANGED;
        }
        result.add(new Change(oldStart, oldEnd, newStart, newEnd, reason));
    }

    /** Keeps only anchors present in every longest increasing anchor sequence. */
    private static List<Anchor> mandatoryUniqueAnchors(SnapshotIndex previous, SnapshotIndex current) {
        var oldLines = previous.lines().text();
        var candidates = new ArrayList<Anchor>();
        for (int oldLine = 0; oldLine < oldLines.size(); oldLine++) {
            var oldOccurrence = previous.occurrence(oldLines.get(oldLine));
            var newOccurrence = current.occurrence(oldLines.get(oldLine));
            if (oldOccurrence.count() == 1 && newOccurrence != null && newOccurrence.count() == 1) {
                candidates.add(new Anchor(oldLine, newOccurrence.index()));
            }
        }
        int size = candidates.size();
        if (size == 0) {
            return List.of();
        }
        int[] forward = new int[size];
        int[] backward = new int[size];
        int[] tails = new int[size];
        int longest = 0;
        for (int i = 0; i < size; i++) {
            int value = candidates.get(i).newLine();
            int rank = lowerBound(tails, longest, value);
            tails[rank] = value;
            forward[i] = rank + 1;
            longest = Math.max(longest, rank + 1);
        }
        int reverseLength = 0;
        for (int i = size - 1; i >= 0; i--) {
            int value = -candidates.get(i).newLine();
            int rank = lowerBound(tails, reverseLength, value);
            tails[rank] = value;
            backward[i] = rank + 1;
            reverseLength = Math.max(reverseLength, rank + 1);
        }
        int[] countAtRank = new int[longest + 1];
        int[] candidateAtRank = new int[longest + 1];
        for (int i = 0; i < size; i++) {
            if (forward[i] + backward[i] - 1 == longest) {
                int rank = forward[i];
                countAtRank[rank]++;
                candidateAtRank[rank] = i;
            }
        }
        var result = new ArrayList<Anchor>();
        for (int rank = 1; rank <= longest; rank++) {
            if (countAtRank[rank] == 1) {
                result.add(candidates.get(candidateAtRank[rank]));
            }
        }
        return result;
    }

    private static void mapGap(List<String> oldLines, List<String> newLines,
            int oldStart, int oldEnd, int newStart, int newEnd, int[] mapping, DiffBudget budget) {
        int oldCount = oldEnd - oldStart;
        int newCount = newEnd - newStart;
        if (oldCount == 0 || newCount == 0) {
            return;
        }
        if (oldCount == newCount
                && oldLines.subList(oldStart, oldEnd).equals(newLines.subList(newStart, newEnd))) {
            for (int i = 0; i < oldCount; i++) {
                mapping[oldStart + i] = newStart + i;
            }
            return;
        }
        if (oldCount <= newCount) {
            if (mapSubsequence(oldLines, newLines, oldStart, oldEnd, newStart, newEnd, mapping, true)) {
                return;
            }
        } else if (mapSubsequence(newLines, oldLines, newStart, newEnd, oldStart, oldEnd, mapping, false)) {
            return;
        }
        long cells = (oldCount + 1L) * (newCount + 1L);
        if (cells > MAX_GAP_CELLS || !budget.workBudget.tryReserveDiffCells(cells)) {
            budget.limited.add(new int[]{oldStart, oldEnd});
            return;
        }
        int columns = newCount + 1;
        int[] forward = new int[(int) cells];
        int[] backward = new int[(int) cells];
        for (int old = 1; old <= oldCount; old++) {
            if ((old & 63) == 0) budget.cancellationCheck.run();
            for (int now = 1; now <= newCount; now++) {
                int at = old * columns + now;
                forward[at] = oldLines.get(oldStart + old - 1).equals(newLines.get(newStart + now - 1))
                        ? forward[(old - 1) * columns + now - 1] + 1
                        : Math.max(forward[(old - 1) * columns + now], forward[at - 1]);
            }
        }
        for (int old = oldCount - 1; old >= 0; old--) {
            if ((old & 63) == 0) budget.cancellationCheck.run();
            for (int now = newCount - 1; now >= 0; now--) {
                int at = old * columns + now;
                backward[at] = oldLines.get(oldStart + old).equals(newLines.get(newStart + now))
                        ? backward[(old + 1) * columns + now + 1] + 1
                        : Math.max(backward[(old + 1) * columns + now], backward[at + 1]);
            }
        }
        int longest = forward[oldCount * columns + newCount];
        int[] countAtRank = new int[longest + 1];
        int[] oldAtRank = new int[longest + 1];
        int[] newAtRank = new int[longest + 1];
        for (int old = 0; old < oldCount; old++) {
            if ((old & 63) == 0) budget.cancellationCheck.run();
            for (int now = 0; now < newCount; now++) {
                int before = forward[old * columns + now];
                if (before + 1 + backward[(old + 1) * columns + now + 1] == longest
                        && oldLines.get(oldStart + old).equals(newLines.get(newStart + now))) {
                    int rank = before + 1;
                    countAtRank[rank]++;
                    oldAtRank[rank] = oldStart + old;
                    newAtRank[rank] = newStart + now;
                }
            }
        }
        for (int rank = 1; rank <= longest; rank++) {
            if (countAtRank[rank] == 1) {
                mapping[oldAtRank[rank]] = newAtRank[rank];
            }
        }
    }

    /**
     * If one gap is a subsequence of the other, every optimal alignment contains
     * every line in the smaller gap. Earliest and latest greedy alignments agree
     * exactly for its mandatory matches. This handles huge repetitive files with
     * unambiguous inserted/deleted lines without allocating any diff matrix.
     */
    private static boolean mapSubsequence(List<String> smaller, List<String> larger,
            int smallStart, int smallEnd, int largeStart, int largeEnd, int[] mapping, boolean oldIsSmaller) {
        int count = smallEnd - smallStart;
        int[] earliest = new int[count];
        int cursor = largeStart;
        for (int i = 0; i < count; i++) {
            while (cursor < largeEnd && !smaller.get(smallStart + i).equals(larger.get(cursor))) {
                cursor++;
            }
            if (cursor == largeEnd) {
                return false;
            }
            earliest[i] = cursor++;
        }
        cursor = largeEnd - 1;
        for (int i = count - 1; i >= 0; i--) {
            while (!smaller.get(smallStart + i).equals(larger.get(cursor))) {
                cursor--;
            }
            if (earliest[i] == cursor) {
                if (oldIsSmaller) {
                    mapping[smallStart + i] = cursor;
                } else {
                    mapping[cursor] = smallStart + i;
                }
            }
            cursor--;
        }
        return true;
    }

    private static int lowerBound(int[] sorted, int size, int value) {
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] < value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private record Anchor(int oldLine, int newLine) {
    }

    private static final class DiffBudget {
        private final ProjectionWorkBudget workBudget;
        private final List<int[]> limited = new ArrayList<>();
        private final Runnable cancellationCheck;

        private DiffBudget(ProjectionWorkBudget workBudget, Runnable cancellationCheck) {
            this.workBudget = workBudget;
            this.cancellationCheck = cancellationCheck;
        }
    }

}
