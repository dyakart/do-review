package dev.doreview.ide;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.FileEditorManagerTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Without CodeGlance, real file editor hosts use a fixed whole-file overview. */
public final class ReviewStripeOverviewTest extends FileEditorManagerTestCase {
    private DoReviewService service;

    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var projectFixture = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(projectFixture, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(projectFixture.getProject().getBasePath()); }
        });
    }

    @Override public void setUp() {
        // The standard headless manager creates unhosted editors. This test case installs
        // the real FileEditorManagerImpl and owns its docking/async editor cleanup.
        super.setUp();
        ReviewHistoryStore.getInstance().loadState(new ReviewHistoryStore.StoreState());
        service = DoReviewService.getInstance(getProject());
        service.historyReloaded();
        ReviewStripeSettings.getInstance(getProject()).setShown(true);
    }

    public void testWholeFileBandsStayFixedWhenTheEditorScrolls() {
        var editor = open("scrolled.py");
        sizeHost(editor, 920, 360);
        var stripe = stripe(editor);
        var snapshot = snapshot(editor.getDocument());
        stripe.setSnapshot(snapshot);
        assertFallback(editor, stripe, snapshot);
        var initial = stripe.bands();
        assertTrue(band(stripe, ReviewStripeModel.Kind.REVIEWED).y() > 0);
        assertTrue(band(stripe, ReviewStripeModel.Kind.NEEDS_REVIEW).y() > stripe.trackBounds().height / 2);

        scroll(editor, rowY(editor, 700) + editor.getLineHeight() / 2);

        assertTrue("The editor must really be scrolled deep into the long file", editor.getScrollingModel().getVisibleArea().y > 0);
        assertEquals("Fallback bands describe the whole file, so scrolling must not translate them", initial, stripe.bands());
        assertFallback(editor, stripe, snapshot);
        int oldGreenY = band(stripe, ReviewStripeModel.Kind.REVIEWED).y();
        int oldYellowY = band(stripe, ReviewStripeModel.Kind.NEEDS_REVIEW).y();

        sizeHost(editor, 920, 600);

        assertTrue("A taller overview must spread the same file's green block farther down", band(stripe, ReviewStripeModel.Kind.REVIEWED).y() > oldGreenY);
        assertTrue("A taller overview must spread the same file's yellow block farther down", band(stripe, ReviewStripeModel.Kind.NEEDS_REVIEW).y() > oldYellowY);
        assertFallback(editor, stripe, snapshot);
    }

    public void testFallbackBoundsExcludeHeaderAndHorizontalScrollbar() {
        var editor = open("header.py");
        var extended = (EditorEx) editor;
        var header = new JPanel();
        header.setPreferredSize(new Dimension(0, 37));
        extended.setHeaderComponent(header);
        extended.getScrollPane().setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        sizeHost(editor, 760, 340);
        editor.getScrollingModel().scrollHorizontally(140);
        scroll(editor, rowY(editor, 500));
        var stripe = stripe(editor);
        var snapshot = snapshot(editor.getDocument());
        stripe.setSnapshot(snapshot);
        var track = stripe.trackBounds();
        var viewport = extended.getScrollPane().getViewport();
        var viewportTop = SwingUtilities.convertPoint(viewport, 0, 0, stripe);
        var horizontal = extended.getScrollPane().getHorizontalScrollBar();
        var scrollbarTop = SwingUtilities.convertPoint(horizontal, 0, 0, stripe);

        assertTrue("The fixture must contain an actual header", header.getHeight() > 0 && track.y >= header.getHeight());
        assertTrue("The horizontal scrollbar must really be visible", horizontal.isVisible());
        assertEquals(viewportTop.y, track.y);
        assertEquals("An overlay scrollbar must also be excluded from the overview track",
                Math.min(viewport.getHeight(), scrollbarTop.y - viewportTop.y), track.height);
        assertTrue("The overview must end before the horizontal scrollbar", track.y + track.height < stripe.getHeight());
        assertTrue("The source must really be horizontally scrolled", editor.getScrollingModel().getVisibleArea().x > 0);
        assertFallback(editor, stripe, snapshot);
        var before = stripe.bands();

        scroll(editor, rowY(editor, 100));

        assertEquals(track, stripe.trackBounds());
        assertEquals("Neither source scroll direction changes the whole-file scale", before, stripe.bands());
    }

    public void testClickAndDragNavigateAcrossTheWholeFileAndCenterTheTarget() {
        var editor = open("navigation.py");
        sizeHost(editor, 820, 350);
        var stripe = stripe(editor);
        var snapshot = snapshot(editor.getDocument());
        stripe.setSnapshot(snapshot);
        scroll(editor, rowY(editor, 100));
        var bands = stripe.bands();
        var track = stripe.trackBounds();
        int clickPixel = track.height * 3 / 4;
        var expectedClick = ReviewStripeModel.positionAt(editor, clickPixel, track.height);
        var beforeClick = new Rectangle(editor.getScrollingModel().getVisibleArea());

        stripe.dispatchEvent(new MouseEvent(stripe, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK,
                4, track.y + clickPixel, 1, false, MouseEvent.BUTTON1));

        assertEquals(expectedClick, editor.getCaretModel().getVisualPosition());
        assertTrue("A three-quarter click must reach distant code, not a row in the former viewport",
                editor.getCaretModel().getLogicalPosition().line > 700);
        assertTrue(editor.getScrollingModel().getVisibleArea().y > beforeClick.y);
        assertCaretInCentralArea(editor);
        assertEquals(bands, stripe.bands());
        int dragPixel = track.height / 4;
        var expectedDrag = ReviewStripeModel.positionAt(editor, dragPixel, track.height);

        stripe.dispatchEvent(new MouseEvent(stripe, MouseEvent.MOUSE_DRAGGED, 0, InputEvent.BUTTON1_DOWN_MASK,
                4, track.y + dragPixel, 0, false, MouseEvent.NOBUTTON));

        assertEquals(expectedDrag, editor.getCaretModel().getVisualPosition());
        assertTrue("Dragging up must address the earlier quarter of the whole document",
                editor.getCaretModel().getLogicalPosition().line > 200 && editor.getCaretModel().getLogicalPosition().line < 300);
        assertCaretInCentralArea(editor);
        assertEquals(bands, stripe.bands());
        stripe.dispatchEvent(new MouseEvent(stripe, MouseEvent.MOUSE_RELEASED, 0, 0,
                4, track.y + dragPixel, 1, false, MouseEvent.BUTTON1));
    }

    public void testRealSplitEditorsScaleByTheirOwnTrackHeight() {
        var first = open("split.py");
        var file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(first.getDocument());
        var manager = FileEditorManagerEx.getInstanceEx(getProject());
        assertNotNull(manager.getCurrentWindow());
        manager.getCurrentWindow().split(SwingConstants.VERTICAL, true, file, false);
        PlatformTestUtil.waitWithEventsDispatching("The real split did not create two loaded text editors",
                () -> textEditors(file).size() == 2 && textEditors(file).stream().allMatch(TextEditor::isEditorLoaded), 10);
        var second = textEditors(file).stream().map(TextEditor::getEditor).filter(editor -> editor != first).findFirst().orElseThrow();
        assertSame(first.getDocument(), second.getDocument());
        fresh(first.getDocument());
        sizeHost(first, 720, 330);
        sizeHost(second, 680, 510);
        scroll(first, rowY(first, 200));
        scroll(second, rowY(second, 600));
        var shared = snapshot(first.getDocument());
        var firstStripe = stripe(first);
        var secondStripe = stripe(second);
        firstStripe.setSnapshot(shared);
        secondStripe.setSnapshot(shared);
        assertNotSame(firstStripe, secondStripe);
        assertTrue(firstStripe.trackBounds().height < secondStripe.trackBounds().height);
        assertTrue("The taller split must put the same document block lower in its own overview",
                band(firstStripe, ReviewStripeModel.Kind.NEEDS_REVIEW).y() < band(secondStripe, ReviewStripeModel.Kind.NEEDS_REVIEW).y());
        assertFallback(first, firstStripe, shared);
        assertFallback(second, secondStripe, shared);
        var firstBands = firstStripe.bands();
        var secondBands = secondStripe.bands();

        scroll(first, rowY(first, 700));

        assertEquals(firstBands, firstStripe.bands());
        assertEquals("Scrolling one split must not move the other's overview", secondBands, secondStripe.bands());
        sizeHost(first, 720, 460);
        assertFalse("Resizing one split must update that split's whole-file scale", firstBands.equals(firstStripe.bands()));
        assertEquals("Resizing one split must not change the other's scale", secondBands, secondStripe.bands());
        assertFallback(first, firstStripe, shared);
    }

    private Editor open(String name) {
        var source = new StringBuilder();
        for (int row = 0; row < 1000; row++) source.append("# row ").append(row).append(' ').append("x".repeat(150)).append('\n');
        var file = myFixture.addFileToProject(name, source.toString()).getVirtualFile();
        var editor = FileEditorManager.getInstance(getProject()).openTextEditor(new OpenFileDescriptor(getProject(), file), true);
        assertNotNull("Use the real file editor rather than the CodeInsight fixture's unhosted editor", editor);
        assertSame(editor, FileEditorManager.getInstance(getProject()).getSelectedTextEditor());
        assertEquals(EditorKind.MAIN_EDITOR, editor.getEditorKind());
        PlatformTestUtil.waitWithEventsDispatching("The real text editor never acquired its SDK host",
                () -> ReviewStripeHost.find(editor) != null && textEditors(file).stream().allMatch(TextEditor::isEditorLoaded), 10);
        editor.getSettings().setUseSoftWraps(false);
        editor.getScrollingModel().disableAnimation();
        fresh(editor.getDocument());
        return editor;
    }

    private List<TextEditor> textEditors(VirtualFile file) {
        return Arrays.stream(FileEditorManager.getInstance(getProject()).getAllEditors(file))
                .filter(TextEditor.class::isInstance).map(TextEditor.class::cast).toList();
    }

    private void fresh(Document document) {
        PlatformTestUtil.waitWithEventsDispatching("Current overview projection did not finish", () -> service.isProjectionReady(document), 10);
        PlatformTestUtil.waitWithEventsDispatching("Current overview stripe snapshot did not finish", () ->
                Arrays.stream(EditorFactory.getInstance().getEditors(document, getProject()))
                        .filter(editor -> ReviewStripeHost.find(editor) != null)
                        .allMatch(editor -> stripe(editor).currentSnapshot() != null), 10);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
    }

    private static ReviewStripeModel.Snapshot snapshot(Document document) {
        return new ReviewStripeModel.Snapshot(document.getModificationStamp(), List.of(
                new ReviewStripeModel.Run(document.getLineStartOffset(200), document.getLineStartOffset(260), ReviewStripeModel.Kind.REVIEWED),
                new ReviewStripeModel.Run(document.getLineStartOffset(650), document.getLineStartOffset(730), ReviewStripeModel.Kind.NEEDS_REVIEW)));
    }

    private static void assertFallback(Editor editor, ReviewStripePanel stripe, ReviewStripeModel.Snapshot snapshot) {
        assertNull("These tests deliberately cover operation without an active CodeGlance minimap",
                ReviewStripeCodeGlanceBridge.mapping(editor, stripe));
        assertEquals("Panel rendering must select the model's whole-file fallback for its actual track height",
                ReviewStripeModel.bands(editor, snapshot, stripe.trackBounds().height), stripe.bands());
    }

    private static ReviewStripePanel stripe(Editor editor) {
        var host = ReviewStripeHost.find(editor);
        assertNotNull("The editor must retain its real SDK host", host);
        return Arrays.stream(host.getComponents()).filter(ReviewStripePanel.class::isInstance)
                .map(ReviewStripePanel.class::cast).findFirst().orElseThrow();
    }

    private static ReviewStripeModel.Band band(ReviewStripePanel stripe, ReviewStripeModel.Kind kind) {
        var matching = stripe.bands().stream().filter(candidate -> candidate.kind() == kind).toList();
        assertEquals(1, matching.size());
        return matching.getFirst();
    }

    private static void assertCaretInCentralArea(Editor editor) {
        var viewport = editor.getScrollingModel().getVisibleArea();
        int caretY = editor.visualPositionToXY(editor.getCaretModel().getVisualPosition()).y;
        // The platform's CENTER policy places the caret above the mathematical midpoint.
        // Require an interior target, rather than duplicating the SDK's exact scroll arithmetic.
        int relativeY = caretY - viewport.y;
        assertTrue("Overview navigation must scroll the target into the central part of the code viewport: caret=" + caretY
                        + ", viewport=" + viewport + ", lineHeight=" + editor.getLineHeight(),
                relativeY >= viewport.height / 4 && relativeY + editor.getLineHeight() <= viewport.height * 3 / 4);
    }

    private static int rowY(Editor editor, int row) {
        return editor.offsetToXY(editor.getDocument().getLineStartOffset(row)).y;
    }

    private static void scroll(Editor editor, int y) {
        editor.getScrollingModel().scrollVertically(y);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
    }

    private static void sizeHost(Editor editor, int width, int height) {
        var host = ReviewStripeHost.find(editor);
        assertNotNull(host);
        host.setSize(width, height);
        layout(host);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        layout(host);
        assertTrue(stripe(editor).trackBounds().height > 0);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
