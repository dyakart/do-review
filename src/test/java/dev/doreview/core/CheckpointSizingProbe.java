package dev.doreview.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Optional read-only sizing probe; external inputs are never copied into repository fixtures. */
public final class CheckpointSizingProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            var source = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                source.append("fn step_").append(i).append("() {\n  common();\n  if (ready) {\n    work();\n  }\n}\n\n");
            }
            measure("synthetic", source.toString());
        } else {
            for (int i = 0; i < args.length; i++) measure("input-" + (i + 1), Files.readString(Path.of(args[i])));
        }
    }

    private static void measure(String label, String input) {
        String previous = FileSnapshot.create(input).text();
        if (previous.isEmpty()) return;
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), previous,
                List.of(new ReviewedRange(0, previous.length())), 1);
        var exact = ReviewHistoryEngine.project(history, previous, java.util.Map.of(), ContextMap.fallback(previous), null);
        var initial = ProjectionHints.fromProjection(history, exact);
        String prefix = "// inserted locally\n";
        String current = prefix + previous;
        var hints = initial.applyEdit(previous, current, 0, 0, prefix.length());
        for (int i = 0; i < 10; i++) hints.compactForCheckpoint(current);
        long[] elapsed = new long[25];
        ProjectionHints compact = null;
        for (int i = 0; i < elapsed.length; i++) {
            long start = System.nanoTime();
            compact = hints.compactForCheckpoint(current);
            elapsed[i] = System.nanoTime() - start;
        }
        Arrays.sort(elapsed);
        for (int i = 0; i < 10; i++) hints.compactForCheckpoint(current, history);
        long[] settledElapsed = new long[25];
        ProjectionHints settled = null;
        for (int i = 0; i < settledElapsed.length; i++) {
            long started = System.nanoTime();
            settled = hints.compactForCheckpoint(current, history);
            settledElapsed[i] = System.nanoTime() - started;
        }
        Arrays.sort(settledElapsed);
        long start = System.nanoTime();
        var mapping = SnapshotReconciler.map(previous, current);
        long mapNanos = System.nanoTime() - start;
        System.out.printf(java.util.Locale.ROOT,
                "%s rows=%d fullRefs=%d compactRefs=%d compactDebugChars=%d compactMedianMs=%.3f settledRefs=%d settledDebugChars=%d settledMedianMs=%.3f mapMs=%.3f mapped=%d%n",
                label, LineTable.from(previous).text().size(), hints.authoritativeRefs().size(),
                compact.authoritativeRefs().size(), compact.toString().length(), elapsed[elapsed.length / 2] / 1e6,
                settled.authoritativeRefs().size(), settled.toString().length(), settledElapsed[settledElapsed.length / 2] / 1e6,
                mapNanos / 1e6, mapping.exactLines().stream().filter(line -> line >= 0).count());
        int editOffset = 0;
        while (editOffset < previous.length() && Character.isWhitespace(previous.charAt(editOffset))) editOffset++;
        if (editOffset < previous.length()) {
            String edited = previous.substring(0, editOffset) + (previous.charAt(editOffset) == 'X' ? "Y" : "X")
                    + previous.substring(editOffset + 1);
            var editedHints = initial.applyEdit(previous, edited, editOffset, 1, 1);
            var editedCompact = editedHints.compactForCheckpoint(edited, history);
            System.out.printf(java.util.Locale.ROOT, "%s-one-char settledRefs=%d settledDebugChars=%d%n",
                    label, editedCompact.authoritativeRefs().size(), editedCompact.toString().length());
        }
    }
}
