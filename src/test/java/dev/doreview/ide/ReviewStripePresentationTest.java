package dev.doreview.ide;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.impl.text.TextEditorComponent;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.ui.JBUI;
import dev.doreview.core.ContextMap;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Color;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real editor host, cached rendering and independent project-wide stripe resources. */
public final class ReviewStripePresentationTest extends BasePlatformTestCase {
    private DoReviewService service;
    private ReviewStripeSettings settings;

    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var projectFixture = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(projectFixture, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(projectFixture.getProject().getBasePath()); }
        });
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        ReviewHistoryStore.getInstance().loadState(new ReviewHistoryStore.StoreState());
        settings = ReviewStripeSettings.getInstance(getProject());
        settings.setShown(false);
        service = DoReviewService.getInstance(getProject());
        service.historyReloaded();
    }

    public void testToggleNestedFiles() {
        var first = open("first/a.py", "first = 1\n");
        var second = open("nested/project/b.py", "second = 2\n");
        var history = service.history(first.getDocument());
        assertNull(panel(first)); assertNull(panel(second));

        settings.setShown(true); flush();
        assertNotNull(panel(first)); assertNotNull(panel(second));
        assertEquals(JBUI.scale(14), panel(first).getPreferredSize().width);
        assertEquals(history, service.history(first.getDocument()));

        settings.setShown(false);
        assertNull(panel(first)); assertNull(panel(second));
        settings.setShown(true);
        assertNotNull(panel(first)); assertNotNull(panel(second));
        assertEquals(1, stripeCount(first));
        settings.loadState(new ReviewStripeSettings.State());
        assertNull(panel(first)); assertNull(panel(second));
        var loaded = new ReviewStripeSettings.State(); loaded.showReviewStripe = true;
        settings.loadState(loaded);
        assertNotNull(panel(first)); assertNotNull(panel(second));
    }

    public void testMarkClearAndBlank() {
        var editor = open("a.py", "first = 1\n\nsecond = 2\n");
        settings.setShown(true);
        myFixture.getEditor().getSelectionModel().setSelection(0, editor.getDocument().getTextLength());
        myFixture.performEditorAction("ReviewMarks.Mark");
        fresh(editor.getDocument());
        var panel = panel(editor);
        assertNotNull(panel);
        assertEquals(2L, panel.currentSnapshot().runs().stream().filter(run -> run.kind() == ReviewStripeModel.Kind.REVIEWED).count());
        assertEquals(2L, panel.currentSnapshot().runs().stream().filter(run -> run.kind() == ReviewStripeModel.Kind.BLANK).count());
        assertEquals(2, service.coverage(editor.getDocument()).reviewed());
        assertEquals(2, service.coverage(editor.getDocument()).total());
        assertEquals(-1, service.nextUnreviewedLine(editor.getDocument(), 0));
        assertTrue(Arrays.stream(editor.getMarkupModel().getAllHighlighters())
                .filter(highlighter -> Boolean.TRUE.equals(highlighter.getUserData(ReviewMarkKeys.REVIEWED_HIGHLIGHTER)))
                .noneMatch(highlighter -> highlighter.getStartOffset() == editor.getDocument().getLineStartOffset(1)));
        var history = service.history(editor.getDocument());
        settings.setShown(false); settings.setShown(true);
        assertEquals(history, service.history(editor.getDocument()));

        myFixture.performEditorAction("ReviewMarks.Clear");
        fresh(editor.getDocument());
        assertTrue(panel(editor).currentSnapshot().runs().stream().allMatch(run ->
                run.kind() == ReviewStripeModel.Kind.BLANK || run.kind() == ReviewStripeModel.Kind.UNREVIEWED));
        assertEquals(0, service.coverage(editor.getDocument()).reviewed());
        assertEquals(2, service.coverage(editor.getDocument()).total());
        assertEquals(2, service.nextUnreviewedLine(editor.getDocument(), 0));
    }

    public void testLateHostAndRelease() {
        var file = myFixture.addFileToProject("late.py", "value = 1\n").getVirtualFile();
        var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
        var editor = EditorFactory.getInstance().createEditor(document, getProject(), file, false, EditorKind.MAIN_EDITOR);
        try {
            settings.setShown(true);
            fresh(document);
            assertNull(ReviewStripeHost.find(editor));
            var host = new TextEditorComponent(file, (EditorImpl) editor);
            assertSame(host, ReviewStripeHost.find(editor));
            assertNotNull(panel(editor));
            assertEquals(1, stripeCount(editor));
            EditorFactory.getInstance().releaseEditor(editor);
            assertFalse(Arrays.stream(host.getComponents()).anyMatch(ReviewStripePanel.class::isInstance));
        } finally { if (!editor.isDisposed()) EditorFactory.getInstance().releaseEditor(editor); }
    }

    public void testPreserveInnerLayout() {
        var editor = open("a.py", "value = 1\n");
        var root = editor.getComponent();
        assertInstanceOf(root.getLayout(), BorderLayout.class);
        var layout = (BorderLayout) root.getLayout();
        var existing = layout.getLayoutComponent(BorderLayout.LINE_END);
        // An independent minimap owns this slot; stripe attachment must never wrap it.
        var minimap = existing == null ? new JPanel() : existing;
        if (existing == null) root.add(minimap, BorderLayout.LINE_END);
        try {
            int baselineListeners = editor.getContentComponent().getComponentListeners().length;
            int baselineContainerListeners = root.getContainerListeners().length;
            for (int i = 0; i < 12; i++) {
                settings.setShown(true);
                editor.getSettings().setUseSoftWraps(i % 2 == 0);
                assertSame(minimap, layout.getLayoutComponent(BorderLayout.LINE_END));
                assertSame(root, minimap.getParent());
                assertEquals(1, stripeCount(editor));
                settings.setShown(false);
                assertNull(panel(editor));
                assertSame(minimap, layout.getLayoutComponent(BorderLayout.LINE_END));
                assertEquals(baselineListeners, editor.getContentComponent().getComponentListeners().length);
                assertEquals(baselineContainerListeners, root.getContainerListeners().length);
            }
        } finally { if (existing == null) root.remove(minimap); }
    }

    public void testSplitSnapshotsAndDisposal() {
        var first = open("a.py", "first = 1\nsecond = 2\nthird = 3\n");
        var document = first.getDocument();
        var virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document);
        var second = EditorFactory.getInstance().createEditor(document, getProject(), virtualFile, false, EditorKind.MAIN_EDITOR);
        try {
            new TextEditorComponent(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document), (EditorImpl) second);
            settings.setShown(true);
            service.mark(document, 0, document.getTextLength());
            fresh(document);
            assertNotSame(panel(first), panel(second));
            assertSame(panel(first).currentSnapshot(), panel(second).currentSnapshot());
            var survivor = panel(first);
            EditorFactory.getInstance().releaseEditor(second);
            assertSame(survivor, panel(first));
            assertNotNull(survivor.currentSnapshot());
        } finally { if (!second.isDisposed()) EditorFactory.getInstance().releaseEditor(second); }
    }

    public void testViewportAndNavigation() {
        var editor = open("a.py", "# line\n".repeat(100));
        ((EditorEx) editor).setHeaderComponent(new JPanel() {{ setPreferredSize(new java.awt.Dimension(0, 30)); }});
        settings.setShown(true);
        var host = ReviewStripeHost.find(editor);
        host.setSize(900, 350); layout(host);
        var stripe = panel(editor);
        var track = stripe.trackBounds();
        assertTrue(track.y >= 30);
        assertTrue(track.height > 0);
        assertTrue(track.y + track.height <= stripe.getHeight());
        stripe.dispatchEvent(new MouseEvent(stripe, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK,
                4, track.y + track.height - 1, 1, false, MouseEvent.BUTTON1));
        assertTrue(editor.getCaretModel().getLogicalPosition().line >= 98);
        stripe.dispatchEvent(new MouseEvent(stripe, MouseEvent.MOUSE_DRAGGED, 0, InputEvent.BUTTON1_DOWN_MASK,
                4, track.y, 0, false, MouseEvent.NOBUTTON));
        assertEquals(0, editor.getCaretModel().getLogicalPosition().line);
        stripe.dispatchEvent(new MouseEvent(stripe, MouseEvent.MOUSE_RELEASED, 0, 0,
                4, track.y + track.height - 1, 1, false, MouseEvent.BUTTON1));
    }

    public void testScaleAndTheme() {
        var editor = open("colors.py", "first\nsecond\nthird\nlast");
        settings.setShown(true);
        fresh(editor.getDocument());
        var host = ReviewStripeHost.find(editor);
        host.setSize(800, 100); layout(host);
        var stripe = panel(editor);
        var scheme = new com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl(editor.getColorsScheme());
        Color green = new Color(0x225533), yellow = new Color(0x998811), changed = new Color(0x336699), deleted = new Color(0xaa2233);
        var reviewed = new TextAttributes(); reviewed.setBackgroundColor(green);
        var needsReview = new TextAttributes(); needsReview.setBackgroundColor(yellow);
        scheme.setAttributes(ReviewMarkKeys.REVIEWED, reviewed);
        scheme.setAttributes(ReviewMarkKeys.NEEDS_REVIEW, needsReview);
        scheme.setColor(EditorColors.MODIFIED_LINES_COLOR, changed);
        scheme.setColor(EditorColors.DELETED_LINES_COLOR, deleted);
        ((EditorEx) editor).setColorsScheme(scheme);
        var document = editor.getDocument();
        stripe.setSnapshot(new ReviewStripeModel.Snapshot(document.getModificationStamp(), List.of(
                new ReviewStripeModel.Run(0, document.getLineStartOffset(1), ReviewStripeModel.Kind.REVIEWED),
                new ReviewStripeModel.Run(document.getLineStartOffset(1), document.getLineStartOffset(2), ReviewStripeModel.Kind.NEEDS_REVIEW),
                new ReviewStripeModel.Run(document.getLineStartOffset(2), document.getLineStartOffset(3), ReviewStripeModel.Kind.CHANGED),
                new ReviewStripeModel.Run(document.getLineStartOffset(3), document.getLineStartOffset(3), ReviewStripeModel.Kind.DELETED))));
        for (int scale : new int[]{1, 2}) {
            var image = new BufferedImage(stripe.getWidth() * scale, stripe.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { graphics.scale(scale, scale); stripe.printAll(graphics); } finally { graphics.dispose(); }
            var track = stripe.trackBounds();
            for (var band : stripe.bands()) {
                Color expected = switch (band.kind()) {
                    case BLANK, REVIEWED -> green;
                    case UNREVIEWED -> editor.getColorsScheme().getDefaultBackground();
                    case NEEDS_REVIEW -> yellow;
                    case CHANGED -> changed;
                    case DELETED -> deleted;
                };
                assertEquals("The same themed logical stripe must render at either device scale", expected.getRGB(),
                        image.getRGB(3 * scale, (track.y + band.y()) * scale));
            }
        }
    }

    public void testWheelForwarding() {
        var editor = open("wheel.py", "# line\n".repeat(100));
        settings.setShown(true);
        var rotations = new AtomicInteger();
        MouseWheelListener listener = event -> rotations.addAndGet(event.getWheelRotation());
        editor.getContentComponent().addMouseWheelListener(listener);
        try {
            var stripe = panel(editor);
            stripe.dispatchEvent(new MouseWheelEvent(stripe, MouseEvent.MOUSE_WHEEL, 0, 0, 3, 5, 0,
                    false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 2));
            assertEquals(2, rotations.get());
        } finally { editor.getContentComponent().removeMouseWheelListener(listener); }
    }

    public void testPopupContext() {
        var editor = open("context.py", "value = 1\n");
        settings.setShown(true);
        var context = DataManager.getInstance().getDataContext(panel(editor));
        assertSame(getProject(), CommonDataKeys.PROJECT.getData(context));
        assertSame(editor.getDocument(), CommonDataKeys.EDITOR.getData(context).getDocument());
        var toggle = (ToggleReviewStripeAction) com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("ReviewMarks.ToggleStripe");
        var event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(toggle, null, "DoReview.Stripe", context);
        assertTrue(toggle.isSelected(event));
        toggle.setSelected(event, false);
        assertFalse(settings.isShown());
        assertNull(panel(editor));
    }

    public void testPreviewStripe() throws Exception {
        var editor = open("preview.py", "# preceding line\n".repeat(300)
                + "def compute(value):\n    first = value + 1\n    second = first + 2\n    return second\n\n"
                + "def pending(value):\n    result = value * 2\n    return result\n\n" + "# following line\n".repeat(700));
        var document = editor.getDocument();
        settings.setShown(true);
        service.mark(document, document.getLineStartOffset(300), document.getLineStartOffset(308));
        fresh(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            int start = document.getText().indexOf("first + 2");
            document.replaceString(start, start + "first + 2".length(), "first + 3");
            assertNull("No stale green snapshot may survive a document edit", panel(editor).currentSnapshot());
            int header = document.getText().indexOf("pending(value)");
            document.replaceString(header, header + "pending(value)".length(), "pending(amount)");
        });
        fresh(document);
        var host = ReviewStripeHost.find(editor);
        host.setSize(1000, 380); layout(host);
        editor.getScrollingModel().disableAnimation();
        editor.getScrollingModel().scrollVertically(editor.offsetToXY(document.getLineStartOffset(298)).y + 3);
        assertTrue(editor.getScrollingModel().getVisibleArea().y > 0);
        var image = new BufferedImage(1000, 380, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { host.printAll(graphics); } finally { graphics.dispose(); }
        var output = Path.of("build/preview/do-review-stripe.png");
        Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(image, "png", output.toFile()));
        assertTrue(panel(editor).bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.CHANGED));
    }

    public void testBlankFillUsesReviewedColorAndSeparateTooltip() {
        var editor = open("blank-fill.py", "first = 1\n \t\nsecond = 2\n");
        var document = editor.getDocument();
        settings.setShown(true);
        service.mark(document, 0, document.getTextLength());
        fresh(document);
        var stripe = panel(editor);
        var host = ReviewStripeHost.find(editor);
        host.setSize(800, 200); layout(host);
        var image = new BufferedImage(stripe.getWidth(), stripe.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { stripe.printAll(graphics); } finally { graphics.dispose(); }
        var blank = stripe.bands().stream().filter(band -> band.kind() == ReviewStripeModel.Kind.BLANK).findFirst().orElseThrow();
        var reviewed = stripe.bands().stream().filter(band -> band.kind() == ReviewStripeModel.Kind.REVIEWED).findFirst().orElseThrow();
        int top = stripe.trackBounds().y;
        assertEquals("Blank separators connect the same reviewed green without adding editor highlights",
                image.getRGB(3, top + reviewed.y()), image.getRGB(3, top + blank.y()));
        var mouse = new MouseEvent(stripe, MouseEvent.MOUSE_MOVED, 0, 0, 3, top + blank.y(), 0, false);
        assertEquals("DoReview — blank line, no review required", stripe.getToolTipText(mouse));
        assertTrue(service.projection(document).rows().get(1).evidenceRefs().isEmpty());
        assertEquals(FileProjection.Status.UNREVIEWED, service.projection(document).rows().get(1).status());
    }

    public void testWorkerUsesFrozenTextAndCannotRestoreInvalidatedSnapshot() {
        settings.setShown(true);
        var editor = standaloneEditor("\n");
        var gate = new BlockingCapture();
        var presentation = new ReviewStripePresentation(getProject(), getTestRootDisposable(), gate);
        var document = editor.getDocument();
        presentation.render(editor, unreviewed(document.getText()));
        try {
            await(gate.started::get);
            var stripe = panel(editor);
            assertNull(stripe.currentSnapshot());
            presentation.invalidate(document);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "code"));
            presentation.render(editor, unreviewed(document.getText()));
            await(() -> stripe.currentSnapshot() != null);
            var current = stripe.currentSnapshot();
            assertEquals("\n", gate.frozen.get());
            assertEquals(document.getModificationStamp(), current.stamp());
            assertTrue(current.runs().stream().anyMatch(run -> run.kind() == ReviewStripeModel.Kind.UNREVIEWED));
            gate.release.countDown();
            await(gate.finished::get); flush();
            assertSame("An obsolete worker must not restore the pre-edit blank snapshot", current, stripe.currentSnapshot());
        } finally { gate.release.countDown(); Disposer.dispose(presentation); }
    }

    public void testOneWorkerAndSnapshotAreSharedAcrossSplitEditors() {
        settings.setShown(true);
        var first = standaloneEditor("\ncode\n");
        var second = standaloneEditor(first.getDocument());
        var gate = new BlockingCapture();
        var presentation = new ReviewStripePresentation(getProject(), getTestRootDisposable(), gate);
        var projection = unreviewed(first.getDocument().getText());
        presentation.render(first, projection);
        presentation.render(second, projection);
        try {
            await(gate.started::get);
            assertEquals(1, gate.calls.get());
            presentation.release(second);
            assertNull(panel(second));
            gate.release.countDown();
            await(() -> panel(first).currentSnapshot() != null);
            assertEquals("Closing one split keeps the surviving document's worker", 1, gate.calls.get());
            presentation.render(second, projection);
            assertSame(panel(first).currentSnapshot(), panel(second).currentSnapshot());
        } finally { gate.release.countDown(); Disposer.dispose(presentation); }
    }

    public void testProjectionReplacementAtSameStampSuppressesOldWorker() {
        settings.setShown(true);
        var editor = standaloneEditor("code");
        var document = editor.getDocument();
        var gate = new BlockingCapture();
        var presentation = new ReviewStripePresentation(getProject(), getTestRootDisposable(), gate);
        presentation.render(editor, unreviewed(document.getText()));
        try {
            await(gate.started::get);
            long stamp = document.getModificationStamp();
            var history = ReviewHistoryEngine.review(ReviewHistory.empty(), document.getText(),
                    List.of(new ReviewedRange(0, document.getTextLength())), 1);
            var context = ContextMap.fallback(document.getText());
            var reviewed = ReviewHistoryEngine.project(history, document.getText(),
                    Map.of(history.evidence().getFirst().snapshotId(), context), context, null);
            presentation.render(editor, reviewed);
            await(() -> panel(editor).currentSnapshot() != null);
            var current = panel(editor).currentSnapshot();
            assertEquals(stamp, current.stamp());
            assertTrue(current.runs().stream().anyMatch(run -> run.kind() == ReviewStripeModel.Kind.REVIEWED));
            gate.release.countDown();
            await(gate.finished::get); flush();
            assertSame("Matching document stamps cannot make an older projection eligible again",
                    current, panel(editor).currentSnapshot());
        } finally { gate.release.countDown(); Disposer.dispose(presentation); }
    }

    public void testDisableCancelsWorkerAndReenableStartsFreshCapture() {
        settings.setShown(true);
        var editor = standaloneEditor("\n");
        var gate = new BlockingCapture();
        var presentation = new ReviewStripePresentation(getProject(), getTestRootDisposable(), gate);
        presentation.render(editor, unreviewed(editor.getDocument().getText()));
        try {
            await(gate.started::get);
            var obsoletePanel = panel(editor);
            settings.setShown(false);
            assertNull(panel(editor));
            settings.setShown(true);
            await(() -> panel(editor).currentSnapshot() != null);
            var current = panel(editor).currentSnapshot();
            assertEquals(2, gate.calls.get());
            assertNotSame(gate.firstResult.get(), current);
            gate.release.countDown();
            await(gate.finished::get); flush();
            assertNull(obsoletePanel.currentSnapshot());
            assertSame(current, panel(editor).currentSnapshot());
            for (int i = 0; i < 5; i++) { settings.setShown(false); settings.setShown(true); }
            assertEquals("A completed snapshot may be reused after toggling", 2, gate.calls.get());
            assertSame(current, panel(editor).currentSnapshot());
            assertEquals(1, stripeCount(editor));
        } finally { gate.release.countDown(); Disposer.dispose(presentation); }
    }

    public void testLastEditorReleaseSuppressesPendingWorker() { assertStoppedWorker(false); }

    public void testDisposeSuppressesPendingWorker() { assertStoppedWorker(true); }

    private void assertStoppedWorker(boolean dispose) {
        settings.setShown(true);
        var editor = standaloneEditor("\n");
        var gate = new BlockingCapture();
        var presentation = new ReviewStripePresentation(getProject(), getTestRootDisposable(), gate);
        presentation.render(editor, unreviewed(editor.getDocument().getText()));
        try {
            await(gate.started::get);
            var obsolete = panel(editor);
            if (dispose) Disposer.dispose(presentation); else presentation.release(editor);
            assertNull(panel(editor));
            gate.release.countDown();
            await(gate.finished::get); flush();
            assertNull(obsolete.currentSnapshot());
            assertNull(panel(editor));
        } finally { gate.release.countDown(); Disposer.dispose(presentation); }
    }

    private Editor standaloneEditor(String text) {
        return standaloneEditor(EditorFactory.getInstance().createDocument(text));
    }

    private Editor standaloneEditor(Document document) {
        var file = new LightVirtualFile("stripe-worker.txt", document.getText());
        var editor = EditorFactory.getInstance().createEditor(document, getProject(), file, false, EditorKind.MAIN_EDITOR);
        new TextEditorComponent(file, (EditorImpl) editor);
        assertFalse("Worker fixtures must not also be rendered by the project service", service.supports(document));
        Disposer.register(getTestRootDisposable(), () -> {
            if (!editor.isDisposed()) EditorFactory.getInstance().releaseEditor(editor);
        });
        return editor;
    }

    private static FileProjection unreviewed(String text) {
        return ReviewHistoryEngine.project(ReviewHistory.empty(), text, Map.of(), ContextMap.fallback(text), null);
    }

    private static final class BlockingCapture implements ReviewStripePresentation.SnapshotCapture {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean started = new AtomicBoolean(), finished = new AtomicBoolean();
        final AtomicReference<String> frozen = new AtomicReference<>();
        final AtomicReference<ReviewStripeModel.Snapshot> firstResult = new AtomicReference<>();
        final CountDownLatch release = new CountDownLatch(1);

        @Override public ReviewStripeModel.Snapshot capture(FileProjection projection, CharSequence text, long stamp) {
            assertFalse("Stripe capture must run off EDT", ApplicationManager.getApplication().isDispatchThread());
            assertFalse("The text-only pass must not hold a read action", ApplicationManager.getApplication().isReadAccessAllowed());
            var result = ReviewStripeModel.capture(projection, text, stamp);
            if (calls.incrementAndGet() == 1) {
                frozen.set(text.toString()); firstResult.set(result); started.set(true);
                boolean interrupted = false;
                try {
                    // Complete even after cancellation to exercise the stale-delivery guard.
                    while (true) try { release.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                } finally {
                    finished.set(true);
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            return result;
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        PlatformTestUtil.waitWithEventsDispatching("Current stripe snapshot did not finish", condition, 10);
    }

    private Editor open(String name, String text) {
        var file = myFixture.addFileToProject(name, text).getVirtualFile();
        FileEditorManager.getInstance(getProject()).openFile(file, true);
        myFixture.configureFromExistingVirtualFile(file);
        var document = myFixture.getEditor().getDocument();
        // CodeInsight fixtures use an unhosted editor; construct the real SDK main-editor host.
        var editor = EditorFactory.getInstance().createEditor(document, getProject(), file, false, EditorKind.MAIN_EDITOR);
        new TextEditorComponent(file, (EditorImpl) editor);
        Disposer.register(getTestRootDisposable(), () -> {
            if (!editor.isDisposed()) EditorFactory.getInstance().releaseEditor(editor);
        });
        fresh(editor.getDocument());
        assertNotNull("A normal text editor must expose the SDK 262 host", ReviewStripeHost.find(editor));
        return editor;
    }

    private void fresh(Document document) {
        PlatformTestUtil.waitWithEventsDispatching("Current stripe projection did not finish", () -> service.isProjectionReady(document), 10);
        flush();
        if (settings.isShown()) await(() -> Arrays.stream(EditorFactory.getInstance().getEditors(document, getProject()))
                .filter(editor -> ReviewStripeHost.find(editor) != null)
                .allMatch(editor -> panel(editor) != null && panel(editor).currentSnapshot() != null));
    }

    private static ReviewStripePanel panel(Editor editor) {
        var host = ReviewStripeHost.find(editor);
        return host == null ? null : Arrays.stream(host.getComponents()).filter(ReviewStripePanel.class::isInstance)
                .map(ReviewStripePanel.class::cast).findFirst().orElse(null);
    }

    private static long stripeCount(Editor editor) {
        return Arrays.stream(ReviewStripeHost.find(editor).getComponents()).filter(ReviewStripePanel.class::isInstance).count();
    }

    private static void flush() { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
