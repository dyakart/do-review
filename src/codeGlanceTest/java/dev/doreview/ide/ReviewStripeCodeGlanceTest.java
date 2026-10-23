package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.testFramework.FileEditorManagerTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.ui.scale.Scale;
import com.intellij.ui.scale.ScaleType;
import com.nasller.codeglance.config.CodeGlanceConfig;
import com.nasller.codeglance.config.CodeGlanceConfigKt;
import com.nasller.codeglance.config.CodeGlanceConfigService;
import com.nasller.codeglance.config.enums.EditorSizeEnum;
import com.nasller.codeglance.panel.GlancePanel;
import com.nasller.codeglance.render.BaseMinimap;

import javax.imageio.ImageIO;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Opt-in tests against the installed plugin. Native VCS painting is the coordinate oracle. */
public final class ReviewStripeCodeGlanceTest extends FileEditorManagerTestCase {
    private CodeGlanceConfig config;
    private SavedConfig saved;
    private DoReviewService service;

    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var projectFixture = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(projectFixture, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(projectFixture.getProject().getBasePath()); }
        });
    }

    @Override public void setUp() {
        super.setUp();
        config = CodeGlanceConfigService.Companion.getConfig();
        saved = new SavedConfig(config);
        config.setDisabled(false);
        config.setRightAligned(true);
        config.setEditorKindsStr("MAIN_EDITOR");
        config.setMinLinesCount(0);
        config.setMaxLinesCount(Integer.MAX_VALUE);
        config.setDisableLanguageSuffix("");
        config.setUseEmptyMinimapStr("");
        config.setUseFastMinimapForMain(false);
        config.setPixelsPerLine(2);
        config.setEditorSize(EditorSizeEnum.Proportional);
        config.setShowVcsHighlight(true);
        config.setHideOriginalScrollBar(false);
        config.setEnableHiDpi(true);
        ReviewHistoryStore.getInstance().loadState(new ReviewHistoryStore.StoreState());
        service = DoReviewService.getInstance(getProject());
        service.historyReloaded();
        ReviewStripeSettings.getInstance(getProject()).setShown(true);
    }

    @Override public void tearDown() throws Exception {
        try {
            if (saved != null) {
                saved.restore(config);
                CodeGlanceConfigKt.getSettingsChangePublisher().onGlobalChanged();
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            }
        } finally {
            super.tearDown();
        }
    }

    public void testNativeProportional() {
        var editor = open("prop.py");
        sizeHost(editor, 900, 370);
        scroll(editor, 300);
        var glance = glance(editor);
        var span = centerSpan(editor, glance);
        var nativePixels = assertNative(editor, span);

        assertTrue("The regression needs CodeGlance to crop its long minimap", glance.getScrollState().getVisibleStart() > 0);
        int nativeTop = first(nativePixels);
        int normalizedTop = stripe(editor).trackBounds().y + (int) Math.round(
                editor.offsetToVisualPosition(span.start).line * stripe(editor).trackBounds().height
                        / (double) ((com.intellij.openapi.editor.impl.EditorImpl) editor).getVisibleLineCount());
        assertTrue("A separate whole-file height normalization must fail this fixture; it cannot replace CodeGlance's crop",
                Math.abs(nativeTop - normalizedTop) > 8);

        scroll(editor, 600);
        assertNative(editor, centerSpan(editor, glance(editor)));
    }

    public void testNativeFit() {
        config.setEditorSize(EditorSizeEnum.Fit);
        var editor = open("fit.py");
        sizeHost(editor, 850, 350);
        scroll(editor, 100);
        var document = editor.getDocument();
        var span = new Span(document.getLineStartOffset(720), document.getLineStartOffset(736));
        var nativePixels = assertNative(editor, span);
        assertTrue("Fit mode must include review marks far outside the editor viewport", first(nativePixels) >= 0);
        assertTrue("The chosen row must not be in the editor viewport", editor.offsetToXY(span.start).y
                > editor.getScrollingModel().getVisibleArea().y + editor.getScrollingModel().getVisibleArea().height);
        assertEquals(0, glance(editor).getScrollState().getVisibleStart());
    }

    public void testNativeFast() {
        config.setUseFastMinimapForMain(true);
        var editor = open("fast.py");
        sizeHost(editor, 890, 350);
        scroll(editor, 520);
        assertEquals("Exercise the installed default renderer rather than only MainMinimap",
                "com.nasller.codeglance.render.FastMainMinimap", minimap(glance(editor)).getClass().getName());
        assertNative(editor, centerSpan(editor, glance(editor)));
        config.setEditorSize(EditorSizeEnum.Fit);
        glance(editor).updateScrollState(editor.getScrollingModel().getVisibleArea(), false);
        assertNative(editor, centerSpan(editor, glance(editor)));
    }

    public void testNativeInlay() {
        config.setEditorSize(EditorSizeEnum.Fit);
        var editor = open("inlay.py");
        sizeHost(editor, 850, 350);
        int offset = editor.getDocument().getLineStartOffset(300);
        var inlay = editor.getInlayModel().addBlockElement(offset, false, true, 0, new EditorCustomElementRenderer() {
            @Override public int calcWidthInPixels(Inlay inlay) { return 100; }
            @Override public int calcHeightInPixels(Inlay inlay) { return editor.getLineHeight() * 6; }
            @Override public void paint(Inlay inlay, Graphics graphics, Rectangle targetRegion, TextAttributes attributes) { }
        });
        assertNotNull(inlay);
        try {
            sizeHost(editor, 850, 350);
            assertTrue("The fixture needs a real layout-affecting block inlay", inlay.getHeightInPixels() > editor.getLineHeight());
            var document = editor.getDocument();
            assertNative(editor, new Span(document.getLineStartOffset(330), document.getLineStartOffset(346)));
        } finally { inlay.dispose(); }
    }

    public void testNativeNavigationAndStale() {
        var editor = open("nav.py");
        sizeHost(editor, 860, 350);
        scroll(editor, 410);
        var glance = glance(editor);
        var stripe = stripe(editor);
        assertNative(editor, centerSpan(editor, glance));
        int pixel = stripe.trackBounds().height * 3 / 4;
        int expectedLine = glance.getMyRenderVisualLine((int) (pixel / glance.getPixScale())
                + glance.getScrollState().getVisibleStart());
        expectedLine = Math.max(0, Math.min(expectedLine,
                ((com.intellij.openapi.editor.impl.EditorImpl) editor).getVisibleLineCount() - 1));
        stripe.dispatchEvent(new MouseEvent(stripe, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK,
                4, stripe.trackBounds().y + pixel, 1, false, MouseEvent.BUTTON1));
        stripe.dispatchEvent(new MouseEvent(stripe, MouseEvent.MOUSE_RELEASED, 0, 0,
                4, stripe.trackBounds().y + pixel, 1, false, MouseEvent.BUTTON1));
        assertEquals("A review-lane click must use CodeGlance's own inverse coordinate transform",
                expectedLine, editor.getCaretModel().getVisualPosition().line);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            editor.getDocument().insertString(0, "# edit invalidates review coordinates\n");
            assertNull("A synchronous document change must hide the old stripe snapshot", stripe.currentSnapshot());
            assertTrue(stripe.bands().isEmpty());
            assertEquals("Stale review paint must be empty even while CodeGlance still has an old image", -1, first(stripeRows(stripe)));
        });
    }

    public void testNativeScaleRebuild() {
        var editor = open("scale.py");
        sizeHost(editor, 880, 350);
        scroll(editor, 420);
        var stripe = stripe(editor);
        for (double scale : new double[] {1.0, 1.5, 2.0}) {
            var glance = glance(editor);
            // Change this disposable panel's scale context, never the application/JBUIScale setting.
            glance.getScaleContext().overrideScale(new Scale(1.0, ScaleType.USR_SCALE));
            glance.getScaleContext().overrideScale(new Scale(1.0, ScaleType.SYS_SCALE));
            glance.getScaleContext().overrideScale(new Scale(scale, ScaleType.OBJ_SCALE));
            assertEquals(scale, glance.getPixScale(), 0.0001);
            glance.updateScrollState(editor.getScrollingModel().getVisibleArea(), false);
            assertNative(editor, centerSpan(editor, glance));
        }

        var oldGlance = glance(editor);
        config.setHideOriginalScrollBar(true);
        CodeGlanceConfigKt.getSettingsChangePublisher().onGlobalChanged();
        PlatformTestUtil.waitWithEventsDispatching("CodeGlance did not rebuild after hiding the original scrollbar",
                () -> currentGlance(editor) != null && currentGlance(editor) != oldGlance, 10);
        sizeHost(editor, 880, 350);
        assertTrue(oldGlance.isReleased());
        assertSame("Rebuilding CodeGlance must preserve the independently owned review stripe", stripe, stripe(editor));
        assertNotNull("Use CodeGlance's actual separate VCS panel in this configuration", glance(editor).getMyVcsPanel());
        assertNative(editor, centerSpan(editor, glance(editor)));
    }

    public void testNativeSplitFoldWrap() {
        var first = open("split.py");
        var file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(first.getDocument());
        var manager = FileEditorManagerEx.getInstanceEx(getProject());
        assertNotNull(manager.getCurrentWindow());
        manager.getCurrentWindow().split(SwingConstants.VERTICAL, true, file, false);
        PlatformTestUtil.waitWithEventsDispatching("The real split did not load both editors and minimaps",
                () -> textEditors(file).size() == 2 && textEditors(file).stream().allMatch(TextEditor::isEditorLoaded)
                        && textEditors(file).stream().allMatch(text -> currentGlance(text.getEditor()) != null), 10);
        var second = textEditors(file).stream().map(TextEditor::getEditor).filter(editor -> editor != first).findFirst().orElseThrow();
        assertSame(first.getDocument(), second.getDocument());
        for (var editor : List.of(first, second)) {
            editor.getSettings().setUseSoftWraps(true);
            editor.getSettings().setSoftMargins(List.of(80));
            editor.getFoldingModel().runBatchFoldingOperation(() -> {
                var region = editor.getFoldingModel().addFoldRegion(editor.getDocument().getLineStartOffset(100),
                        editor.getDocument().getLineStartOffset(200), "folded rows");
                assertNotNull(region);
                region.setExpanded(false);
            });
        }
        sizeHost(first, 640, 330);
        sizeHost(second, 700, 290);
        scroll(first, 250);
        scroll(second, 640);
        assertFalse("Real soft wraps must be present", first.getSoftWrapModel()
                .getSoftWrapsForRange(0, first.getDocument().getTextLength()).isEmpty());
        assertNotSame(glance(first), glance(second));
        assertTrue("Split minimaps must retain their own scroll state", glance(first).getScrollState().getVisibleStart()
                != glance(second).getScrollState().getVisibleStart());
        assertNative(first, centerSpan(first, glance(first)));
        assertNative(second, centerSpan(second, glance(second)));
        var secondBands = stripe(second).bands();
        scroll(first, 350);
        assertEquals("Scrolling one editor must not move the other minimap's review bands", secondBands, stripe(second).bands());
        assertNative(first, centerSpan(first, glance(first)));
    }

    public void testPreview() throws Exception {
        config.setUseFastMinimapForMain(true);
        var editor = open("preview.py");
        sizeHost(editor, 1120, 650);
        scroll(editor, 300);
        var document = editor.getDocument();
        var green = new Span(document.getLineStartOffset(270), document.getLineStartOffset(315));
        var yellow = new Span(document.getLineStartOffset(315), document.getLineStartOffset(365));
        var markup = DocumentMarkupModel.forDocument(document, getProject(), true);
        var greenAttributes = ReviewHighlighting.attributesFor(editor.getColorsScheme());
        var yellowAttributes = ReviewHighlighting.needsReviewAttributesFor(editor.getColorsScheme());
        var greenCode = markup.addRangeHighlighter(green.start, green.end - 1, HighlighterLayer.ADDITIONAL_SYNTAX,
                greenAttributes, HighlighterTargetArea.LINES_IN_RANGE);
        var yellowCode = markup.addRangeHighlighter(yellow.start, yellow.end - 1, HighlighterLayer.ADDITIONAL_SYNTAX,
                yellowAttributes, HighlighterTargetArea.LINES_IN_RANGE);
        try {
            var minimap = minimap(glance(editor));
            minimap.rebuildDataAndImage();
            PlatformTestUtil.waitWithEventsDispatching("The actual CodeGlance minimap image did not finish rendering",
                    () -> minimap.getImageOrUpdate() != null, 10);
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            stripe(editor).setSnapshot(new ReviewStripeModel.Snapshot(document.getModificationStamp(), List.of(
                    new ReviewStripeModel.Run(green.start, green.end, ReviewStripeModel.Kind.REVIEWED),
                    new ReviewStripeModel.Run(yellow.start, yellow.end, ReviewStripeModel.Kind.NEEDS_REVIEW))));
            var host = ReviewStripeHost.find(editor);
            assertNotNull(host);
            var image = new BufferedImage(host.getWidth(), host.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            try { host.paint(graphics); } finally { graphics.dispose(); }
            var output = Path.of("build", "preview", "do-review-codeglance-aligned.png");
            Files.createDirectories(output.getParent());
            assertTrue(ImageIO.write(image, "png", output.toFile()));
        } finally {
            markup.removeHighlighter(greenCode);
            markup.removeHighlighter(yellowCode);
        }
    }

    /** Compare actual painted rows, including CodeGlance's integer rectangles and fractional DPI transform. */
    private boolean[] assertNative(Editor editor, Span span) {
        var stripe = stripe(editor);
        var glance = glance(editor);
        glance.updateScrollState(editor.getScrollingModel().getVisibleArea(), false);
        var origin = SwingUtilities.convertPoint(glance, 0, 0, stripe);
        assertEquals("Review and minimap must start at the same component y", origin.y, stripe.trackBounds().y);
        assertEquals(glance.getHeight(), stripe.trackBounds().height);
        var snapshot = new ReviewStripeModel.Snapshot(editor.getDocument().getModificationStamp(), List.of(
                new ReviewStripeModel.Run(span.start, span.end, ReviewStripeModel.Kind.REVIEWED)));
        stripe.setSnapshot(snapshot);

        var markup = DocumentMarkupModel.forDocument(editor.getDocument(), getProject(), true);
        var marker = markup.addRangeHighlighter(span.start, span.end - 1, HighlighterLayer.ADDITIONAL_SYNTAX,
                new TextAttributes(), HighlighterTargetArea.EXACT_RANGE);
        marker.setThinErrorStripeMark(true);
        marker.setErrorStripeMarkColor(Color.MAGENTA);
        boolean[] expected;
        try {
            var image = new BufferedImage(64, stripe.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            try {
                graphics.translate(0, origin.y);
                graphics.scale(glance.getPixScale(), glance.getPixScale());
                // This is the exact method called by native MyVcsPanel.paintComponent.
                glance.paintVcs(graphics, glance.getVisibleRangeOffset(), 8);
            } finally { graphics.dispose(); }
            expected = alphaRows(image, 2);
        } finally { markup.removeHighlighter(marker); }

        assertTrue("Native CodeGlance VCS oracle must actually paint the test range", first(expected) >= 0);
        var actual = stripeRows(stripe);
        assertEquals("Review stripe must occupy precisely the native VCS marker rows. Native " + bounds(expected)
                        + ", review " + bounds(actual), Arrays.toString(expected), Arrays.toString(actual));
        return expected;
    }

    private static boolean[] stripeRows(ReviewStripePanel stripe) {
        var snapshot = stripe.currentSnapshot();
        stripe.setSnapshot(null);
        var empty = paint(stripe);
        stripe.setSnapshot(snapshot);
        var marked = paint(stripe);
        var result = new boolean[stripe.getHeight()];
        int x = stripe.getWidth() / 2;
        for (int y = 0; y < result.length; y++) result[y] = empty.getRGB(x, y) != marked.getRGB(x, y);
        return result;
    }

    private static BufferedImage paint(ReviewStripePanel stripe) {
        var image = new BufferedImage(stripe.getWidth(), stripe.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { stripe.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    private static boolean[] alphaRows(BufferedImage image, int x) {
        var result = new boolean[image.getHeight()];
        for (int y = 0; y < result.length; y++) result[y] = image.getRGB(x, y) >>> 24 != 0;
        return result;
    }

    private static int first(boolean[] pixels) {
        for (int y = 0; y < pixels.length; y++) if (pixels[y]) return y;
        return -1;
    }

    private static String bounds(boolean[] pixels) {
        int last = -1;
        for (int y = 0; y < pixels.length; y++) if (pixels[y]) last = y;
        return first(pixels) + ".." + last;
    }

    private static Span centerSpan(Editor editor, GlancePanel glance) {
        glance.updateScrollState(editor.getScrollingModel().getVisibleArea(), false);
        var state = glance.getScrollState();
        int visual = glance.getMyRenderVisualLine(state.getVisibleStart() + state.getDrawHeight() * 3 / 4);
        int row = editor.visualToLogicalPosition(new VisualPosition(visual, 0)).line;
        row = Math.max(0, Math.min(row, editor.getDocument().getLineCount() - 10));
        return new Span(editor.getDocument().getLineStartOffset(row), editor.getDocument().getLineStartOffset(row + 6));
    }

    private Editor open(String name) {
        var source = new StringBuilder();
        for (int row = 0; row < 1000; row++) source.append("# row ").append(row).append(' ').append("x".repeat(150)).append('\n');
        var file = myFixture.addFileToProject(name, source.toString()).getVirtualFile();
        var editor = FileEditorManager.getInstance(getProject()).openTextEditor(new OpenFileDescriptor(getProject(), file), true);
        assertNotNull(editor);
        assertEquals(EditorKind.MAIN_EDITOR, editor.getEditorKind());
        PlatformTestUtil.waitWithEventsDispatching("The actual editor did not load its SDK host and CodeGlance panel",
                () -> ReviewStripeHost.find(editor) != null && currentGlance(editor) != null
                        && textEditors(file).stream().allMatch(TextEditor::isEditorLoaded), 10);
        editor.getSettings().setUseSoftWraps(false);
        editor.getScrollingModel().disableAnimation();
        PlatformTestUtil.waitWithEventsDispatching("Review projection did not become current",
                () -> service.isProjectionReady(editor.getDocument()), 10);
        return editor;
    }

    private List<TextEditor> textEditors(VirtualFile file) {
        return Arrays.stream(FileEditorManager.getInstance(getProject()).getAllEditors(file))
                .filter(TextEditor.class::isInstance).map(TextEditor.class::cast).toList();
    }

    private static GlancePanel currentGlance(Editor editor) {
        var glance = editor.getUserData(GlancePanel.Companion.getCURRENT_GLANCE());
        return glance != null && !glance.isReleased() && glance.getEditor() == editor ? glance : null;
    }

    private static GlancePanel glance(Editor editor) {
        var result = currentGlance(editor);
        assertNotNull("The installed CodeGlance listener must supply its real panel", result);
        return result;
    }

    private static BaseMinimap minimap(GlancePanel glance) {
        try {
            var field = GlancePanel.class.getDeclaredField("minimap");
            assertTrue("The opt-in test must inspect the actual renderer", field.trySetAccessible());
            return (BaseMinimap) field.get(glance);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static ReviewStripePanel stripe(Editor editor) {
        var host = ReviewStripeHost.find(editor);
        assertNotNull(host);
        return Arrays.stream(host.getComponents()).filter(ReviewStripePanel.class::isInstance)
                .map(ReviewStripePanel.class::cast).findFirst().orElseThrow();
    }

    private static void scroll(Editor editor, int row) {
        editor.getScrollingModel().scrollVertically(editor.offsetToXY(editor.getDocument().getLineStartOffset(row)).y);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        glance(editor).updateScrollState(editor.getScrollingModel().getVisibleArea(), false);
    }

    private static void sizeHost(Editor editor, int width, int height) {
        var host = ReviewStripeHost.find(editor);
        assertNotNull(host);
        host.setSize(width, height);
        layout(host);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        layout(host);
        glance(editor).updateScrollState(editor.getScrollingModel().getVisibleArea(), false);
        assertTrue(glance(editor).getHeight() > 0);
        assertTrue(stripe(editor).getHeight() > 0);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private record Span(int start, int end) { }

    private record SavedConfig(boolean disabled, boolean right, String kinds, int minLines, int maxLines,
                               String disabledLanguages, String emptyKinds, boolean fast, int pixels,
                               EditorSizeEnum size, boolean vcs, boolean hideScroll, boolean hiDpi) {
        private SavedConfig(CodeGlanceConfig config) {
            this(config.getDisabled(), config.isRightAligned(), config.getEditorKindsStr(), config.getMinLinesCount(),
                    config.getMaxLinesCount(), config.getDisableLanguageSuffix(), config.getUseEmptyMinimapStr(),
                    config.getUseFastMinimapForMain(), config.getPixelsPerLine(), config.getEditorSize(),
                    config.getShowVcsHighlight(), config.getHideOriginalScrollBar(), config.getEnableHiDpi());
        }

        private void restore(CodeGlanceConfig config) {
            config.setDisabled(disabled);
            config.setRightAligned(right);
            config.setEditorKindsStr(kinds);
            config.setMinLinesCount(minLines);
            config.setMaxLinesCount(maxLines);
            config.setDisableLanguageSuffix(disabledLanguages);
            config.setUseEmptyMinimapStr(emptyKinds);
            config.setUseFastMinimapForMain(fast);
            config.setPixelsPerLine(pixels);
            config.setEditorSize(size);
            config.setShowVcsHighlight(vcs);
            config.setHideOriginalScrollBar(hideScroll);
            config.setEnableHiDpi(hiDpi);
        }
    }
}
