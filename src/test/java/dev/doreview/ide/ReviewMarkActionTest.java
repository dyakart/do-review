package dev.doreview.ide;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import dev.doreview.core.ReviewedRange;
import dev.doreview.core.FileProjection;

import javax.swing.KeyStroke;
import java.awt.Color;
import java.awt.Font;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Tests the actual registered user action and visible output in custom editor themes. */
public final class ReviewMarkActionTest extends BasePlatformTestCase {
    private DoReviewService service;

    @Override
    protected CodeInsightTestFixture createMyFixture() {
        IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture projectFixture = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(projectFixture, new TempDirTestFixtureImpl() {
            @Override
            protected Path getTempHome() {
                return Path.of(projectFixture.getProject().getBasePath());
            }
        });
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        service = DoReviewService.getInstance(getProject());
        ReviewHistoryStore.getInstance().loadState(new ReviewHistoryStore.StoreState());
        service.historyReloaded();
    }

    public void testRegisteredActionMarksCurrentLineWithoutSelectionAndDoesNotEditSource() {
        String source = "before\ncurrent line\nafter\n";
        Document document = open(source);
        Editor editor = myFixture.getEditor();
        editor.getCaretModel().moveToOffset(document.getLineStartOffset(1) + 3);
        assertFalse(editor.getSelectionModel().hasSelection());

        invokeMarkAction();

        assertEquals(List.of(new ReviewedRange(document.getLineStartOffset(1), document.getLineStartOffset(2))),
                service.getRanges(document));
        assertEquals(source, document.getText());
        assertFalse(editor.getSelectionModel().hasSelection());
    }

    public void testRegisteredActionExpandsPartialSelectionToWholeTouchedLines() {
        String source = "before\nfirst selected\nsecond selected\nafter\n";
        Document document = open(source);
        Editor editor = myFixture.getEditor();
        editor.getSelectionModel().setSelection(document.getLineStartOffset(1) + 4,
                document.getLineStartOffset(2) + 3);

        invokeMarkAction();

        assertEquals(List.of(new ReviewedRange(document.getLineStartOffset(1), document.getLineStartOffset(3))),
                service.getRanges(document));
        assertEquals(source, document.getText());
    }

    public void testSelectionEndingAtLineStartExcludesTheFollowingLine() {
        String source = "before\nselected\nnot selected\n";
        Document document = open(source);
        myFixture.getEditor().getSelectionModel().setSelection(document.getLineStartOffset(1) + 2,
                document.getLineStartOffset(2));

        invokeMarkAction();

        assertEquals(List.of(new ReviewedRange(document.getLineStartOffset(1), document.getLineStartOffset(2))),
                service.getRanges(document));
        assertEquals(source, document.getText());
    }

    public void testCurrentLastLineWithoutTrailingNewlineIsMarkable() {
        Document document = open("before\nlast line");
        myFixture.getEditor().getCaretModel().moveToOffset(document.getTextLength());

        invokeMarkAction();

        assertEquals(List.of(new ReviewedRange(document.getLineStartOffset(1), document.getTextLength())),
                service.getRanges(document));
    }

    public void testBlankToggle() {
        String source = "\n \t \n\t\n";
        Document document = open(source);
        Editor editor = myFixture.getEditor();
        var history = service.history(document);

        for (int line = 0; line < document.getLineCount(); line++) {
            editor.getCaretModel().moveToOffset(document.getLineStartOffset(line));
            invokeMarkAction();
            assertEquals("Ctrl+M on a blank row must not create a review decision", history, service.history(document));
            assertNoReviewDecoration(document);
        }
        editor.getSelectionModel().setSelection(0, document.getTextLength());
        invokeMarkAction();
        invokeMarkAction();

        assertEquals(history, service.history(document));
        assertEquals(source, document.getText());
        assertEquals(source, editor.getSelectionModel().getSelectedText());
        assertEquals(new DoReviewService.Coverage(0, 0), service.coverage(document));
        assertNoReviewDecoration(document);
    }

    public void testBlankGapsToggle() {
        String source = "\nalpha\n \t \nbeta\t \n\n";
        Document document = open(source);
        Editor editor = myFixture.getEditor();
        editor.getSelectionModel().setSelection(0, document.getTextLength());
        var expected = List.of(
                new ReviewedRange(document.getLineStartOffset(1), document.getLineStartOffset(2)),
                new ReviewedRange(document.getLineStartOffset(3), document.getLineStartOffset(4)));

        invokeMarkAction();

        assertEquals(expected, service.getRanges(document));
        assertEquals("Saved review ranges must exclude the whitespace gaps", expected,
                service.history(document).evidence().stream().flatMap(proof -> proof.ranges().stream()).toList());
        assertEquals(new DoReviewService.Coverage(2, 2), service.coverage(document));
        for (int line : new int[]{0, 2, 4}) {
            assertEquals(FileProjection.Status.UNREVIEWED, service.projection(document).rows().get(line).status());
        }

        invokeMarkAction();

        assertEquals("The second Ctrl+M must clear both nonblank rows despite the gaps",
                List.of(), service.getRanges(document));
        assertEquals(new DoReviewService.Coverage(0, 2), service.coverage(document));
        assertEquals(source, document.getText());
        assertEquals(source, editor.getSelectionModel().getSelectedText());
        assertNoReviewDecoration(document);
    }

    public void testEraseToBlank() {
        String source = "def sample():\n    value = 1\n    return value\n";
        var file = myFixture.addFileToProject("erase.py", source);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Document document = myFixture.getEditor().getDocument();
        myFixture.getEditor().getCaretModel().moveToOffset(document.getLineStartOffset(1));
        invokeMarkAction();
        assertEquals(FileProjection.Status.REVIEWED, service.projection(document).rows().get(1).status());

        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.replaceString(
                document.getLineStartOffset(1), document.getLineEndOffset(1), " \t "));
        awaitProjection(document);
        assertEquals(FileProjection.Status.UNREVIEWED, service.projection(document).rows().get(1).status());
        assertNoReviewDecoration(document);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.deleteString(
                document.getLineStartOffset(1), document.getLineEndOffset(1)));
        awaitProjection(document);
        assertEquals(FileProjection.Status.UNREVIEWED, service.projection(document).rows().get(1).status());
        assertNoReviewDecoration(document);
    }

    public void testOldBlankProof() {
        Document document = open("before\n \t \n\nafter\n");
        invokeMarkAction(); // Create the real project/file identity before injecting an older journal entry.
        var store = ReviewHistoryStore.getInstance();
        var legacy = store.getState();
        var savedFile = legacy.projects.values().iterator().next().files.values().iterator().next();
        savedFile.evidence.getFirst().ranges = List.of(new ReviewHistoryStore.RangeState(
                document.getLineStartOffset(1), document.getLineStartOffset(3)));
        savedFile.checkpoints.clear();
        savedFile.pendingCheckpoint = null;
        assertEquals(List.of(new ReviewedRange(document.getLineStartOffset(1), document.getLineStartOffset(3))),
                ReviewJournalCodec.read(savedFile).evidence().getFirst().ranges());
        store.loadState(legacy);
        service.historyReloaded();
        awaitProjection(document);
        assertNoReviewDecoration(document);
        assertEquals(0, service.projection(document).deletedCount());

        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.replaceString(
                document.getLineStartOffset(1), document.getLineEndOffset(1), "\t  "));
        awaitProjection(document);
        assertNoReviewDecoration(document);
        assertEquals(0, service.projection(document).deletedCount());

        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.deleteString(
                document.getLineStartOffset(1), document.getLineStartOffset(3)));
        awaitProjection(document);
        assertEquals("before\nafter\n", document.getText());
        assertNoReviewDecoration(document);
        assertEquals("Removing old reviewed blank rows must not create deleted-code diagnostics",
                0, service.projection(document).deletedCount());
    }

    public void testRegisteredMarkActionAdvertisesCtrlMShortcut() {
        AnAction action = markAction();
        KeyboardShortcut ctrlM = new KeyboardShortcut(KeyStroke.getKeyStroke("ctrl M"), null);

        assertTrue("Mark action must expose its Ctrl+M keyboard binding to the IDE",
                Arrays.asList(action.getShortcutSet().getShortcuts()).contains(ctrlM));
    }

    public void testRepeatingCurrentLineActionClearsTheMarkAndCoverageWithoutEditingSource() {
        String source = "before\ncurrent line\nafter\n";
        Document document = open(source);
        Editor editor = myFixture.getEditor();
        int caret = document.getLineStartOffset(1) + 3;
        editor.getCaretModel().moveToOffset(caret);

        invokeMarkAction();
        assertEquals(new DoReviewService.Coverage(1, 3), service.coverage(document));
        assertEquals(List.of(new ReviewedRange(document.getLineStartOffset(1), document.getLineStartOffset(2))),
                service.getRanges(document));

        invokeMarkAction();

        assertEmpty(service.getRanges(document));
        assertEquals(new DoReviewService.Coverage(0, 3), service.coverage(document));
        assertEquals(source, document.getText());
        assertEquals(caret, editor.getCaretModel().getOffset());
        assertFalse(editor.getSelectionModel().hasSelection());
    }

    public void testTogglingMiddleLineSplitsLargerMarkAndPreservesItsNeighbors() {
        String source = "first reviewed\nmiddle reviewed\nlast reviewed\n";
        Document document = open(source);
        Editor editor = myFixture.getEditor();
        editor.getSelectionModel().setSelection(0, document.getTextLength());
        invokeMarkAction();
        assertEquals(List.of(new ReviewedRange(0, document.getTextLength())), service.getRanges(document));
        editor.getSelectionModel().removeSelection();
        editor.getCaretModel().moveToOffset(document.getLineStartOffset(1) + 2);

        invokeMarkAction();

        assertEquals(List.of(new ReviewedRange(0, document.getLineStartOffset(1)),
                new ReviewedRange(document.getLineStartOffset(2), document.getTextLength())),
                service.getRanges(document));
        assertEquals(new DoReviewService.Coverage(2, 3), service.coverage(document));
        assertEquals(source, document.getText());
        assertFalse(editor.getSelectionModel().hasSelection());
    }

    public void testMixedSelectionFirstBecomesFullyReviewedThenClearsOnlySelectedLines() {
        String source = "outside before\nreviewed selected\nunreviewed selected\noutside after\n";
        Document document = open(source);
        Editor editor = myFixture.getEditor();
        service.mark(document, 0, document.getLineStartOffset(2));
        service.mark(document, document.getLineStartOffset(3), document.getTextLength());
        awaitProjection(document);
        assertEquals(new DoReviewService.Coverage(3, 4), service.coverage(document));
        int start = document.getLineStartOffset(1) + 2;
        int end = document.getLineStartOffset(2) + 4;
        editor.getSelectionModel().setSelection(start, end);
        String selectedText = editor.getSelectionModel().getSelectedText();

        invokeMarkAction();

        assertEquals(List.of(new ReviewedRange(0, document.getTextLength())), service.getRanges(document));
        assertEquals(new DoReviewService.Coverage(4, 4), service.coverage(document));
        assertEquals(start, editor.getSelectionModel().getSelectionStart());
        assertEquals(end, editor.getSelectionModel().getSelectionEnd());
        assertEquals(selectedText, editor.getSelectionModel().getSelectedText());

        invokeMarkAction();

        assertEquals(List.of(new ReviewedRange(0, document.getLineStartOffset(1)),
                new ReviewedRange(document.getLineStartOffset(3), document.getTextLength())),
                service.getRanges(document));
        assertEquals(new DoReviewService.Coverage(2, 4), service.coverage(document));
        assertEquals(start, editor.getSelectionModel().getSelectionStart());
        assertEquals(end, editor.getSelectionModel().getSelectionEnd());
        assertEquals(selectedText, editor.getSelectionModel().getSelectedText());
        assertEquals(source, document.getText());
    }

    public void testCustomDarkThemeWithoutReviewAttributesStillPaintsGreen() {
        assertCustomThemeHighlights(new Color(0x282c34), new Color(0xabb2bf), "Custom One Dark");
    }

    public void testCustomLightThemeWithoutReviewAttributesStillPaintsGreen() {
        assertCustomThemeHighlights(new Color(0xfafafa), new Color(0x383a42), "Custom One Light");
    }

    public void testRapidSecondToggleRevokesAllHistoricalVariants() {
        String original = "def sample(flag):\n    if flag:\n        return 1\n    return 0\n";
        var file = myFixture.addFileToProject("rapid.py", original);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Document document = myFixture.getEditor().getDocument();
        int start = document.getLineStartOffset(2), end = document.getLineStartOffset(3);
        service.mark(document, start, end);
        awaitProjection(document);
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),
                () -> document.replaceString(document.getLineStartOffset(1), document.getLineEndOffset(1), "    if not flag:"));
        awaitProjection(document);
        start = document.getLineStartOffset(2);
        end = document.getLineStartOffset(3);
        service.mark(document, start, end);
        // The full PSI/history task has not completed: the second toggle must still be durable.
        service.toggle(document, start, end);
        awaitProjection(document);
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(original));
        awaitProjection(document);
        assertEmpty(service.getRanges(document));
    }

    public void testYellowContextIsVisibleInBothEditorsAndDoesNotIncreaseCoverage() {
        String original = "def sample(flag):\n    if flag:\n        return 1\n    return 0\n";
        var file = myFixture.addFileToProject("split.py", original);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Document document = myFixture.getEditor().getDocument();
        Editor second = com.intellij.openapi.editor.EditorFactory.getInstance().createEditor(document, getProject());
        try {
            service.mark(document, document.getLineStartOffset(2), document.getLineStartOffset(3));
            awaitProjection(document);
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),
                    () -> document.replaceString(document.getLineStartOffset(1), document.getLineEndOffset(1), "    if not flag:"));
            awaitProjection(document);
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            for (Editor editor : List.of(myFixture.getEditor(), second)) {
                var yellow = Arrays.stream(editor.getMarkupModel().getAllHighlighters())
                        .filter(h -> Boolean.TRUE.equals(h.getUserData(DoReviewService.NEEDS_REVIEW_HIGHLIGHTER))).toList();
                assertEquals(1, yellow.size());
                assertNotNull(yellow.getFirst().getGutterIconRenderer().getClickAction());
                assertNotNull(yellow.getFirst().getTextAttributes(editor.getColorsScheme()).getBackgroundColor());
            }
            assertEquals(0, service.coverage(document).reviewed());
            assertEquals(1, service.coverage(document).needsReview());
        } finally {
            com.intellij.openapi.editor.EditorFactory.getInstance().releaseEditor(second);
        }
    }

    public void testDiffAndReattachActionsAreRegisteredAndVersionsContainOriginalText() {
        Document document = open("old checked text\n");
        service.mark(document, 0, document.getTextLength());
        assertNotNull(ActionManager.getInstance().getAction("ReviewMarks.Changes"));
        assertNotNull(ActionManager.getInstance().getAction("ReviewMarks.AttachHistory"));
        var version = service.reviewVersions(document).getFirst();
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("replacement\n"));
        assertEquals("old checked text\n", version.snapshot().text());
        assertEquals(1, service.reviewVersions(document).size());
    }

    public void testUnrelatedFileCreationPreservesKnownDuplicateIdentity() {
        var file = myFixture.addFileToProject("duplicates.py", "def sample():\n    value = 1\n    value = 1\n    return value\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Document document = myFixture.getEditor().getDocument();
        service.mark(document, document.getLineStartOffset(1), document.getLineStartOffset(2));
        awaitProjection(document);
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),
                () -> document.insertString(document.getLineStartOffset(1), "    value = 1\n"));
        awaitProjection(document);
        int original = document.getLineStartOffset(2);
        assertTrue(service.getRanges(document).stream().anyMatch(range -> range.start() <= original && range.end() > original));

        myFixture.addFileToProject("unrelated.py", "pass\n");
        awaitProjection(document);

        assertTrue("An unrelated file must not erase the original row's known identity",
                service.getRanges(document).stream().anyMatch(range -> range.start() <= original && range.end() > original));
        int inserted = document.getLineStartOffset(1);
        assertFalse(service.getRanges(document).stream().anyMatch(range -> range.start() <= inserted && range.end() > inserted));
    }

    public void testInsertedTextCannotStayGreenWhileBackgroundAnalysisIsPending() {
        var file = myFixture.addFileToProject("pending.py", "def sample():\n    value = 1\n    return value\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Document document = myFixture.getEditor().getDocument();
        service.mark(document, 0, document.getTextLength());
        awaitProjection(document);
        assertTrue(Arrays.stream(myFixture.getEditor().getMarkupModel().getAllHighlighters())
                .anyMatch(h -> Boolean.TRUE.equals(h.getUserData(DoReviewService.REVIEWED_HIGHLIGHTER))));

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),
                () -> document.insertString(document.getLineStartOffset(2), "    added = 2\n"));

        assertFalse("The editor must not retain confirmed tint over inserted text before its worker finishes",
                Arrays.stream(myFixture.getEditor().getMarkupModel().getAllHighlighters())
                        .anyMatch(h -> Boolean.TRUE.equals(h.getUserData(DoReviewService.REVIEWED_HIGHLIGHTER))));
        awaitProjection(document);
        int added = document.getLineStartOffset(2);
        assertFalse(service.getRanges(document).stream().anyMatch(range -> range.start() <= added && range.end() > added));
    }

    public void testSplitInsertedCopyCannotGainReviewAfterReopenReloadOrVersionReturn() {
        String original = "fun sample() {\n    first(); second();\n    untouched();\n}\n";
        var file = myFixture.addFileToProject("identity.tact", original);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Document document = myFixture.getEditor().getDocument();
        service.mark(document, 0, document.getTextLength());
        awaitProjection(document);
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),
                () -> document.insertString(document.getText().indexOf("second();"),
                        "\n    first(); second();\n    "));
        // Persist even before the background projection has a chance to finish.
        var store = ReviewHistoryStore.getInstance();
        var saved = com.intellij.util.xmlb.XmlSerializer.deserialize(
                com.intellij.util.xmlb.XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class);
        store.loadState(saved);
        awaitProjection(document);
        assertEquals(dev.doreview.core.FileProjection.Status.UNREVIEWED, service.projection(document).rows().get(2).status());
        String changed = document.getText();

        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(document);
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject()).closeFile(file.getVirtualFile());
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        awaitProjection(document);
        assertEquals(dev.doreview.core.FileProjection.Status.UNREVIEWED, service.projection(document).rows().get(2).status());

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(original));
        awaitProjection(document);
        assertEquals(dev.doreview.core.FileProjection.Status.REVIEWED, service.projection(document).rows().get(1).status());
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(changed));
        awaitProjection(document);
        assertEquals(dev.doreview.core.FileProjection.Status.UNREVIEWED, service.projection(document).rows().get(2).status());

        String unseenVersion = changed + "// externally changed version\n";
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(unseenVersion));
        awaitProjection(document);
        assertFalse("A new content hash must not forget a known lost origin",
                service.projection(document).rows().get(2).status() == dev.doreview.core.FileProjection.Status.REVIEWED);
        store.loadState(store.getState());
        awaitProjection(document);
        assertFalse("Reload must retain conservative exclusions for an unseen content version",
                service.projection(document).rows().get(2).status() == dev.doreview.core.FileProjection.Status.REVIEWED);
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(changed));
        awaitProjection(document);

        service.mark(document, document.getLineStartOffset(2), document.getLineStartOffset(3));
        awaitProjection(document);
        assertEquals(dev.doreview.core.FileProjection.Status.REVIEWED, service.projection(document).rows().get(2).status());
        store.loadState(store.getState());
        awaitProjection(document);
        assertEquals(dev.doreview.core.FileProjection.Status.REVIEWED, service.projection(document).rows().get(2).status());
    }

    public void testEditingBeforeInitialHistoricalProjectionCannotCertifyInsertedCopy() {
        String original = "fun sample() {\n    first(); second();\n    untouched();\n}\n";
        var file = myFixture.addFileToProject("pending-history.tact", original);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Document document = myFixture.getEditor().getDocument();
        service.mark(document, 0, document.getTextLength());
        awaitProjection(document);
        // A content version without an exact reviewed snapshot has not yet been reconciled.
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),
                () -> document.setText(original.replace("untouched();", "untouched(1);")));
        service.historyReloaded();
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),
                () -> document.insertString(document.getText().indexOf("second();"),
                        "\n    first(); second();\n    "));
        var store = ReviewHistoryStore.getInstance();
        store.loadState(store.getState());
        awaitProjection(document);
        assertFalse("An unresolved historical row cannot certify a known inserted copy",
                service.projection(document).rows().get(2).status() == dev.doreview.core.FileProjection.Status.REVIEWED);
        assertEquals(dev.doreview.core.FileProjection.Status.REVIEWED, service.projection(document).rows().getFirst().status());
    }

    public void testPresentationOwnsOnlyItsHighlightsAndReleasesOneEditorIndependently() {
        Document document = open("first\nsecond\n");
        Editor first = myFixture.getEditor();
        Editor second = com.intellij.openapi.editor.EditorFactory.getInstance().createEditor(document, getProject());
        var parent = com.intellij.openapi.util.Disposer.newDisposable("Independent review presentation");
        var presentation = new ReviewEditorPresentation(getProject(), parent, ignored -> { });
        var foreign = first.getMarkupModel().addRangeHighlighter(0, 1, HighlighterLayer.SYNTAX, null,
                HighlighterTargetArea.EXACT_RANGE);
        try {
            var history = dev.doreview.core.ReviewHistoryEngine.review(dev.doreview.core.ReviewHistory.empty(),
                    document.getText(), List.of(new ReviewedRange(0, document.getTextLength())), 1);
            var projection = dev.doreview.core.ReviewHistoryEngine.project(history, document.getText(), null, null, null);
            presentation.render(first, projection);
            presentation.render(second, projection);
            assertEquals(1, Arrays.stream(first.getMarkupModel().getAllHighlighters())
                    .filter(h -> Boolean.TRUE.equals(h.getUserData(DoReviewService.REVIEWED_HIGHLIGHTER))).count());
            presentation.release(first);
            assertTrue("Releasing review resources must preserve another owner's decoration", foreign.isValid());
            assertEquals(1, first.getMarkupModel().getAllHighlighters().length);
            assertEquals(1, Arrays.stream(second.getMarkupModel().getAllHighlighters())
                    .filter(h -> Boolean.TRUE.equals(h.getUserData(DoReviewService.REVIEWED_HIGHLIGHTER))).count());
            com.intellij.openapi.util.Disposer.dispose(parent);
            assertTrue(foreign.isValid());
            assertEquals(0, second.getMarkupModel().getAllHighlighters().length);
        } finally {
            com.intellij.openapi.util.Disposer.dispose(parent);
            foreign.dispose();
            com.intellij.openapi.editor.EditorFactory.getInstance().releaseEditor(second);
        }
    }

    public void testRepeatedPresentationOfLargeReviewedBlockHasBoundedEditorResources() {
        Document document = open("unchanged row\n".repeat(2000));
        var parent = com.intellij.openapi.util.Disposer.newDisposable("Review presentation resource bound");
        var presentation = new ReviewEditorPresentation(getProject(), parent, ignored -> { });
        try {
            var history = dev.doreview.core.ReviewHistoryEngine.review(dev.doreview.core.ReviewHistory.empty(),
                    document.getText(), List.of(new ReviewedRange(0, document.getTextLength())), 1);
            var projection = dev.doreview.core.ReviewHistoryEngine.project(history, document.getText(), null, null, null);
            for (int i = 0; i < 20; i++) presentation.render(myFixture.getEditor(), projection);
            assertEquals("A contiguous reviewed block must not create a resource for every line or render",
                    1, Arrays.stream(myFixture.getEditor().getMarkupModel().getAllHighlighters())
                            .filter(h -> Boolean.TRUE.equals(h.getUserData(DoReviewService.REVIEWED_HIGHLIGHTER))).count());
            presentation.render(myFixture.getEditor(), null);
            assertFalse(Arrays.stream(myFixture.getEditor().getMarkupModel().getAllHighlighters())
                    .anyMatch(h -> Boolean.TRUE.equals(h.getUserData(DoReviewService.REVIEWED_HIGHLIGHTER))));
        } finally { com.intellij.openapi.util.Disposer.dispose(parent); }
    }

    private void awaitProjection(Document document) {
        PlatformTestUtil.waitWithEventsDispatching("Review projection did not finish", () -> service.isProjectionReady(document), 15);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
    }

    private void assertNoReviewDecoration(Document document) {
        assertEmpty(service.projection(document).reviewedRanges());
        assertEmpty(service.projection(document).yellowRanges());
        assertEmpty(service.projection(document).changedRanges());
        assertFalse("Blank rows must not retain a review background, warning or gutter marker",
                Arrays.stream(myFixture.getEditor().getMarkupModel().getAllHighlighters()).anyMatch(highlighter ->
                        Boolean.TRUE.equals(highlighter.getUserData(DoReviewService.REVIEWED_HIGHLIGHTER))
                                || Boolean.TRUE.equals(highlighter.getUserData(DoReviewService.NEEDS_REVIEW_HIGHLIGHTER))
                                || Boolean.TRUE.equals(highlighter.getUserData(DoReviewService.CHANGED_HIGHLIGHTER))));
    }

    private void assertCustomThemeHighlights(Color background, Color foreground, String name) {
        Document document = open("before\nreviewed code\nafter\n");
        Editor editor = myFixture.getEditor();
        EditorColorsScheme original = editor.getColorsScheme();
        EditorColorsScheme custom = new EditorColorsSchemeImpl(original) {
            @Override
            public TextAttributes getAttributes(TextAttributesKey key) {
                // Reproduces an external theme that does not inherit either bundled color scheme.
                return DoReviewService.REVIEWED_ATTRIBUTES.equals(key) ? null : super.getAttributes(key);
            }
        };
        custom.setName(name);
        custom.setAttributes(HighlighterColors.TEXT, new TextAttributes(foreground, background, null, null, Font.PLAIN));
        ((EditorEx) editor).setColorsScheme(custom);
        try {
            assertNull(editor.getColorsScheme().getAttributes(DoReviewService.REVIEWED_ATTRIBUTES));
            editor.getCaretModel().moveToOffset(document.getLineStartOffset(1) + 2);

            invokeMarkAction();
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

            List<RangeHighlighter> highlights = Arrays.stream(editor.getMarkupModel().getAllHighlighters())
                    .filter(highlighter -> Boolean.TRUE.equals(
                            highlighter.getUserData(DoReviewService.REVIEWED_HIGHLIGHTER)))
                    .toList();
            assertEquals(1, highlights.size());
            RangeHighlighter highlight = highlights.getFirst();
            TextAttributes attributes = highlight.getTextAttributes(editor.getColorsScheme());
            assertNotNull("Missing custom-theme attributes must not suppress the review background", attributes);
            Color reviewedBackground = attributes.getBackgroundColor();
            assertNotNull(reviewedBackground);
            assertTrue("Reviewed code needs a visible green background in " + name,
                    reviewedBackground.getGreen() > reviewedBackground.getRed()
                            && reviewedBackground.getGreen() > reviewedBackground.getBlue());
            assertFalse("Review color must differ from the editor background", background.equals(reviewedBackground));
            assertNull("Review highlighting must preserve syntax foreground colors", attributes.getForegroundColor());
            assertEquals(foreground, editor.getColorsScheme().getDefaultForeground());
            assertEquals(background, editor.getColorsScheme().getDefaultBackground());
            assertEquals(HighlighterTargetArea.LINES_IN_RANGE, highlight.getTargetArea());
            assertTrue("Normal editor selection must remain above the review tint",
                    highlight.getLayer() < HighlighterLayer.SELECTION);
        } finally {
            ((EditorEx) editor).setColorsScheme(original);
        }
    }

    private Document open(String source) {
        var file = myFixture.addFileToProject("sample.txt", source);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Document document = myFixture.getEditor().getDocument();
        assertTrue(service.supports(document));
        return document;
    }

    private AnAction markAction() {
        AnAction action = ActionManager.getInstance().getAction("ReviewMarks.Mark");
        assertNotNull("Plugin descriptor must register the mark action", action);
        return action;
    }

    private void invokeMarkAction() {
        myFixture.testAction(markAction());
        awaitProjection(myFixture.getEditor().getDocument());
    }
}
