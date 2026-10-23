package dev.doreview.core;

import junit.framework.TestCase;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReviewHistoryIndexTest extends TestCase {
    private static final Runnable NO_CANCEL = () -> { };

    public void testProjectionAndPendingEditGuardReuseActiveRowsAndTables() {
        String text = "head\nwork();\ntail\n";
        var history = reviewed(text);
        var rows = ReviewHistoryEngine.proofRows(history);
        var baseline = history.snapshots().values().iterator().next();
        var table = SnapshotIndex.of(baseline, NO_CANCEL).lines();
        assertSame(table, rows.getFirst().lines());
        var initial = project(history, text, ProjectionHints.empty());
        String changed = text.replace("work();", "changed();");
        var pending = ProjectionHints.empty().guardUnresolvedEdit(history, text, changed, 5, 7, 10);
        project(history, changed, pending);

        assertSame(rows, ReviewHistoryEngine.proofRows(history));
        assertSame(table, SnapshotIndex.of(baseline, NO_CANCEL).lines());
        assertEquals(List.of(new ReviewedRange(0, text.length())), initial.reviewedRanges());
        try { rows.clear(); fail("Cached active rows must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    public void testReviewAndRevocationCreateFreshIndexesButReuseUnchangedSnapshotTables() {
        String text = "first\nsecond\n";
        var original = reviewed(text);
        var before = ReviewHistoryIndex.of(original, NO_CANCEL);
        var beforeRows = ReviewHistoryEngine.proofRows(original);
        var rechecked = ReviewHistoryEngine.review(original, text, List.of(new ReviewedRange(0, 6)), 2);
        var afterRows = ReviewHistoryEngine.proofRows(rechecked);
        assertNotSame(before, ReviewHistoryIndex.of(rechecked, NO_CANCEL));
        assertNotSame(beforeRows, afterRows);
        assertSame(beforeRows.getFirst().lines(), afterRows.getFirst().lines());
        assertEquals(3, afterRows.size());

        var revoked = ReviewHistoryEngine.revoke(rechecked, afterRows.stream().map(ReviewHistoryEngine.ProofRow::ref).toList());
        assertTrue(ReviewHistoryEngine.proofRows(revoked).isEmpty());
        assertEquals(List.of(), project(revoked, text, ProjectionHints.empty()).reviewedRanges());
        assertEquals(2, ReviewHistoryEngine.proofRows(original).size());
    }

    public void testEqualReloadAndSameRevisionDifferentHistoryNeverReuseHistoryIndex() {
        String text = "first\nsecond\n";
        var first = reviewed(text);
        var reloaded = new ReviewHistory(first.snapshots(), first.evidence(), first.revocations(), first.revision());
        assertEquals(first, reloaded);
        assertNotSame(ReviewHistoryIndex.of(first, NO_CANCEL), ReviewHistoryIndex.of(reloaded, NO_CANCEL));
        assertNotSame(ReviewHistoryEngine.proofRows(first), ReviewHistoryEngine.proofRows(reloaded));

        var proof = first.evidence().getFirst();
        var withdrawn = new ReviewHistory(first.snapshots(), first.evidence(),
                List.of(new Revocation(proof.id(), List.of(new ReviewedRange(0, 6)), first.revision())), first.revision());
        assertEquals(first.revision(), withdrawn.revision());
        assertEquals(1, ReviewHistoryEngine.proofRows(withdrawn).size());
        assertEquals(FileProjection.Status.UNREVIEWED, project(withdrawn, text, ProjectionHints.empty()).rows().getFirst().status());
        assertEquals(2, ReviewHistoryEngine.proofRows(first).size());
    }

    public void testSnapshotIdentityIsNotItsHashOrRevision() {
        var original = FileSnapshot.create("same\n");
        var reloaded = new FileSnapshot(original.id(), original.text());
        assertSame(SnapshotIndex.of(original, NO_CANCEL), SnapshotIndex.of(original, NO_CANCEL));
        assertNotSame(SnapshotIndex.of(original, NO_CANCEL), SnapshotIndex.of(reloaded, NO_CANCEL));
        assertEquals(SnapshotIndex.of(original, NO_CANCEL).lines().text(), SnapshotIndex.of(reloaded, NO_CANCEL).lines().text());
    }

    public void testHistoryAndSnapshotCachesEvictOldEntries() {
        var original = reviewed("original\n");
        var firstIndex = ReviewHistoryIndex.of(original, NO_CANCEL);
        var firstRows = ReviewHistoryEngine.proofRows(original);
        var firstSnapshot = original.snapshots().values().iterator().next();
        var snapshotIndex = SnapshotIndex.of(firstSnapshot, NO_CANCEL);
        for (int index = 0; index < 70; index++) {
            var history = reviewed("other " + index + "\n");
            ReviewHistoryIndex.of(history, NO_CANCEL);
            ReviewHistoryEngine.proofRows(history);
        }
        assertNotSame(snapshotIndex, SnapshotIndex.of(firstSnapshot, NO_CANCEL));
        assertNotSame(firstIndex, ReviewHistoryIndex.of(original, NO_CANCEL));
        assertNotSame(firstRows, ReviewHistoryEngine.proofRows(original));
        assertEquals(firstRows.stream().map(ReviewHistoryEngine.ProofRow::ref).toList(),
                ReviewHistoryEngine.proofRows(original).stream().map(ReviewHistoryEngine.ProofRow::ref).toList());
    }

    public void testCancellationIsCheckedOnWarmIndexesAndDoesNotPublishPartialRows() {
        String text = "work();\n".repeat(700);
        var history = reviewed(text);
        var checks = new AtomicInteger();
        try {
            ReviewHistoryIndex.proofRows(history, () -> {
                if (checks.incrementAndGet() == 8) throw new Cancelled();
            });
            fail("Cold indexing must honor cancellation");
        } catch (Cancelled expected) { }
        assertEquals(700, ReviewHistoryEngine.proofRows(history).size());
        Runnable cancel = () -> { throw new Cancelled(); };
        try { ReviewHistoryIndex.of(history, cancel); fail("Warm metadata must check cancellation"); }
        catch (Cancelled expected) { }
        try { ReviewHistoryIndex.proofRows(history, cancel); fail("Warm rows must check cancellation"); }
        catch (Cancelled expected) { }
        try { SnapshotIndex.of(history.snapshots().values().iterator().next(), cancel); fail("Warm snapshot must check cancellation"); }
        catch (Cancelled expected) { }
        assertEquals(700, ReviewHistoryEngine.proofRows(history).size());
    }

    public void testIdentityCacheBoundsWeightAndLruWithoutCallingKeyEquality() {
        var cache = new BoundedIdentityCache<Object, String>(2, 5, String::length);
        var first = new IdentityOnlyKey();
        var second = new IdentityOnlyKey();
        var third = new IdentityOnlyKey();
        var built = new AtomicInteger();
        java.util.function.Function<Object, String> build = ignored -> { built.incrementAndGet(); return "xx"; };
        cache.get(first, NO_CANCEL, build);
        cache.get(second, NO_CANCEL, build);
        cache.get(first, NO_CANCEL, build);
        cache.get(third, NO_CANCEL, build);
        assertEquals(3, built.get());
        cache.get(second, NO_CANCEL, build);
        assertEquals(4, built.get());
        assertEquals(2, cache.size());
        assertEquals(4L, cache.weight());

        var huge = new IdentityOnlyKey();
        cache.get(huge, NO_CANCEL, ignored -> "oversized");
        cache.get(huge, NO_CANCEL, ignored -> { built.incrementAndGet(); return "oversized"; });
        assertEquals(5, built.get());
        assertEquals(2, cache.size());
        assertEquals(4L, cache.weight());
    }

    public void testCancelledCacheBuildIsNeverPublished() {
        var cache = new BoundedIdentityCache<Object, String>(2, 10, String::length);
        Object key = new Object();
        try { cache.get(key, NO_CANCEL, ignored -> { throw new Cancelled(); }); fail(); }
        catch (Cancelled expected) { }
        assertEquals(0, cache.size());
        assertEquals("complete", cache.get(key, NO_CANCEL, ignored -> "complete"));
        assertEquals(1, cache.size());
    }

    public void testWeightLimitEvictsBeforeEntryCountLimit() {
        var cache = new BoundedIdentityCache<Object, String>(10, 3, String::length);
        Object first = new Object(), second = new Object();
        cache.get(first, NO_CANCEL, ignored -> "aa");
        cache.get(second, NO_CANCEL, ignored -> "bb");
        assertEquals(1, cache.size());
        assertEquals(2L, cache.weight());
        assertEquals("rebuilt", cache.get(first, NO_CANCEL, ignored -> "rebuilt"));
        assertEquals(1, cache.size());
        assertEquals(2L, cache.weight());
    }

    public void testWorkspaceReusesMappingButNeverAcrossOperationsOrBudgets() {
        var baseline = FileSnapshot.create("a\nb\na\nvariant\n");
        String current = "b\na\nb\ncurrent\n";
        var budget = new ProjectionWorkBudget(25);
        var cancelled = new AtomicBoolean();
        var workspace = new ReviewProjectionWorkspace(current, budget, () -> {
            if (cancelled.get()) throw new Cancelled();
        });
        var first = workspace.mapping(baseline);
        assertEquals(25L, budget.consumedDiffCells());
        assertSame(first, workspace.mapping(baseline));
        assertEquals(25L, budget.consumedDiffCells());
        var limited = new ReviewProjectionWorkspace(current, new ProjectionWorkBudget(0), NO_CANCEL).mapping(baseline);
        assertNotSame(first, limited);
        assertTrue(limited.changes().stream().anyMatch(change -> change.reason() == SnapshotReconciler.ChangeReason.DIFF_LIMIT));
        cancelled.set(true);
        try { workspace.mapping(baseline); fail("Warm workspace must check cancellation"); }
        catch (Cancelled expected) { }
    }

    public void testSharedProjectionAndCheckpointPreserveDuplicateAndSplitProvenance() {
        String baseline = "head\nAB\nsame\ntail\n";
        var history = reviewed(baseline);
        var initial = ProjectionHints.fromProjection(history, project(history, baseline, ProjectionHints.empty()));
        String copied = "head\nAB\nsame\nsame\ntail\n";
        var copyHints = initial.applyEdit(baseline, copied, baseline.indexOf("tail"), 0, 5);
        String split = "head\nA\nB\nsame\nsame\ntail\n";
        var splitHints = copyHints.applyEdit(copied, split, 6, 0, 1);
        var contexts = contexts(history);
        for (var fixture : List.of(Map.entry(copied, copyHints), Map.entry(split, splitHints))) {
            var workspace = new ReviewProjectionWorkspace(fixture.getKey(), ProjectionWorkBudget.defaults(), NO_CANCEL);
            var actual = ReviewHistoryEngine.project(history, contexts, context(fixture.getKey()), fixture.getValue(), workspace);
            var expected = project(history, fixture.getKey(), fixture.getValue());
            assertEquals(expected, actual);
            var resolved = fixture.getValue().mergeResolved(ProjectionHints.fromProjection(history, actual));
            assertEquals(resolved.compactForCheckpoint(fixture.getKey(), history), resolved.compactForCheckpoint(history, workspace));
        }
    }

    public void testSnapshotPriorityIsReusedAndTracksNewReviewSequence() {
        var first = reviewed("first\n");
        var initial = ReviewHistoryEngine.snapshotsByRecency(first, NO_CANCEL);
        assertSame(initial, ReviewHistoryEngine.snapshotsByRecency(first, NO_CANCEL));
        var next = ReviewHistoryEngine.review(first, "second\n", List.of(new ReviewedRange(0, 7)), 0);
        var ordered = ReviewHistoryEngine.snapshotsByRecency(next, NO_CANCEL);
        assertEquals("second\n", ordered.getFirst().text());
        assertEquals("first\n", ordered.getLast().text());
        assertEquals(1, initial.size());
    }

    public void testSnapshotPriorityExcludesNeverReviewedCheckpointTargets() {
        var history = reviewed("reviewed\n");
        var target = FileSnapshot.create("unreviewed checkpoint target\n");
        var snapshots = new HashMap<>(history.snapshots());
        snapshots.put(target.id(), target);
        var portable = new ReviewHistory(snapshots, history.evidence(), history.revocations(), history.revision());

        assertEquals(history.snapshots().values().iterator().next(),
                ReviewHistoryEngine.snapshotsByRecency(portable, NO_CANCEL).getFirst());
        assertEquals(1, ReviewHistoryEngine.snapshotsByRecency(portable, NO_CANCEL).size());
        assertEquals(2, portable.snapshots().size());
        assertEquals(1, ReviewHistoryEngine.proofRows(portable).size());
    }

    private static ReviewHistory reviewed(String text) {
        return ReviewHistoryEngine.review(ReviewHistory.empty(), text, List.of(new ReviewedRange(0, text.length())), 1);
    }
    private static Map<String, ContextMap> contexts(ReviewHistory history) {
        var result = new HashMap<String, ContextMap>();
        history.snapshots().forEach((id, snapshot) -> result.put(id, context(snapshot.text())));
        return result;
    }
    private static FileProjection project(ReviewHistory history, String current, ProjectionHints hints) {
        return ReviewHistoryEngine.project(history, current, contexts(history), context(current), hints);
    }
    private static ContextMap context(String text) {
        return new ContextMap("index-test", Collections.nCopies(1 + (int) text.chars().filter(value -> value == '\n').count(), "same"));
    }
    private static final class Cancelled extends RuntimeException { }
    private static final class IdentityOnlyKey {
        @Override public int hashCode() { throw new AssertionError("Value hash must not be called"); }
        @Override public boolean equals(Object other) { throw new AssertionError("Value equality must not be called"); }
    }
}
