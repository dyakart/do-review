package dev.doreview.ide;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReviewAuthorsPresentationTest extends BasePlatformTestCase {
    public void testAdjacentRowsWithDifferentAuthorsKeepSeparateTooltips() {
        myFixture.configureByText("authors.txt", "first\nsecond\n");
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), "first\nsecond\n", List.of(new ReviewedRange(0, 6)), 1);
        history = ReviewHistoryEngine.review(history, "first\nsecond\n", List.of(new ReviewedRange(6, 13)), 2);
        var names = Map.of(history.evidence().getFirst().id(), "Alice", history.evidence().getLast().id(), "Bob");
        Disposable parent = Disposer.newDisposable();
        var presentation = new ReviewEditorPresentation(getProject(), parent, ignored -> { }, (document, refs) ->
                refs.stream().map(ref -> names.get(ref.evidenceId())).distinct().sorted().reduce((left, right) -> left + ", " + right).orElse(""));
        try {
            presentation.render(myFixture.getEditor(), ReviewHistoryEngine.project(history, "first\nsecond\n", null, null, null));
            var marks = marks();
            assertEquals(2, marks.size());
            assertTrue(marks.getFirst().getErrorStripeTooltip().toString().contains("Alice"));
            assertFalse(marks.getFirst().getErrorStripeTooltip().toString().contains("Bob"));
            assertTrue(marks.getLast().getErrorStripeTooltip().toString().contains("Bob"));
            assertFalse(marks.getLast().getErrorStripeTooltip().toString().contains("Alice"));
        } finally { Disposer.dispose(parent); }
    }

    public void testCommonAuthorSetStillCoalescesWholeReviewedBlock() {
        myFixture.configureByText("authors.txt", "first\nsecond\n");
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), "first\nsecond\n", List.of(new ReviewedRange(0, 13)), 1);
        Disposable parent = Disposer.newDisposable();
        var presentation = new ReviewEditorPresentation(getProject(), parent, ignored -> { }, (document, refs) -> "Alice, Bob");
        try {
            var projection = ReviewHistoryEngine.project(history, "first\nsecond\n", null, null, null);
            for (int index = 0; index < 5; index++) presentation.render(myFixture.getEditor(), projection);
            assertEquals(1, marks().size());
            assertTrue(marks().getFirst().getErrorStripeTooltip().toString().contains("Alice, Bob"));
        } finally { Disposer.dispose(parent); }
        assertEmpty(marks());
    }

    public void testAuthorNamesAreHtmlEscapedAndGutterCompareStillWorks() {
        myFixture.configureByText("authors.txt", "first\n");
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), "first\n", List.of(new ReviewedRange(0, 6)), 1);
        Disposable parent = Disposer.newDisposable();
        var compared = new AtomicInteger();
        var presentation = new ReviewEditorPresentation(getProject(), parent, document -> {
            assertSame(myFixture.getEditor().getDocument(), document);
            compared.incrementAndGet();
        }, (document, refs) -> "<img src='file:///bad'> & \"Alice\"");
        try {
            presentation.render(myFixture.getEditor(), ReviewHistoryEngine.project(history, "first\n", null, null, null));
            var highlighter = marks().getFirst();
            String tooltip = highlighter.getErrorStripeTooltip().toString();
            assertTrue(tooltip.startsWith("<html>"));
            assertFalse(tooltip.contains("<img"));
            assertTrue(tooltip.contains("&lt;img src=&#39;file:///bad&#39;&gt; &amp; &quot;Alice&quot;"));
            String gutter = highlighter.getGutterIconRenderer().getTooltipText();
            assertTrue(gutter.contains("Click to compare"));
            assertTrue(gutter.endsWith("</html>"));
            myFixture.testAction(highlighter.getGutterIconRenderer().getClickAction());
            assertEquals(1, compared.get());
        } finally { Disposer.dispose(parent); }
    }

    public void testCompatibilityConstructorKeepsPlainTooltipWithoutAuthors() {
        myFixture.configureByText("authors.txt", "first\n");
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), "first\n", List.of(new ReviewedRange(0, 6)), 1);
        Disposable parent = Disposer.newDisposable();
        var presentation = new ReviewEditorPresentation(getProject(), parent, ignored -> { });
        try {
            presentation.render(myFixture.getEditor(), ReviewHistoryEngine.project(history, "first\n", null, null, null));
            assertEquals("Reviewed — exact text and context confirmed", marks().getFirst().getErrorStripeTooltip());
            assertEquals("Reviewed — exact text and context confirmed — click to compare",
                    marks().getFirst().getGutterIconRenderer().getTooltipText());
        } finally { Disposer.dispose(parent); }
    }

    private List<RangeHighlighter> marks() {
        return Arrays.stream(myFixture.getEditor().getMarkupModel().getAllHighlighters())
                .filter(highlighter -> Boolean.TRUE.equals(highlighter.getUserData(ReviewMarkKeys.REVIEWED_HIGHLIGHTER)))
                .sorted(java.util.Comparator.comparingInt(RangeHighlighter::getStartOffset)).toList();
    }
}
