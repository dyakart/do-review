package dev.doreview.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Dependency-free live line identity regressions and independent edit histories. */
public final class LineReviewTrackerTest {
    private static int checks;

    private LineReviewTrackerTest() {
    }

    public static void main(String[] args) {
        boundaryCases();
        duplicateRowsAndBulkEdits();
        validation();
        randomizedHistories();
        largeFile();
        System.out.println("LineReviewTrackerTest: " + checks + " checks passed");
    }

    private static void boundaryCases() {
        String old = "A\nB\nC\n";
        equal(List.of(r(0, 2), r(4, 6)), edit(old, all(old), 2, 1, "X"),
                "edit one row of coalesced span");
        equal(List.of(r(0, 2), r(6, 10)), edit(old, all(old), 2, 0, "new\n"),
                "whole-row insertion retains old rows only");
        equal(List.of(r(0, 4)), edit(old, all(old), 2, 2, ""),
                "whole-row deletion preserves adjacent rows");
        equal(List.of(r(0, 2)), edit(old, all(old), 3, 1, ""),
                "delete LF joins two rows and clears joined row");
        equal(List.of(r(0, 3), r(4, 6)), edit("AB\nC\n", all("AB\nC\n"), 2, 0, "\n"),
                "Enter before an existing LF preserves exact old row and following row");
        equal(List.of(r(0, 2), r(4, 8)), edit(old, all(old), 2, 0, "\n\n"),
                "insert blank complete rows at a boundary");
        equal(List.of(r(0, 2), r(5, 7)), edit(old, all(old), 2, 0, "X"),
                "insert code at row start clears only that row");
        equal(List.of(r(0, 2), r(7, 9)), edit("A\nBCD\nE\n", all("A\nBCD\nE\n"), 3, 0, "\n"),
                "split inside a row clears both fragments");
        equal(List.of(r(0, old.length())), edit(old, all(old), old.length(), 0, "added\n"),
                "append after existing LF preserves prior rows");
        equal(List.of(r(0, 2)), edit("A\nB", all("A\nB"), 3, 0, "X"),
                "append to unterminated row clears that row");
        equal(List.of(r(0, 2)), edit("A\nB", all("A\nB"), 3, 0, "\nX\n"),
                "added LF changes unterminated old row");
        equal(List.of(r(0, 2)), edit("A\nB", all("A\nB"), 2, 0, "X"),
                "insert at final unterminated row start clears that row");
        equal(List.of(r(0, 2)), edit("A\n", all("A\n"), 0, 0, ""), "no-op retains marks");
        equal(List.of(), edit("", List.of(), 0, 0, "new\n"), "empty file insertion is unreviewed");
        equal(List.of(), edit(old, all(old), 0, old.length(), ""), "delete entire document");
        equal(List.of(r(0, 2)), edit("\nA\n", all("\nA\n"), 0, 1, ""),
                "delete entire blank first row preserves next row");
        equal(List.of(r(0, 2)), edit("A\n\n", all("A\n\n"), 2, 1, ""),
                "delete final blank row preserves previous row");
        var changed = edit(old, all(old), 2, 1, "X");
        equal(List.of(r(0, 2), r(4, 6)), edit("A\nX\nC\n", changed, 2, 1, "B"),
                "undo does not resurrect invalidated row");
        equal(List.of(r(0, 2), r(5, 7)),
                edit("A\nB\nC\n", all("A\nB\nC\n"), 2, 1, "\uD83D\uDE80"),
                "UTF-16 offsets shift correctly");
    }

    private static void duplicateRowsAndBulkEdits() {
        String repeated = "same\nsame\nsame\nsame\n";
        equal(List.of(r(0, 5), r(10, 20)), edit(repeated, all(repeated), 5, 1, "X"),
                "known duplicate row modification clears only edited identity");
        equal(List.of(r(0, 5), r(10, 25)), edit(repeated, all(repeated), 5, 0, "same\n"),
                "known identical-row insertion preserves all original identities");
        equal(List.of(r(0, 15)), edit(repeated, all(repeated), 5, 5, ""),
                "known duplicate row deletion preserves other identical identities");
        String block = "head\nfirst\nsecond\nthird\ntail\n";
        String replacement = "FIRST\nsecond\nTHIRD\n";
        equal(List.of(r(0, 5), r(11, 18), r(24, 29)),
                edit(block, all(block), 5, "first\nsecond\nthird\n".length(), replacement),
                "bulk replacement retains exact unchanged interior row");
        equal(List.of(r(1, 3), r(8, 10)),
                edit("abc\nmid\nxyz\n", List.of(r(1, 3), r(4, 8), r(8, 10)), 5, 1, "I"),
                "partial legacy marks survive only on unchanged complete containing rows");
    }

    private static void validation() {
        invalid(() -> LineReviewTracker.applyEdit("A\n", "A\n", all("A\n"), -1, 0, 0), "negative offset");
        invalid(() -> LineReviewTracker.applyEdit("A\n", "A\n", all("A\n"), 0, -1, 0), "negative old length");
        invalid(() -> LineReviewTracker.applyEdit("A\n", "A\n", all("A\n"), 0, 0, -1), "negative new length");
        invalid(() -> LineReviewTracker.applyEdit("A\n", "A\n", all("A\n"), 2, 1, 1), "edit exceeds old text");
        invalid(() -> LineReviewTracker.applyEdit("A\n", "B\n", all("A\n"), 1, 1, 1), "wrong edit coordinates");
        invalid(() -> LineReviewTracker.applyEdit("A\n", "AA\n", all("A\n"), 0, 1, 1), "wrong new length");
        invalid(() -> LineReviewTracker.applyEdit("A\n", "A\n", List.of(r(0, 3)), 0, 0, 0), "mark exceeds snapshot");
    }

    /** Model tracks explicit row identities and flags; duplicate text cannot move marks. */
    private static void randomizedHistories() {
        var random = new Random(0x4c494e4553L);
        for (int history = 0; history < 30; history++) {
            var rows = new ArrayList<Row>();
            for (int i = 0; i < 15; i++) {
                rows.add(new Row("same " + random.nextInt(3) + "\n", true));
            }
            String previous = text(rows);
            List<ReviewedRange> marks = expected(rows);
            for (int step = 0; step < 100; step++) {
                int row = random.nextInt(rows.size());
                int offset = 0;
                for (int i = 0; i < row; i++) {
                    offset += rows.get(i).text().length();
                }
                int operation = random.nextInt(4);
                if (rows.get(row).text().length() < 3 && operation >= 2) {
                    operation = 0;
                }
                String added;
                int removed;
                if (operation == 0) {
                    added = "same " + random.nextInt(3) + "\n";
                    removed = 0;
                    rows.add(row, new Row(added, false));
                } else if (operation == 1 && rows.size() > 1) {
                    added = "";
                    removed = rows.remove(row).text().length();
                } else if (operation == 2) {
                    offset++;
                    added = "X";
                    removed = 1;
                    String old = rows.get(row).text();
                    rows.set(row, new Row(old.substring(0, 1) + "X" + old.substring(2), false));
                } else {
                    offset++;
                    added = "\n";
                    removed = 0;
                    String old = rows.remove(row).text();
                    rows.add(row, new Row(old.substring(1), false));
                    rows.add(row, new Row(old.substring(0, 1) + "\n", false));
                }
                String current = text(rows);
                marks = LineReviewTracker.applyEdit(previous, current, marks, offset, removed, added.length());
                equal(expected(rows), marks, "row identity history " + history + "/" + step);
                previous = current;
            }
        }
    }

    private static void largeFile() {
        String previous = "same\n".repeat(20_000);
        int offset = 50_000;
        equal(List.of(r(0, offset), r(offset + 5, previous.length())),
                edit(previous, all(previous), offset, 1, "X"),
                "large repeated file local edit clears one row without global diff");
        var source = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            source.append("unique ").append(i).append('\n');
        }
        previous = source.toString();
        String current = previous.replace("unique 5000\n", "CHANGED 5000\n");
        int change = previous.indexOf("unique 5000\n");
        equal(List.of(r(0, change), r(change + "CHANGED 5000\n".length(), current.length())),
                LineReviewTracker.applyEdit(previous, current, all(previous), 0, previous.length(), current.length()),
                "large full-buffer replacement preserves other 9999 rows");
    }

    private record Row(String text, boolean reviewed) {
    }

    private static String text(List<Row> rows) {
        var text = new StringBuilder();
        for (var row : rows) {
            text.append(row.text());
        }
        return text.toString();
    }

    private static List<ReviewedRange> expected(List<Row> rows) {
        var spans = new ArrayList<ReviewedRange>();
        int offset = 0;
        for (var row : rows) {
            if (row.reviewed()) {
                spans.add(r(offset, offset + row.text().length()));
            }
            offset += row.text().length();
        }
        return ReviewRanges.normalized(spans);
    }

    private static List<ReviewedRange> edit(String previous, List<ReviewedRange> marks,
            int offset, int removed, String added) {
        String current = previous.substring(0, offset) + added + previous.substring(offset + removed);
        return LineReviewTracker.applyEdit(previous, current, marks, offset, removed, added.length());
    }

    private static List<ReviewedRange> all(String text) {
        return text.isEmpty() ? List.of() : List.of(r(0, text.length()));
    }

    private static ReviewedRange r(int start, int end) {
        return new ReviewedRange(start, end);
    }

    private static void equal(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
        checks++;
    }

    private static void invalid(Runnable operation, String description) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError(description + ": expected IllegalArgumentException");
    }
}
