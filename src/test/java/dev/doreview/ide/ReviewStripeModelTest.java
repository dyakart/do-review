package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.testFramework.EditorTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ReviewedRange;

import java.util.List;

import static dev.doreview.ide.ReviewStripeModel.Kind.CHANGED;
import static dev.doreview.ide.ReviewStripeModel.Kind.DELETED;
import static dev.doreview.ide.ReviewStripeModel.Kind.NEEDS_REVIEW;
import static dev.doreview.ide.ReviewStripeModel.Kind.REVIEWED;

/** Logical spans are rasterized in the selected map's coordinates. */
public final class ReviewStripeModelTest extends BasePlatformTestCase {
    public void testCaptureMergesRuns() {
        var projection = new FileProjection("snapshot", 1, List.of(),
                List.of(new ReviewedRange(0, 4), new ReviewedRange(4, 8), new ReviewedRange(10, 14)),
                List.of(new ReviewedRange(14, 16)), List.of(new ReviewedRange(16, 18)),
                List.of(new FileProjection.DeletedRow(8, 0, List.of()), new FileProjection.DeletedRow(8, 1, List.of())));

        var snapshot = ReviewStripeModel.capture(projection, 42);

        assertEquals(42L, snapshot.stamp());
        assertEquals(List.of(new ReviewStripeModel.Run(0, 8, REVIEWED),
                new ReviewStripeModel.Run(8, 8, DELETED),
                new ReviewStripeModel.Run(10, 14, REVIEWED),
                new ReviewStripeModel.Run(14, 16, NEEDS_REVIEW),
                new ReviewStripeModel.Run(16, 18, CHANGED)), snapshot.runs());
    }

    public void testBlankHoleAndEndLf() {
        Editor editor = open("a\n\nc");
        var snapshot = snapshot(editor, new ReviewStripeModel.Run(0, 2, REVIEWED),
                new ReviewStripeModel.Run(3, 4, REVIEWED));
        int row = editor.getLineHeight();

        assertEquals("The first range's LF must not cover the following blank visual row",
                List.of(new ReviewStripeModel.Band(0, row, REVIEWED), new ReviewStripeModel.Band(2 * row, row, REVIEWED)),
                ReviewStripeModel.bands(editor, snapshot, 3 * row));
    }

    public void testRasterPriority() {
        Editor editor = open("abcd");
        int row = editor.getLineHeight();
        var snapshot = snapshot(editor, new ReviewStripeModel.Run(0, 1, REVIEWED),
                new ReviewStripeModel.Run(1, 2, NEEDS_REVIEW));
        assertEquals(List.of(new ReviewStripeModel.Band(0, row, NEEDS_REVIEW)), ReviewStripeModel.bands(editor, snapshot, row));
        snapshot = snapshot(editor, new ReviewStripeModel.Run(0, 1, REVIEWED),
                new ReviewStripeModel.Run(1, 2, NEEDS_REVIEW), new ReviewStripeModel.Run(2, 3, CHANGED));
        assertEquals(List.of(new ReviewStripeModel.Band(0, row, CHANGED)), ReviewStripeModel.bands(editor, snapshot, row));

        snapshot = snapshot(editor, new ReviewStripeModel.Run(0, 1, REVIEWED),
                new ReviewStripeModel.Run(1, 2, NEEDS_REVIEW), new ReviewStripeModel.Run(2, 3, CHANGED),
                new ReviewStripeModel.Run(3, 3, DELETED));
        assertEquals(List.of(new ReviewStripeModel.Band(0, 1, DELETED)), ReviewStripeModel.bands(editor, snapshot, 1));
    }

    public void testDeletionBand() {
        Editor editor = open("a\nb\nc\nd");
        var snapshot = snapshot(editor, new ReviewStripeModel.Run(0, 7, REVIEWED),
                new ReviewStripeModel.Run(4, 4, DELETED));
        int row = editor.getLineHeight();

        assertEquals(List.of(new ReviewStripeModel.Band(0, 2 * row, REVIEWED),
                new ReviewStripeModel.Band(2 * row, 2, DELETED), new ReviewStripeModel.Band(2 * row + 2, 2 * row - 2, REVIEWED)),
                ReviewStripeModel.bands(editor, snapshot, 4 * row));
        assertEquals(List.of(new ReviewStripeModel.Band(1, 1, DELETED)),
                ReviewStripeModel.bands(editor, snapshot(editor, new ReviewStripeModel.Run(7, 7, DELETED)), 2));
    }

    public void testAdjacentPixelsMerge() {
        Editor editor = open("a\nb\nc");
        var snapshot = snapshot(editor, new ReviewStripeModel.Run(0, 2, NEEDS_REVIEW),
                new ReviewStripeModel.Run(2, 4, NEEDS_REVIEW), new ReviewStripeModel.Run(4, 5, CHANGED));
        int row = editor.getLineHeight();

        assertEquals(List.of(new ReviewStripeModel.Band(0, 2 * row, NEEDS_REVIEW),
                new ReviewStripeModel.Band(2 * row, row, CHANGED)), ReviewStripeModel.bands(editor, snapshot, 3 * row));
    }

    public void testStaleAndEmpty() {
        Editor editor = open("a");
        var stale = new ReviewStripeModel.Snapshot(editor.getDocument().getModificationStamp() - 1,
                List.of(new ReviewStripeModel.Run(0, 1, REVIEWED)));
        assertEmpty(ReviewStripeModel.bands(editor, stale, 20));
        assertEmpty(ReviewStripeModel.bands(editor, snapshot(editor, new ReviewStripeModel.Run(0, 1, REVIEWED)), 0));
        assertEmpty(ReviewStripeModel.bands(editor, snapshot(editor), 20));
    }

    public void testNavigationClamps() {
        Editor editor = open("one\ntwo\nlast");
        int row = editor.getLineHeight();
        assertEquals(0, offsetAt(editor, Integer.MIN_VALUE, 3 * row));
        assertEquals(4, offsetAt(editor, row, 3 * row));
        assertEquals(8, offsetAt(editor, Integer.MAX_VALUE, 3 * row));
        assertEquals(0, offsetAt(editor, 10, 0));
    }

    public void testFoldedRows() {
        Editor editor = open("head\n{\ninside1\ninside2\n}\ntail");
        var document = editor.getDocument();
        editor.getFoldingModel().runBatchFoldingOperation(() -> {
            var region = editor.getFoldingModel().addFoldRegion(document.getLineStartOffset(1),
                    document.getLineEndOffset(4), "...");
            assertNotNull(region);
            region.setExpanded(false);
        });
        assertEquals(3, ((EditorImpl) editor).getVisibleLineCount());
        var snapshot = snapshot(editor, new ReviewStripeModel.Run(document.getLineStartOffset(2),
                document.getLineEndOffset(3), NEEDS_REVIEW));

        int row = editor.getLineHeight();
        assertEquals(List.of(new ReviewStripeModel.Band(row, row, NEEDS_REVIEW)), ReviewStripeModel.bands(editor, snapshot, 3 * row));
        assertEquals(document.getLineStartOffset(5), offsetAt(editor, 2 * row + 1, 3 * row));
    }

    public void testWrappedRows() {
        Editor editor = open("one two three four five six seven eight nine ten eleven twelve\ntail");
        EditorTestUtil.configureSoftWraps(editor, 12);
        int visible = ((EditorImpl) editor).getVisibleLineCount();
        assertTrue("The fixture must contain actual visual soft wraps", visible > editor.getDocument().getLineCount());
        int nextLine = editor.getDocument().getLineStartOffset(1);
        int tailVisualLine = editor.offsetToVisualPosition(nextLine).line;
        var snapshot = snapshot(editor, new ReviewStripeModel.Run(0, nextLine, REVIEWED));
        int row = editor.getLineHeight();

        assertEquals(List.of(new ReviewStripeModel.Band(0, tailVisualLine * row, REVIEWED)),
                ReviewStripeModel.bands(editor, snapshot, visible * row));
        int wrappedOffset = offsetAt(editor, row + 1, visible * row);
        assertTrue("Navigation in the second visual row must enter the wrapped logical line",
                wrappedOffset > 0 && wrappedOffset < nextLine);
        assertEquals("Keep the visual side of a soft wrap when navigating", 1,
                ReviewStripeModel.positionAt(editor, row + 1, visible * row).line);
        assertEquals(nextLine, offsetAt(editor, visible * row - 1, visible * row));
    }

    public void testWithoutMinimapShortFileUsesWholeFileOverview() {
        Editor editor = open("first\nlast");
        int row = editor.getLineHeight();
        assertEquals(List.of(new ReviewStripeModel.Band(0, 10 * row, REVIEWED)),
                ReviewStripeModel.bands(editor, snapshot(editor, new ReviewStripeModel.Run(0, 6, REVIEWED)), 20 * row));
    }

    public void testUsesMapScaleAndCropInsteadOfEditorViewport() {
        Editor editor = open("a\nb\nc\nd\ne");
        var mapping = new ReviewStripeModel.Mapping() {
            @Override public int top() { return 37; }
            @Override public int height() { return 9; }
            @Override public double scale() { return 1.5; }
            @Override public int[] rows(int first, int last) { return new int[]{first * 3 - 5, (last + 1) * 3 - 5}; }
            @Override public int visualLine(int y) { return (y + 5) / 3; }
        };
        var snapshot = snapshot(editor, new ReviewStripeModel.Run(0, 4, REVIEWED),
                new ReviewStripeModel.Run(4, 6, NEEDS_REVIEW), new ReviewStripeModel.Run(6, 8, CHANGED),
                new ReviewStripeModel.Run(8, 8, DELETED));
        assertEquals(List.of(new ReviewStripeModel.Band(0, 1, REVIEWED),
                        new ReviewStripeModel.Band(1, 3, NEEDS_REVIEW), new ReviewStripeModel.Band(4, 2, CHANGED)),
                ReviewStripeModel.bands(editor, snapshot, mapping));
        assertEquals(3, ReviewStripeModel.positionAt(editor, 6, mapping).line);
    }

    private Editor open(String text) {
        myFixture.configureByText("stripe.txt", text);
        return myFixture.getEditor();
    }

    private static ReviewStripeModel.Snapshot snapshot(Editor editor, ReviewStripeModel.Run... runs) {
        return new ReviewStripeModel.Snapshot(editor.getDocument().getModificationStamp(), List.of(runs));
    }

    private static int offsetAt(Editor editor, int y, int height) {
        return editor.visualPositionToOffset(ReviewStripeModel.positionAt(editor, y, height));
    }
}
