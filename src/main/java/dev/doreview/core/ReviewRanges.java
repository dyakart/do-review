package dev.doreview.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure operations on reviewed document ranges. Results are immutable, ordered,
 * and contain no overlapping or touching ranges. Offsets use Java's UTF-16 units,
 * matching IntelliJ document offsets.
 *
 * <p>These ranges compress adjacent reviewed lines for storage. Editing behavior
 * is line granular and is implemented by {@link LineReviewTracker} and
 * {@link SnapshotReconciler}; a merged span is not one indivisible review mark.
 */
public final class ReviewRanges {
    private ReviewRanges() {
    }

    /** Adds review coverage, coalescing both overlapping and adjacent marks. */
    public static List<ReviewedRange> mark(List<ReviewedRange> ranges, int start, int end) {
        var combined = new ArrayList<>(Objects.requireNonNull(ranges, "ranges"));
        combined.add(new ReviewedRange(start, end));
        return normalized(combined);
    }

    /**
     * Removes coverage from [start, end), splitting a mark when necessary.
     * An empty selection leaves the coverage unchanged.
     */
    public static List<ReviewedRange> clear(List<ReviewedRange> ranges, int start, int end) {
        validateSelection(start, end);
        var existing = normalized(ranges);
        if (start == end) {
            return existing;
        }
        var result = new ArrayList<ReviewedRange>();
        for (var range : existing) {
            if (end <= range.start() || start >= range.end()) {
                result.add(range);
                continue;
            }
            if (range.start() < start) {
                result.add(new ReviewedRange(range.start(), start));
            }
            if (end < range.end()) {
                result.add(new ReviewedRange(end, range.end()));
            }
        }
        return List.copyOf(result);
    }

    static List<ReviewedRange> normalized(List<ReviewedRange> ranges) {
        Objects.requireNonNull(ranges, "ranges");
        var sorted = new ArrayList<>(ranges);
        for (var range : sorted) {
            Objects.requireNonNull(range, "range");
        }
        sorted.sort(Comparator.comparingInt(ReviewedRange::start).thenComparingInt(ReviewedRange::end));
        var result = new ArrayList<ReviewedRange>();
        for (var range : sorted) {
            if (result.isEmpty()) {
                result.add(range);
                continue;
            }
            var previous = result.get(result.size() - 1);
            if (range.start() <= previous.end()) {
                result.set(result.size() - 1,
                        new ReviewedRange(previous.start(), Math.max(previous.end(), range.end())));
            } else {
                result.add(range);
            }
        }
        return List.copyOf(result);
    }

    private static void validateSelection(int start, int end) {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Expected 0 <= start <= end, got " + start + ".." + end);
        }
    }

}
