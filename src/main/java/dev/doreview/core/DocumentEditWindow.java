package dev.doreview.core;

import java.util.Objects;

/** Complete old/current rows touched by one validated UTF-16 document replacement. */
record DocumentEditWindow(int start, int oldEnd, int newEnd) {
    static DocumentEditWindow forEdit(String previous, String current, int offset, int oldLength, int newLength) {
        validate(previous, current, offset, oldLength, newLength);
        return ofValidatedEdit(previous, current, offset, oldLength, newLength);
    }

    /** Kept separate so callers can preserve their no-op fast paths after validation. */
    static void validate(String previous, String current, int offset, int oldLength, int newLength) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
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

    static DocumentEditWindow ofValidatedEdit(String previous, String current,
            int offset, int oldLength, int newLength) {
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
        // Include the following row when a consumed LF or partial insertion joins it.
        if (newWindowEnd > 0 && newWindowEnd < current.length()
                && current.charAt(newWindowEnd - 1) != '\n') {
            int newline = current.indexOf('\n', newWindowEnd);
            int fullLineEnd = newline < 0 ? current.length() : newline + 1;
            oldWindowEnd += fullLineEnd - newWindowEnd;
            newWindowEnd = fullLineEnd;
        }
        return new DocumentEditWindow(windowStart, oldWindowEnd, newWindowEnd);
    }
}
