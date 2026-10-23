package dev.doreview.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Content rows own their LF; a trailing empty logical editor row has no offsets. */
record LineTable(List<String> text, int[] starts) {
    static LineTable from(String value) {
        var lines = new ArrayList<String>();
        var starts = new ArrayList<Integer>();
        int start = 0;
        while (start < value.length()) {
            int newline = value.indexOf('\n', start);
            int end = newline < 0 ? value.length() : newline + 1;
            starts.add(start);
            lines.add(value.substring(start, end));
            start = end;
        }
        return new LineTable(lines, starts.stream().mapToInt(Integer::intValue).toArray());
    }

    int lineAt(int offset) {
        int match = Arrays.binarySearch(starts, offset);
        return match >= 0 ? match : -match - 2;
    }

    int end(int line) {
        return starts[line] + text.get(line).length();
    }

    ReviewedRange range(int line) {
        return new ReviewedRange(starts[line], end(line));
    }
}
