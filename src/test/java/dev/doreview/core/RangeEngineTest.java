package dev.doreview.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Dependency-free regression suite; run its main method without enabling -ea. */
public final class RangeEngineTest {
    private static int checks;

    private RangeEngineTest() {
    }

    public static void main(String[] args) {
        markAndClearCases();
        validationAndImmutability();
        randomizedCoverageCases();
        System.out.println("RangeEngineTest: " + checks + " checks passed");
    }

    private static void markAndClearCases() {
        equal(List.of(r(5, 10)), ReviewRanges.mark(List.of(), 5, 10), "first mark");
        equal(List.of(r(5, 10)), ReviewRanges.mark(List.of(r(5, 10)), 6, 9), "contained mark");
        equal(List.of(r(2, 15)), ReviewRanges.mark(List.of(r(5, 10)), 2, 15), "enclosing mark");
        equal(List.of(r(5, 12)), ReviewRanges.mark(List.of(r(5, 10)), 8, 12), "overlap");
        equal(List.of(r(5, 12)), ReviewRanges.mark(List.of(r(5, 10)), 10, 12), "adjacency");
        equal(List.of(r(0, 15)), ReviewRanges.mark(List.of(r(0, 5), r(10, 15)), 5, 10), "bridge");
        equal(List.of(r(0, 2), r(5, 8), r(10, 12)),
                ReviewRanges.mark(List.of(r(10, 12), r(0, 2)), 5, 8), "sorted disjoint marks");
        equal(List.of(r(0, 12)), ReviewRanges.mark(List.of(r(8, 12), r(0, 5)), 4, 9),
                "normalizes unsorted overlapping marks");

        var base = List.of(r(5, 15));
        equal(List.of(r(5, 8), r(12, 15)), ReviewRanges.clear(base, 8, 12), "clear splits");
        equal(List.of(r(10, 15)), ReviewRanges.clear(base, 0, 10), "clear prefix");
        equal(List.of(r(5, 10)), ReviewRanges.clear(base, 10, 20), "clear suffix");
        equal(List.of(), ReviewRanges.clear(base, 0, 20), "clear enclosing interval");
        equal(List.of(), ReviewRanges.clear(base, 5, 15), "clear exact interval");
        equal(base, ReviewRanges.clear(base, 0, 5), "clear touching left");
        equal(base, ReviewRanges.clear(base, 15, 20), "clear touching right");
        equal(base, ReviewRanges.clear(base, 10, 10), "empty clear");
        equal(List.of(r(0, 2), r(18, 20)),
                ReviewRanges.clear(List.of(r(0, 5), r(8, 12), r(15, 20)), 2, 18),
                "clear across multiple marks");
    }

    private static void validationAndImmutability() {
        invalid(() -> r(-1, 2), "negative range start");
        invalid(() -> r(1, 1), "empty mark");
        invalid(() -> r(2, 1), "reversed range");
        invalid(() -> ReviewRanges.mark(List.of(), 0, 0), "cannot mark empty selection");
        invalid(() -> ReviewRanges.clear(List.of(), -1, 1), "negative clear offset");
        invalid(() -> ReviewRanges.clear(List.of(), 2, 1), "reversed clear");
        var mutable = new ArrayList<>(List.of(r(5, 10)));
        var result = ReviewRanges.mark(mutable, 10, 12);
        equal(List.of(r(5, 10)), mutable, "input list unchanged");
        mutable.clear();
        equal(List.of(r(5, 12)), result, "output independent of input list");
        try {
            result.add(r(15, 20));
            throw new AssertionError("output should be immutable");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }
    }

    /** Independent bitmap model verifies union and subtraction over many histories. */
    private static void randomizedCoverageCases() {
        var random = new Random(0x524556494557L);
        var coverage = new boolean[64];
        List<ReviewedRange> ranges = List.of();
        for (int step = 0; step < 2_000; step++) {
            int start = random.nextInt(coverage.length);
            int end = start + 1 + random.nextInt(coverage.length - start);
            boolean mark = random.nextBoolean();
            ranges = mark ? ReviewRanges.mark(ranges, start, end) : ReviewRanges.clear(ranges, start, end);
            for (int i = start; i < end; i++) {
                coverage[i] = mark;
            }
            var actual = new boolean[coverage.length];
            int previousEnd = -1;
            for (var range : ranges) {
                if (range.start() <= previousEnd) {
                    throw new AssertionError("result contains overlapping or adjacent ranges");
                }
                previousEnd = range.end();
                for (int i = range.start(); i < range.end(); i++) {
                    actual[i] = true;
                }
            }
            if (!java.util.Arrays.equals(coverage, actual)) {
                throw new AssertionError("bitmap mismatch after operation " + step);
            }
            checks++;
        }
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
