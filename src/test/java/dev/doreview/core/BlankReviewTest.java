package dev.doreview.core;

import junit.framework.TestCase;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Blank rows never carry review decisions, including immutable legacy evidence. */
public final class BlankReviewTest extends TestCase {
    public void testBlankOnlyReviewIsAnExactNoOp() {
        String text = "\n \t\n\u2003\n";
        var history = ReviewHistory.empty();

        var reviewed = ReviewHistoryEngine.review(history, text, List.of(range(0, text.length())), 1);

        assertSame(history, reviewed);
        assertTrue(reviewed.evidence().isEmpty());
        assertTrue(reviewed.snapshots().isEmpty());
        assertEquals(0L, reviewed.revision());
    }

    public void testBlankOnlyReviewCannotReuseOrAppendAnExistingProof() {
        String text = "first\n \t\nlast\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), text, List.of(range(0, 6)), 1);

        var reviewed = ReviewHistoryEngine.review(history, text, List.of(range(6, 9)), 2);

        assertSame(history, reviewed);
        assertEquals(1, reviewed.evidence().size());
        assertEquals(1L, reviewed.revision());
    }

    public void testMixedSelectionStoresOnlyNonblankRows() {
        String text = "first\n\n \t\nlast\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), text,
                List.of(range(0, text.length())), 1);

        assertEquals(List.of(range(0, 6), range(10, 15)), history.evidence().getFirst().ranges());
        var projection = project(history, text, null);
        assertEquals(FileProjection.Status.REVIEWED, projection.rows().get(0).status());
        assertEquals(FileProjection.Status.REVIEWED, projection.rows().get(3).status());
        assertBlankRowsUnmarked(text, projection);
    }

    public void testLegacyBlankReviewsAreIgnoredWithoutRewritingHistory() {
        String text = "first\n\n \t\nlast\n";
        var history = legacy(text, List.of(range(0, text.length())));
        var original = new ReviewHistory(history.snapshots(), history.evidence(), history.revocations(), history.revision());

        var projection = project(history, text, null);

        assertEquals(FileProjection.Status.REVIEWED, projection.rows().get(0).status());
        assertEquals(FileProjection.Status.REVIEWED, projection.rows().get(3).status());
        assertBlankRowsUnmarked(text, projection);
        assertEquals(original, history);
        assertEquals(List.of(range(0, text.length())), history.evidence().getFirst().ranges());
    }

    public void testLegacyBlankReviewsCannotBecomeYellowWhenContextChanges() {
        String text = "first\n\n \t\nlast\n";
        var history = legacy(text, List.of(range(0, text.length())));
        var baseline = history.snapshots().values().iterator().next();
        var projection = ReviewHistoryEngine.project(history, text,
                Map.of(baseline.id(), context(text, "old-context")), context(text, "new-context"), null);

        assertEquals(FileProjection.Status.NEEDS_REVIEW, projection.rows().get(0).status());
        assertBlankRowsUnmarked(text, projection);
    }

    public void testDeletedLegacyBlankRowsDoNotCreateDeletionAnchors() {
        String baseline = " \t\n\n";
        var history = legacy(baseline, List.of(range(0, baseline.length())));

        var projection = project(history, "", null);

        assertEquals(0, projection.deletedCount());
        assertTrue(projection.deletedRefs().isEmpty());
    }

    public void testDeletingMixedLegacyRowsCountsOnlyTheNonblankDeletion() {
        String baseline = "work();\n \t\n\n";
        var history = legacy(baseline, List.of(range(0, baseline.length())));

        var projection = project(history, "", null);

        assertEquals(1, projection.deletedCount());
        assertEquals(List.of(new EvidenceRef("legacy-proof", 0, 8)), projection.deletedRefs());
    }

    public void testLegacyBlankProofCannotFollowNewlyTypedCode() {
        String baseline = "head\n \t\ntail\n";
        var history = legacy(baseline, List.of(range(5, 8)));
        var ref = new EvidenceRef("legacy-proof", 5, 8);
        // A checkpoint written by an older plugin can still contain a blank-row locator.
        var saved = new ProjectionHints(Map.of(ref, range(5, 8)), Set.of(ref));
        String typed = "newOperation();\n";
        String current = baseline.substring(0, 5) + typed + baseline.substring(8);
        var hints = saved.applyEdit(baseline, current, 5, 3, typed.length());

        var projection = project(history, current, hints);

        assertEquals(FileProjection.Status.UNREVIEWED, projection.rows().get(1).status());
        assertTrue(projection.rows().get(1).evidenceRefs().isEmpty());
        assertTrue(projection.reviewedRanges().isEmpty());
        assertTrue(projection.yellowRanges().isEmpty());
        assertTrue(projection.changedRanges().isEmpty());
    }

    public void testClearingReviewedCodeLeavesTheBlankRowUnmarked() {
        String baseline = "head\nwork();\ntail\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), baseline,
                List.of(range(0, baseline.length())), 1);
        var hints = ProjectionHints.fromProjection(history, project(history, baseline, null));
        String current = baseline.replace("work();", " \t");
        hints = hints.applyEdit(baseline, current, 5, "work();".length(), 2);

        var projection = project(history, current, hints);

        assertBlankRowsUnmarked(current, projection);
        assertEquals(FileProjection.Status.REVIEWED, projection.rows().get(0).status());
        assertEquals(FileProjection.Status.REVIEWED, projection.rows().get(2).status());
    }

    public void testAmbiguousChangedHunkDoesNotPaintItsBlankRows() {
        String baseline = "head\noldOne();\noldTwo();\ntail\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), baseline,
                List.of(range(0, baseline.length())), 1);
        String current = "head\n \t\nreplacement();\n\ntail\n";

        var projection = project(history, current, null);

        assertEquals(FileProjection.Status.NEEDS_REVIEW, projection.rows().get(2).status());
        assertBlankRowsUnmarked(current, projection);
        assertEquals(FileProjection.Status.REVIEWED, projection.rows().get(0).status());
        assertEquals(FileProjection.Status.REVIEWED, projection.rows().get(4).status());
    }

    private static ReviewHistory legacy(String text, List<ReviewedRange> ranges) {
        var snapshot = FileSnapshot.create(text);
        var proof = new ReviewEvidence("legacy-proof", snapshot.id(), ranges, 1, 1);
        return new ReviewHistory(Map.of(snapshot.id(), snapshot), List.of(proof), List.of(), 1);
    }

    private static FileProjection project(ReviewHistory history, String text, ProjectionHints hints) {
        var contexts = new HashMap<String, ContextMap>();
        history.snapshots().forEach((id, snapshot) -> contexts.put(id, context(snapshot.text(), "same-context")));
        return ReviewHistoryEngine.project(history, text, contexts, context(text, "same-context"), hints);
    }

    private static ContextMap context(String text, String fingerprint) {
        int rows = 1 + (int) text.chars().filter(character -> character == '\n').count();
        return new ContextMap("blank-review-test", Collections.nCopies(rows, fingerprint));
    }

    private static ReviewedRange range(int start, int end) { return new ReviewedRange(start, end); }

    private static void assertBlankRowsUnmarked(String text, FileProjection projection) {
        for (var row : projection.rows()) {
            if (!text.substring(row.start(), row.end()).isBlank()) continue;
            assertEquals("Blank row " + row.line(), FileProjection.Status.UNREVIEWED, row.status());
            assertEquals(FileProjection.Reason.UNREVIEWED, row.reason());
            assertTrue(row.evidenceRefs().isEmpty());
            for (var ranges : List.of(projection.reviewedRanges(), projection.yellowRanges(), projection.changedRanges())) {
                assertFalse("Blank row must not intersect a presentation range", ranges.stream()
                        .anyMatch(range -> range.start() < row.end() && range.end() > row.start()));
            }
        }
    }
}
