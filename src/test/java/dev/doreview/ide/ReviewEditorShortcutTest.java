package dev.doreview.ide;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionWrapper;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import dev.doreview.core.FileProjection;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Exercises component-owned navigation without editing the global IDE keymap. */
public final class ReviewEditorShortcutTest extends BasePlatformTestCase {
    private static final KeyboardShortcut ALT_M = new KeyboardShortcut(KeyStroke.getKeyStroke("alt M"), null);
    private static final KeyboardShortcut ALT_SHIFT_M = new KeyboardShortcut(KeyStroke.getKeyStroke("alt shift M"), null);
    private DoReviewService service;

    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture project = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(project, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(project.getProject().getBasePath()); }
        });
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        ReviewHistoryStore.getInstance().loadState(new ReviewHistoryStore.StoreState());
        service = DoReviewService.getInstance(getProject());
        service.historyReloaded();
    }

    public void testRepeatedRenderKeepsOneWrapperPerDirectionAndPreservesGlobalOwners() {
        AnAction next = ActionManager.getInstance().getAction("ReviewMarks.Next");
        AnAction previous = ActionManager.getInstance().getAction("ReviewMarks.Previous");
        assertNotNull(next);
        assertNotNull(previous);
        Shortcut[] globalShortcuts = next.getShortcutSet().getShortcuts().clone();
        Shortcut[] previousGlobalShortcuts = previous.getShortcutSet().getShortcuts().clone();
        var keymap = KeymapManager.getInstance().getActiveKeymap();
        List<String> altMOwners = List.copyOf(keymap.getActionIdList(ALT_M));
        List<String> altShiftMOwners = List.copyOf(keymap.getActionIdList(ALT_SHIFT_M));
        Shortcut[] mappedNext = keymap.getShortcuts("ReviewMarks.Next").clone();
        Shortcut[] mappedPrevious = keymap.getShortcuts("ReviewMarks.Previous").clone();
        Shortcut[] amendShortcuts = keymap.getShortcuts("Vcs.ToggleAmendCommitMode").clone();
        Document document = open("sample.txt", "reviewed\nremaining\n");
        Editor editor = myFixture.getEditor();
        service.mark(document, 0, document.getLineStartOffset(1));
        fresh(document);

        for (int render = 0; render < 4; render++) {
            service.historyReloaded();
            fresh(document);
            assertLocalBindings(editor.getContentComponent(), 1);
        }

        assertTrue(localAltM(editor.getContentComponent()).getFirst() instanceof AnActionWrapper);
        assertFalse("Alt+M belongs to editor content, not the globally registered navigation action",
                Arrays.asList(next.getShortcutSet().getShortcuts()).contains(ALT_M));
        assertFalse("Alt+Shift+M must not override global VCS shortcuts",
                Arrays.asList(previous.getShortcutSet().getShortcuts()).contains(ALT_SHIFT_M));
        assertTrue(Arrays.equals(globalShortcuts, next.getShortcutSet().getShortcuts()));
        assertTrue(Arrays.equals(previousGlobalShortcuts, previous.getShortcutSet().getShortcuts()));
        assertTrue(Arrays.equals(mappedNext, keymap.getShortcuts("ReviewMarks.Next")));
        assertTrue(Arrays.equals(mappedPrevious, keymap.getShortcuts("ReviewMarks.Previous")));
        assertEquals("Existing global Alt+M owners, including Commit Amend, must remain unchanged",
                altMOwners, keymap.getActionIdList(ALT_M));
        assertEquals("Existing global Alt+Shift+M owners must remain unchanged",
                altShiftMOwners, keymap.getActionIdList(ALT_SHIFT_M));
        assertTrue(Arrays.equals(amendShortcuts, keymap.getShortcuts("Vcs.ToggleAmendCommitMode")));
        assertLocalBindings(new JPanel(), 0);
    }

    public void testLocalWrapperSkipsGreenAndBlankRowsVisitsYellowAndWrapsWithoutChangingHistory() {
        String source = "def sample(flag):\n    if flag:\n        checked = 1\n\n    return checked\n\nnew_line = 3\n";
        Document document = open("sample.py", source);
        service.mark(document, 0, document.getLineStartOffset(6));
        fresh(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.replaceString(
                document.getLineStartOffset(1), document.getLineEndOffset(1), "    if not flag:"));
        fresh(document);
        service.mark(document, document.getLineStartOffset(1), document.getLineStartOffset(2));
        fresh(document);
        assertEquals(FileProjection.Status.REVIEWED, service.projection(document).rows().get(0).status());
        assertEquals(FileProjection.Status.REVIEWED, service.projection(document).rows().get(1).status());
        assertEquals(FileProjection.Status.NEEDS_REVIEW, service.projection(document).rows().get(2).status());
        assertEquals(FileProjection.Status.REVIEWED, service.projection(document).rows().get(4).status());
        assertEquals(FileProjection.Status.UNREVIEWED, service.projection(document).rows().get(6).status());
        var history = service.history(document);
        String currentText = document.getText();
        Editor editor = myFixture.getEditor();
        editor.getCaretModel().moveToOffset(0);
        editor.getSelectionModel().setSelection(0, 2);

        invokeLocalNext();
        assertCaretAtLineEnd(editor, 2);
        assertFalse(editor.getSelectionModel().hasSelection());
        invokeLocalNext();
        assertCaretAtLineEnd(editor, 6);
        invokeLocalNext();
        assertEquals("Navigation must wrap while continuing to skip reviewed and blank rows", 2,
                editor.getCaretModel().getLogicalPosition().line);
        assertCaretAtLineEnd(editor, 2);
        editor.getSelectionModel().setSelection(document.getLineStartOffset(2), document.getLineEndOffset(2));
        invokeLocalPrevious();
        assertCaretAtLineEnd(editor, 6);
        assertFalse(editor.getSelectionModel().hasSelection());
        invokeLocalPrevious();
        assertCaretAtLineEnd(editor, 2);
        assertEquals(currentText, document.getText());
        assertEquals(history, service.history(document));
    }

    public void testBothDirectionsLandAfterTrailingWhitespaceAtEofAndWrap() {
        String source = "first target  \n\nreviewed\n\nlast target\t  ";
        Document document = open("sample.txt", source);
        service.mark(document, document.getLineStartOffset(2), document.getLineStartOffset(3));
        fresh(document);
        Editor editor = myFixture.getEditor();
        var history = service.history(document);
        editor.getCaretModel().moveToOffset(document.getLineStartOffset(2));

        invokeLocalNext();
        assertCaretAtLineEnd(editor, 4);
        assertEquals("The last target has no newline; caret must land at EOF after its tab and spaces",
                document.getTextLength(), editor.getCaretModel().getOffset());
        invokeLocalPrevious();
        assertCaretAtLineEnd(editor, 0);
        assertEquals("Trailing spaces belong to the destination line", "first target  ".length(), editor.getCaretModel().getOffset());
        invokeLocalPrevious();
        assertCaretAtLineEnd(editor, 4);
        invokeLocalNext();
        assertCaretAtLineEnd(editor, 0);
        assertEquals(source, document.getText());
        assertEquals(history, service.history(document));
    }

    public void testSoleUnreviewedRowIsReachableAgainInBothDirections() {
        String source = "reviewed\n\nonly target  ";
        Document document = open("sample.txt", source);
        service.mark(document, 0, document.getLineStartOffset(1));
        fresh(document);
        var history = service.history(document);
        Editor editor = myFixture.getEditor();
        editor.getCaretModel().moveToOffset(document.getLineStartOffset(2) + 1);

        invokeLocalNext();
        assertCaretAtLineEnd(editor, 2);
        editor.getCaretModel().moveToOffset(document.getLineStartOffset(2) + 1);
        invokeLocalPrevious();
        assertCaretAtLineEnd(editor, 2);
        assertEquals(source, document.getText());
        assertEquals(history, service.history(document));
    }

    public void testReleaseAndPresentationDisposalRemoveOnlyTheirOwnLocalBindings() {
        Document document = open("sample.txt", "code\n");
        Editor main = myFixture.getEditor();
        Editor second = EditorFactory.getInstance().createEditor(document, getProject());
        JComponent releasedComponent = second.getContentComponent();
        try {
            fresh(document);
            assertLocalBindings(releasedComponent, 1);
        } finally {
            EditorFactory.getInstance().releaseEditor(second);
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        assertLocalBindings(releasedComponent, 0);
        assertLocalBindings(main.getContentComponent(), 1);

        var parent = Disposer.newDisposable("Isolated shortcut owner");
        var presentation = new ReviewEditorPresentation(getProject(), parent, ignored -> { });
        boolean parentDisposed = false;
        try {
            presentation.render(main, service.projection(document));
            assertLocalBindings(main.getContentComponent(), 2);
            presentation.render(second, service.projection(document));
            assertLocalBindings(releasedComponent, 0);
            Disposer.dispose(parent);
            parentDisposed = true;
            assertLocalBindings(main.getContentComponent(), 1);
        } finally {
            if (!parentDisposed) Disposer.dispose(parent);
        }
    }

    public void testViewerAndExternalFileEditorNeverReceiveLocalNavigationShortcuts() throws Exception {
        Document document = open("sample.txt", "inside project\n");
        Editor viewer = EditorFactory.getInstance().createViewer(document, getProject());
        Path outside = Files.createTempFile("review-next-outside-", ".txt");
        Editor outsideEditor = null;
        try {
            fresh(document);
            assertLocalBindings(viewer.getContentComponent(), 0);
            Files.writeString(outside, "outside project\n");
            var virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outside);
            assertNotNull(virtualFile);
            Document external = FileDocumentManager.getInstance().getDocument(virtualFile);
            assertNotNull(external);
            assertFalse(service.supports(external));
            outsideEditor = EditorFactory.getInstance().createEditor(external, getProject());
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            assertLocalBindings(outsideEditor.getContentComponent(), 0);
            assertLocalBindings(myFixture.getEditor().getContentComponent(), 1);
        } finally {
            if (outsideEditor != null) EditorFactory.getInstance().releaseEditor(outsideEditor);
            EditorFactory.getInstance().releaseEditor(viewer);
            Files.deleteIfExists(outside);
        }
    }

    private Document open(String name, String source) {
        var file = myFixture.addFileToProject(name, source);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Document document = myFixture.getEditor().getDocument();
        fresh(document);
        return document;
    }

    private void fresh(Document document) {
        PlatformTestUtil.waitWithEventsDispatching("Review shortcut projection did not settle", () -> service.isProjectionReady(document), 15);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
    }

    private void invokeLocalNext() {
        invokeLocalNavigation("ReviewMarks.Next", ALT_M);
    }

    private void invokeLocalPrevious() {
        invokeLocalNavigation("ReviewMarks.Previous", ALT_SHIFT_M);
    }

    private void invokeLocalNavigation(String actionId, KeyboardShortcut shortcut) {
        var actions = localNavigation(myFixture.getEditor().getContentComponent(), actionId, shortcut);
        assertEquals(1, actions.size());
        myFixture.testAction(actions.getFirst());
        fresh(myFixture.getEditor().getDocument());
    }

    private static List<AnAction> localAltM(JComponent component) {
        return localNavigation(component, "ReviewMarks.Next", ALT_M);
    }

    private static void assertLocalBindings(JComponent component, int expected) {
        assertEquals("Forward navigation bindings must remain scoped to their editor owner", expected, localAltM(component).size());
        assertEquals("Reverse navigation bindings must remain scoped to their editor owner", expected,
                localNavigation(component, "ReviewMarks.Previous", ALT_SHIFT_M).size());
    }

    private static void assertCaretAtLineEnd(Editor editor, int line) {
        assertEquals(line, editor.getCaretModel().getLogicalPosition().line);
        assertEquals("Navigation must use the end of the destination line", editor.getDocument().getLineEndOffset(line),
                editor.getCaretModel().getOffset());
    }

    private static List<AnAction> localNavigation(JComponent component, String actionId, KeyboardShortcut shortcut) {
        AnAction delegate = ActionManager.getInstance().getAction(actionId);
        return ActionUtil.getActions(component).stream()
                .filter(action -> action instanceof AnActionWrapper wrapper && wrapper.getDelegate() == delegate)
                .filter(action -> Arrays.asList(action.getShortcutSet().getShortcuts()).contains(shortcut)).toList();
    }
}
