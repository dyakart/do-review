package dev.doreview.core;

/** A reviewed, nonempty half-open range of UTF-16 document offsets. */
public record ReviewedRange(int start, int end) {
    public ReviewedRange {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Expected 0 <= start < end, got " + start + ".." + end);
        }
    }
}
