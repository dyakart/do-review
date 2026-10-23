package dev.doreview.ide;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.xmlb.XmlSerializer;

import java.nio.file.Path;

/** Verifies the persistent current-file indicator through actual IDE selection and document events. */
public final class ReviewCoverageWidgetTest extends BasePlatformTestCase {
    private DoReviewService service;
    private ReviewCoverageWidget widget;

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
        ReviewHistoryStore.getInstance().loadState(new ReviewHistoryStore.StoreState());
        service = DoReviewService.getInstance(getProject());
        service.historyReloaded();
        widget = new ReviewCoverageWidget(getProject());
        Disposer.register(getTestRootDisposable(), widget);
    }

    public void testCurrentFileSelectionShowsItsOwnCoverageAndFilename() {
        Document first = open("first.txt", "reviewed\n\nremaining_one\nremaining_two\n");
        markLine(first, 0);
        assertEventually("DoReview 33%", "1 / 3", "first.txt");

        Document second = open("second.txt", "one\ntwo\n");
        assertEventually("DoReview 0%", "0 / 2", "second.txt");
        markLine(second, 1);
        assertEventually("DoReview 50%", "1 / 2", "second.txt");

        FileEditorManager.getInstance(getProject()).openTextEditor(new OpenFileDescriptor(getProject(),
                FileDocumentManager.getInstance().getFile(first)), true);

        assertEventually("DoReview 33%", "1 / 3", "first.txt");
    }

    public void testMarkAndClearUpdateWithoutReopeningTheFile() {
        Document document = open("current.txt", "one\ntwo\nthree\n");
        assertEventually("DoReview 0%", "0 / 3", "current.txt");

        markLine(document, 1);
        assertEventually("DoReview 33%", "1 / 3", "current.txt");

        service.clearFile(document);
        assertEventually("DoReview 0%", "0 / 3", "current.txt");
    }

    public void testEditingUnreviewedCodeUpdatesTheDenominator() {
        Document document = open("current.txt", "reviewed\nunreviewed\n");
        markLine(document, 0);
        assertEventually("DoReview 50%", "1 / 2", "current.txt");

        // The edit is after the reviewed block: review state itself is unchanged,
        // but the current file has an extra non-blank line to inspect.
        edit(() -> document.insertString(document.getTextLength(), "additional_code\n"));

        assertEventually("DoReview 33%", "1 / 3", "current.txt");
    }

    public void testUnmarkedFileEditsStillUpdateTooltipCounts() {
        Document document = open("unmarked.txt", "one\n");
        assertEventually("DoReview 0%", "0 / 1", "unmarked.txt");

        edit(() -> document.insertString(document.getTextLength(), "two\nthree\n"));

        assertEventually("DoReview 0%", "0 / 3", "unmarked.txt");
    }

    public void testEditInsideMarkedCodeUpdatesThePercentage() {
        Document document = open("current.txt", "reviewed\nunreviewed\n");
        markLine(document, 0);
        assertEventually("DoReview 50%", "1 / 2", "current.txt");

        edit(() -> document.replaceString(0, 1, "R"));

        assertEventually("DoReview 0%", "0 / 2", "current.txt");
    }

    public void testEditingOneReviewedRowPreservesOtherReviewedRowsInWidgetCount() {
        Document document = open("current.txt", "first_reviewed\nsecond_reviewed\nthird_reviewed\nunreviewed\n");
        service.mark(document, 0, document.getLineStartOffset(3));
        awaitFresh(document);
        assertEventually("DoReview 75%", "3 / 4", "current.txt");
        int offset = document.getLineStartOffset(1);

        edit(() -> document.replaceString(offset, offset + 1, "S"));

        assertEventually("DoReview 50%", "2 / 4", "current.txt");
    }

    public void testLoadingSavedStateRefreshesTheVisiblePercentage() {
        Document document = open("current.txt", "reviewed\nunreviewed\n");
        markLine(document, 0);
        assertEventually("DoReview 50%", "1 / 2", "current.txt");
        ReviewHistoryStore.StoreState saved = XmlSerializer.deserialize(
                XmlSerializer.serialize(ReviewHistoryStore.getInstance().getState()), ReviewHistoryStore.StoreState.class);
        service.clearFile(document);
        assertEventually("DoReview 0%", "0 / 2", "current.txt");

        ReviewHistoryStore.getInstance().loadState(saved);
        service.historyReloaded();
        awaitFresh(document);

        assertEventually("DoReview 50%", "1 / 2", "current.txt");
    }

    public void testClosingAllEditorsHidesStaleCurrentFileCoverage() {
        Document document = open("current.txt", "reviewed\nunreviewed\n");
        markLine(document, 0);
        assertEventually("DoReview 50%", "1 / 2", "current.txt");

        FileEditorManager manager = FileEditorManager.getInstance(getProject());
        for (var file : manager.getOpenFiles()) manager.closeFile(file);

        PlatformTestUtil.waitWithEventsDispatching("Coverage from a closed file remains visible",
                () -> widget.getText().isEmpty(), 5);
        assertNull(widget.getTooltipText());
    }

    private Document open(String name, String text) {
        var file = myFixture.addFileToProject(name.replace(".txt", ".py"), text);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Document document = myFixture.getEditor().getDocument();
        assertTrue(service.supports(document));
        awaitFresh(document);
        return document;
    }

    private void markLine(Document document, int line) {
        int end = document.getLineEndOffset(line);
        if (end < document.getTextLength()) end++;
        service.mark(document, document.getLineStartOffset(line), end);
        awaitFresh(document);
    }

    private void edit(Runnable edit) {
        WriteCommandAction.runWriteCommandAction(getProject(), edit);
        awaitFresh(myFixture.getEditor().getDocument());
    }

    private void assertEventually(String expectedText, String expectedCount, String filename) {
        PlatformTestUtil.waitWithEventsDispatching(
                () -> "Coverage widget did not update: expected=" + expectedText + " / " + expectedCount
                        + ", actual=" + widget.getText() + " / " + widget.getTooltipText(),
                () -> expectedText.equals(widget.getText()) && widget.getTooltipText() != null
                        && widget.getTooltipText().contains(expectedCount)
                        && widget.getTooltipText().contains(filename.replace(".txt", ".py")), 10);
        assertTrue(widget.getTooltipText().contains("non-blank"));
        assertTrue("The live coverage tooltip must use the current product brand",
                widget.getTooltipText().startsWith("DoReview: reviewed "));
    }

    private void awaitFresh(Document document) {
        PlatformTestUtil.waitWithEventsDispatching("Review projection did not finish",
                () -> service.isProjectionReady(document), 10);
    }
}
