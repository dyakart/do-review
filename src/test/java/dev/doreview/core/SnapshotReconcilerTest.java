package dev.doreview.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Dependency-free regression suite for changes made while the IDE was closed. */
public final class SnapshotReconcilerTest {
    private static int checks;

    private SnapshotReconcilerTest() {
    }

    public static void main(String[] args) {
        unchangedAndLineEdits();
        duplicateAndReorderCases();
        largeFiles();
        randomUniqueLineEdits();
        System.out.println("SnapshotReconcilerTest: " + checks + " checks passed");
    }

    private static void unchangedAndLineEdits() {
        String previous = "header\nreviewed first\nunreviewed gap\nreviewed last\nfooter\n";
        var first = range(previous, "reviewed first\n");
        var last = range(previous, "reviewed last\n");
        var marks = List.of(first, last);
        equal(marks, SnapshotReconciler.reconcile(previous, previous, marks), "identical snapshot");
        equal(List.of(), SnapshotReconciler.reconcile("", "new\n", List.of()), "empty previous snapshot");
        equal(List.of(), SnapshotReconciler.reconcile(previous, "", marks), "file emptied");

        String prefixed = "inserted\n" + previous;
        equal(List.of(shift(first, 9), shift(last, 9)),
                SnapshotReconciler.reconcile(previous, prefixed, marks), "insert before both marks");
        equal(marks, SnapshotReconciler.reconcile(previous, previous + "appended\n", marks),
                "insert after both marks");
        String adjacent = previous.replace("reviewed first\n", "inserted\nreviewed first\n");
        equal(List.of(shift(first, 9), shift(last, 9)),
                SnapshotReconciler.reconcile(previous, adjacent, marks), "insert immediately before mark");
        String gapChanged = previous.replace("unreviewed gap\n", "a longer unreviewed gap\n");
        equal(List.of(first, shift(last, 9)), SnapshotReconciler.reconcile(previous, gapChanged, marks),
                "unreviewed line changed");
        String firstChanged = previous.replace("reviewed first\n", "reviewed CHANGED\n");
        equal(List.of(shift(last, 2)), SnapshotReconciler.reconcile(previous, firstChanged, marks),
                "only changed mark invalidates");
        String deletedPrefix = previous.substring("header\n".length());
        equal(List.of(shift(first, -7), shift(last, -7)),
                SnapshotReconciler.reconcile(previous, deletedPrefix, marks), "delete line before marks");
        String deletedFirst = previous.replace("reviewed first\n", "");
        equal(List.of(shift(last, -15)), SnapshotReconciler.reconcile(previous, deletedFirst, marks),
                "delete marked line");
        String deletedGap = previous.replace("unreviewed gap\n", "");
        equal(List.of(new ReviewedRange(first.start(), last.end() - 15)),
                SnapshotReconciler.reconcile(previous, deletedGap, marks),
                "delete unreviewed line");

        String multi = "begin\nalpha\nbeta\nend\n";
        var block = range(multi, "alpha\nbeta\n");
        equal(List.of(range(multi, "alpha\n")),
                SnapshotReconciler.reconcile(multi, multi.replace("beta\n", "changed\n"), List.of(block)),
                "one changed line clears only that row of a merged span");
        equal(List.of(range(multi, "alpha\n"), shift(range(multi, "beta\n"), 9)),
                SnapshotReconciler.reconcile(multi, multi.replace("beta\n", "inserted\nbeta\n"), List.of(block)),
                "insert inside merged span preserves both old rows");
        var partial = range(multi, "lph");
        equal(List.of(), SnapshotReconciler.reconcile(multi, multi.replace("alpha", "alpha suffix"), List.of(partial)),
                "change outside partial selection on same line invalidates conservatively");
        equal(List.of(shift(partial, 4)), SnapshotReconciler.reconcile(multi, "new\n" + multi, List.of(partial)),
                "partial selection offsets shift exactly");
        equal(List.of(), SnapshotReconciler.reconcile("last", "last\n", List.of(new ReviewedRange(0, 4))),
                "added final newline changes the marked line");
        equal(List.of(new ReviewedRange(5, 9)),
                SnapshotReconciler.reconcile("last", "head\nlast", List.of(new ReviewedRange(0, 4))),
                "last line without newline shifts");
        String unicode = "start\nreviewed \uD83D\uDE80 text\nfinish\n";
        var unicodeMark = range(unicode, "reviewed \uD83D\uDE80 text\n");
        equal(List.of(shift(unicodeMark, 3)),
                SnapshotReconciler.reconcile(unicode, "\uD83D\uDE80\n" + unicode, List.of(unicodeMark)),
                "UTF-16 offsets are preserved");
        var invalidated = SnapshotReconciler.reconcile(multi, multi.replace("alpha", "changed"), List.of(block));
        equal(List.of(range(multi, "beta\n")),
                SnapshotReconciler.reconcile(multi.replace("alpha", "changed"), multi, invalidated),
                "offline undo never resurrects the changed row");
        try {
            SnapshotReconciler.reconcile("a", "b", List.of(new ReviewedRange(0, 2)));
            throw new AssertionError("out of bounds persisted mark should be rejected");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void duplicateAndReorderCases() {
        String previous = "head\nsame\nsame\ntail\n";
        var duplicates = range(previous, "same\nsame\n");
        equal(List.of(), SnapshotReconciler.reconcile(previous, "head\nsame\nsame\nsame\ntail\n",
                List.of(duplicates)), "ambiguous duplicate insertion invalidates");
        equal(List.of(), SnapshotReconciler.reconcile(previous, "head\nsame\ntail\n",
                List.of(duplicates)), "ambiguous duplicate deletion invalidates");
        equal(List.of(shift(duplicates, 6)),
                SnapshotReconciler.reconcile(previous, "intro\n" + previous, List.of(duplicates)),
                "identical duplicate gap between stable anchors survives");
        String reordered = "head\nalpha\nbeta\ntail\n";
        equal(List.of(), SnapshotReconciler.reconcile(reordered, "head\nbeta\nalpha\ntail\n",
                List.of(range(reordered, "alpha\n"), range(reordered, "beta\n"))),
                "ambiguous swapped unique lines both invalidate");
        String gap = "head\nx\nrepeat\nx\ntail\n";
        var repeated = range(gap, "repeat\n");
        equal(List.of(shift(repeated, 2)),
                SnapshotReconciler.reconcile(gap, "head\nx\nx\nrepeat\nx\ntail\n", List.of(repeated)),
                "unique line survives beside ambiguous duplicate lines");
        String fallback = "same\nold\nsame\n";
        equal(List.of(new ReviewedRange(0, 5), new ReviewedRange(9, 14)),
                SnapshotReconciler.reconcile(fallback, "same\nnew\nsame\n",
                        List.of(new ReviewedRange(0, 5), new ReviewedRange(9, 14))),
                "bounded LCS preserves mandatory repeated matches without unique anchors");
        String methods = "class Example {\n  void first() {\n    work(1);\n  }\n\n"
                + "  void second() {\n    work(2);\n  }\n}\n";
        var firstMethod = range(methods, "  void first() {\n    work(1);\n  }\n");
        var secondMethod = range(methods, "  void second() {\n    work(2);\n  }\n");
        equal(List.of(range(methods, "  void first() {\n"), range(methods, "  }\n"), secondMethod),
                SnapshotReconciler.reconcile(methods, methods.replace("work(1)", "work(3)"),
                        List.of(firstMethod, secondMethod)),
                "common braces and other unchanged method rows survive independently");
    }

    private static void largeFiles() {
        var source = new StringBuilder();
        for (int line = 0; line < 20_000; line++) {
            source.append("unique source line ").append(line).append('\n');
        }
        String previous = source.toString();
        var before = range(previous, "unique source line 100\n");
        var after = range(previous, "unique source line 19000\n");
        int insertion = previous.indexOf("unique source line 10000\n");
        String current = previous.substring(0, insertion) + "inserted offline\n" + previous.substring(insertion);
        equal(List.of(before, shift(after, "inserted offline\n".length())),
                SnapshotReconciler.reconcile(previous, current, List.of(before, after)),
                "large file simple insertion does not require quadratic diff");
        String repeated = "same\n".repeat(2_000);
        equal(List.of(), SnapshotReconciler.reconcile(repeated, "same\n" + repeated,
                List.of(new ReviewedRange(0, repeated.length()))),
                "large ambiguous gap invalidates without quadratic matrix");
        equal(List.of(new ReviewedRange(9, repeated.length() + 9)),
                SnapshotReconciler.reconcile(repeated, "inserted\n" + repeated,
                        List.of(new ReviewedRange(0, repeated.length()))),
                "large repetitive file survives unambiguous prefix insertion");
        int middle = repeated.length() / 2;
        String middleInsertion = repeated.substring(0, middle) + "inserted\n" + repeated.substring(middle);
        equal(List.of(new ReviewedRange(middle + 9, middleInsertion.length())),
                SnapshotReconciler.reconcile(repeated, middleInsertion,
                        List.of(new ReviewedRange(middle, repeated.length()))),
                "large repetitive suffix survives unambiguous middle insertion");
        equal(List.of(new ReviewedRange(0, repeated.length())),
                SnapshotReconciler.reconcile("deleted\n" + repeated, repeated,
                        List.of(new ReviewedRange(8, repeated.length() + 8))),
                "large repetitive file survives unambiguous prefix deletion");
    }

    /** Independent token identity model for random single-line offline edits. */
    private static void randomUniqueLineEdits() {
        var random = new Random(0x4f46464c494e45L);
        for (int iteration = 0; iteration < 500; iteration++) {
            var oldLines = new ArrayList<String>();
            for (int i = 0; i < 30; i++) {
                oldLines.add("original " + iteration + "/" + i + "\n");
            }
            String previous = String.join("", oldLines);
            int markedIndex = random.nextInt(oldLines.size());
            var mark = range(previous, oldLines.get(markedIndex));
            var newLines = new ArrayList<>(oldLines);
            int editIndex = random.nextInt(newLines.size());
            int operation = random.nextInt(3);
            boolean removedMark = operation != 0 && editIndex == markedIndex;
            if (operation == 0) {
                newLines.add(editIndex, "inserted " + iteration + "\n");
            } else if (operation == 1) {
                newLines.remove(editIndex);
            } else {
                newLines.set(editIndex, "replaced " + iteration + "\n");
            }
            String current = String.join("", newLines);
            var expected = removedMark ? List.<ReviewedRange>of()
                    : List.of(range(current, oldLines.get(markedIndex)));
            equal(expected, SnapshotReconciler.reconcile(previous, current, List.of(mark)),
                    "random unique-line edit " + iteration);
        }
    }

    private static ReviewedRange range(String text, String selected) {
        int start = text.indexOf(selected);
        if (start < 0) {
            throw new AssertionError("selection missing from fixture: " + selected);
        }
        return new ReviewedRange(start, start + selected.length());
    }

    private static ReviewedRange shift(ReviewedRange range, int delta) {
        return new ReviewedRange(range.start() + delta, range.end() + delta);
    }

    private static void equal(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
        checks++;
    }
}
