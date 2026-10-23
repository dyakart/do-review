package dev.doreview.core;

import junit.framework.TestCase;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reproducible synthetic measurements; elapsed time and sampled heap are observations, not limits. */
public final class ReviewHistoryPerformanceTest extends TestCase {
    private static final int REVISIONS = 80;
    private static final int SOURCE_ROWS = 320;
    private static final int ROWS_PER_REVIEW = 64;
    private static final int CHANGED_ROW = 177;
    private static final Runnable NO_CANCEL = () -> { };

    public void testLongActiveFileHistoryColdAndWarmProjectionAndCheckpoint() throws Exception {
        assertFalse("Pure history work must run outside the IDE event dispatch thread", EventQueue.isDispatchThread());
        long heapBeforeFixture = usedHeap();
        long fixtureStarted = System.nanoTime();
        var history = ReviewHistory.empty();
        String latest = "";
        for (int revision = 0; revision < REVISIONS; revision++) {
            latest = source(revision);
            var lines = LineTable.from(latest);
            for (int first = 0; first < SOURCE_ROWS; first += ROWS_PER_REVIEW) {
                int last = Math.min(first + ROWS_PER_REVIEW, SOURCE_ROWS) - 1;
                history = ReviewHistoryEngine.review(history, latest,
                        List.of(new ReviewedRange(lines.starts()[first], lines.end(last))), revision);
            }
        }
        long fixtureNanos = System.nanoTime() - fixtureStarted;
        long heapAfterFixture = usedHeap();
        assertEquals(REVISIONS, history.snapshots().size());
        assertEquals(REVISIONS * SOURCE_ROWS / ROWS_PER_REVIEW, history.evidence().size());
        String current = latest.replace(statement(CHANGED_ROW), "statement_177 = changed_value\n")
                + "new_unreviewed = value\n";
        Map<String, ContextMap> contexts = new HashMap<>();
        history.snapshots().forEach((id, snapshot) -> contexts.put(id, context(snapshot.text())));
        ContextMap currentContext = context(current);

        Measurement cold = measure(history, current, contexts, currentContext);
        long heapAfterCold = usedHeap();
        var cachedRows = ReviewHistoryEngine.proofRows(history);
        var cachedHistory = ReviewHistoryIndex.of(history, NO_CANCEL);
        assertEquals(REVISIONS * SOURCE_ROWS, cachedRows.size());
        assertResult(cold.projection());
        assertTrue(cold.checkpoint().authoritativeRefs().size() <= cachedRows.size());

        var warm = new ArrayList<Measurement>();
        for (int attempt = 0; attempt < 3; attempt++) {
            Measurement repeated = measure(history, current, contexts, currentContext);
            assertEquals(cold.projection(), repeated.projection());
            assertEquals(cold.checkpoint(), repeated.checkpoint());
            assertSame("An unchanged active history must reuse its derived proof rows", cachedRows,
                    ReviewHistoryEngine.proofRows(history));
            assertSame(cachedHistory, ReviewHistoryIndex.of(history, NO_CANCEL));
            warm.add(repeated);
        }
        long heapAfterWarm = usedHeap();
        FileProjection restored = ReviewHistoryEngine.project(history, current, contexts, currentContext, cold.checkpoint());
        assertEquals(cold.projection().reviewedRanges(), restored.reviewedRanges());
        assertEquals(cold.projection().changedRanges(), restored.changedRanges());
        assertEquals(cold.projection().yellowRanges(), restored.yellowRanges());

        var snapshots = cache(SnapshotIndex.class, "CACHE");
        var metadata = cache(ReviewHistoryIndex.class, "INDEXES");
        var rows = cache(ReviewHistoryIndex.class, "ROWS");
        assertBounded(snapshots, 64);
        assertBounded(metadata, 32);
        assertBounded(rows, 32);
        System.out.printf(Locale.ROOT,
                "DoReview active-file fixture: revisions=%d reviews=%d sourceRows=%d proofRows=%d "
                        + "fixtureBuildMs=%.3f coldProjectionMs=%.3f coldCheckpointMs=%.3f "
                        + "warmProjectionMedianMs=%.3f warmCheckpointMedianMs=%.3f "
                        + "fixtureHeapDeltaBytes=%d coldHeapDeltaBytes=%d warmHeapDeltaBytes=%d "
                        + "snapshotCacheEntries=%d snapshotCacheWeight=%d historyCacheEntries=%d historyCacheWeight=%d "
                        + "rowCacheEntries=%d rowCacheWeight=%d%n",
                REVISIONS, history.evidence().size(), SOURCE_ROWS, cachedRows.size(), ms(fixtureNanos),
                ms(cold.projectionNanos()), ms(cold.checkpointNanos()),
                median(warm.stream().map(Measurement::projectionNanos).toList()),
                median(warm.stream().map(Measurement::checkpointNanos).toList()),
                heapAfterFixture - heapBeforeFixture, heapAfterCold - heapAfterFixture, heapAfterWarm - heapAfterCold,
                snapshots.size(), snapshots.weight(), metadata.size(), metadata.weight(), rows.size(), rows.weight());
        System.out.println("Heap deltas sample used JVM heap without forced GC; they include unreclaimed allocations and "
                + "other JVM activity, can be negative, and do not measure retained memory. "
                + "Warm medians cover three separate operations with new per-operation workspaces; no timing threshold is asserted.");
    }

    private static Measurement measure(ReviewHistory history, String current,
                                       Map<String, ContextMap> contexts, ContextMap currentContext) {
        var budget = ProjectionWorkBudget.defaults();
        long started = System.nanoTime();
        var workspace = new ReviewProjectionWorkspace(current, budget, NO_CANCEL);
        FileProjection projected = ReviewHistoryEngine.project(history, contexts, currentContext, ProjectionHints.empty(), workspace);
        long projectedAt = System.nanoTime();
        ProjectionHints checkpoint = ProjectionHints.fromProjection(history, projected).compactForCheckpoint(history, workspace);
        long finished = System.nanoTime();
        assertTrue("Projection and checkpoint must share the configured quadratic work allowance",
                budget.consumedDiffCells() <= 4_000_000L);
        return new Measurement(projected, checkpoint, projectedAt - started, finished - projectedAt);
    }

    private static void assertResult(FileProjection projection) {
        assertEquals(SOURCE_ROWS - 1L, projection.rows().stream().filter(row -> row.status() == FileProjection.Status.REVIEWED).count());
        assertEquals(FileProjection.Status.CHANGED, projection.rows().get(CHANGED_ROW).status());
        assertEquals(FileProjection.Status.UNREVIEWED, projection.rows().get(SOURCE_ROWS).status());
        assertTrue(projection.yellowRanges().isEmpty());
        assertEquals(1, projection.changedRanges().size());
    }

    private static String source(int revision) {
        var source = new StringBuilder("revision = " + revision + "\n");
        for (int line = 1; line < SOURCE_ROWS; line++) source.append(statement(line));
        return source.toString();
    }

    private static String statement(int line) { return "statement_" + line + " = value_" + line + " + 1\n"; }

    private static ContextMap context(String text) {
        return new ContextMap("performance-fixture", Collections.nCopies(1 + (int) text.chars().filter(value -> value == '\n').count(), "same"));
    }

    private static BoundedIdentityCache<?, ?> cache(Class<?> owner, String name) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name); field.setAccessible(true);
        return (BoundedIdentityCache<?, ?>) field.get(null);
    }

    private static void assertBounded(BoundedIdentityCache<?, ?> cache, int entries) {
        assertTrue(cache.size() <= entries);
        assertTrue(cache.weight() <= 16_000_000L);
    }

    private static long usedHeap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    private static double ms(long nanos) { return nanos / 1_000_000.0; }
    private static double median(List<Long> values) { return ms(values.stream().sorted().toList().get(values.size() / 2)); }
    private record Measurement(FileProjection projection, ProjectionHints checkpoint, long projectionNanos, long checkpointNanos) { }
}
