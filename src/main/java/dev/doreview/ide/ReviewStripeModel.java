package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.impl.EditorImpl;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ReviewedRange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable review spans rasterized in minimap coordinates. */
final class ReviewStripeModel {
    /** Later values win when several logical markers share a pixel (for example inside a fold). */
    enum Kind { REVIEWED, NEEDS_REVIEW, CHANGED, DELETED }

    record Snapshot(long stamp, List<Run> runs) {
        Snapshot { runs = List.copyOf(runs); }
    }

    record Run(int start, int end, Kind kind) {
        Run {
            Objects.requireNonNull(kind, "kind");
            if (start < 0 || end < start || kind == Kind.DELETED && start != end) {
                throw new IllegalArgumentException("Invalid review stripe span");
            }
        }
    }

    record Band(int y, int height, Kind kind) {
        Band {
            Objects.requireNonNull(kind, "kind");
            if (y < 0 || height < 1) throw new IllegalArgumentException("Invalid review stripe band");
        }
    }

    /** Native map pixels, before CodeGlance's own graphics scale is applied. */
    interface Mapping {
        int top();
        int height();
        double scale();
        /** Pairs of [from, until) native pixels; CodeGlance may rasterize the ends separately. */
        int[] rows(int firstVisual, int lastVisual);
        int visualLine(int y);
    }

    private ReviewStripeModel() { }

    static Snapshot capture(FileProjection projection, long stamp) {
        var runs = new ArrayList<Run>();
        append(runs, projection.reviewedRanges(), Kind.REVIEWED);
        append(runs, projection.yellowRanges(), Kind.NEEDS_REVIEW);
        append(runs, projection.changedRanges(), Kind.CHANGED);
        projection.deletions().stream().map(FileProjection.DeletedRow::currentOffset).distinct()
                .forEach(offset -> runs.add(new Run(offset, offset, Kind.DELETED)));
        runs.sort(Comparator.comparingInt(Run::start).thenComparingInt(Run::end).thenComparing(Run::kind));
        return new Snapshot(stamp, runs);
    }

    private static void append(List<Run> result, List<ReviewedRange> ranges, Kind kind) {
        Run prior = null;
        for (var range : ranges.stream().sorted(Comparator.comparingInt(ReviewedRange::start)).toList()) {
            if (range.start() == range.end()) continue;
            if (prior != null && range.start() <= prior.end()) {
                prior = new Run(prior.start(), Math.max(prior.end(), range.end()), kind);
                result.set(result.size() - 1, prior);
            } else {
                prior = new Run(range.start(), range.end(), kind);
                result.add(prior);
            }
        }
    }

    static List<Band> bands(Editor editor, Snapshot snapshot, int height) {
        return bands(editor, snapshot, overview(editor, 0, height));
    }

    static List<Band> bands(Editor editor, Snapshot snapshot, Mapping mapping) {
        if (mapping.height() <= 0 || editor.isDisposed() || snapshot.stamp() != editor.getDocument().getModificationStamp()) return List.of();
        int height = (int) Math.ceil(mapping.height() / mapping.scale());
        int length = editor.getDocument().getTextLength();
        CharSequence text = editor.getDocument().getCharsSequence();
        var pixels = new Kind[height];
        for (var run : snapshot.runs()) {
            int start = Math.min(run.start(), length), end = Math.min(run.end(), length);
            if (run.kind() != Kind.DELETED && start == end) continue;
            int firstLine = editor.offsetToVisualPosition(start).line;
            if (run.kind() == Kind.DELETED) {
                int[] row = mapping.rows(firstLine, firstLine);
                if (row[1] <= row[0]) continue;
                paint(pixels, row[0], row[0] + 2, run.kind());
            } else {
                // A whole-row range includes LF; its exclusive end must not paint the next row.
                if (end > start && text.charAt(end - 1) == '\n') end--;
                int last = Math.max(start, end - 1);
                int lastLine = editor.offsetToVisualPosition(last).line;
                int[] rows = mapping.rows(firstLine, lastLine);
                for (int index = 0; index + 1 < rows.length; index += 2) paint(pixels, rows[index], rows[index + 1], run.kind());
            }
        }
        var result = new ArrayList<Band>();
        for (int y = 0; y < height; ) {
            Kind kind = pixels[y];
            int end = y + 1;
            while (end < height && pixels[end] == kind) end++;
            if (kind != null) result.add(new Band(y, end - y, kind));
            y = end;
        }
        return List.copyOf(result);
    }

    private static void paint(Kind[] pixels, int from, int until, Kind kind) {
        for (int pixel = Math.max(0, from), limit = Math.min(pixels.length, until); pixel < limit; pixel++) {
            if (pixels[pixel] == null || kind.ordinal() > pixels[pixel].ordinal()) pixels[pixel] = kind;
        }
    }

    static VisualPosition positionAt(Editor editor, int y, int height) {
        return positionAt(editor, y, overview(editor, 0, height));
    }

    static VisualPosition positionAt(Editor editor, int y, Mapping mapping) {
        if (mapping.height() <= 0 || editor.isDisposed()) return new VisualPosition(0, 0);
        int pixel = Math.max(0, Math.min(y, mapping.height() - 1));
        int line = Math.max(0, Math.min(visualLineCount(editor) - 1, mapping.visualLine((int) (pixel / mapping.scale()))));
        return new VisualPosition(line, 0);
    }

    static Mapping overview(Editor editor, int top, int height) {
        int lineCount = visualLineCount(editor);
        return new Mapping() {
            @Override public int top() { return top; }
            @Override public int height() { return Math.max(0, height); }
            @Override public double scale() { return 1; }
            @Override public int[] rows(int first, int last) {
                int from = (int) ((long) first * height / lineCount);
                int until = (int) (((last + 1L) * height + lineCount - 1) / lineCount);
                return new int[]{from, Math.max(from + 1, until)};
            }
            @Override public int visualLine(int y) { return height <= 0 ? 0 : (int) ((long) y * lineCount / height); }
        };
    }

    private static int visualLineCount(Editor editor) {
        if (editor instanceof EditorImpl implementation) return Math.max(1, implementation.getVisibleLineCount());
        return Math.max(1, editor.offsetToVisualPosition(editor.getDocument().getTextLength()).line + 1);
    }
}
