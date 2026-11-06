package dev.doreview.ide;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Project-tree selection and weighted coverage without creating editor runtime state. */
public final class ReviewSelectionCoverageTest extends BasePlatformTestCase {
    private ReviewHistoryStore store;

    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture project = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(project, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(project.getProject().getBasePath()); }
        });
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        store = ReviewHistoryStore.getInstance();
        store.loadState(new ReviewHistoryStore.StoreState());
        getProject().getService(DoReviewService.class).historyReloaded();
    }

    public void testWeightsByTotalNonblankLinesAndDoesNotOpenEditors() {
        var small = file("small.py", "reviewed = 1\n");
        var large = file("large.py", "unreviewed = 0\n".repeat(9));
        markAll(small);
        var before = FileEditorManager.getInstance(getProject()).getOpenFiles();

        var result = calculate(small, large);

        assertEquals(2, result.files());
        assertEquals(1L, result.reviewed());
        assertEquals(10L, result.total());
        assertEquals("Percentages must be weighted by line counts, not averaged per file", 10, result.percent());
        assertEquals(0, result.skipped());
        assertTrue("A project-tree report must not open source editors",
                Arrays.equals(before, FileEditorManager.getInstance(getProject()).getOpenFiles()));
    }

    public void testFolderSelectionRecursesAndDeduplicatesOverlappingFilesAndFolders() {
        var first = file("source/first.py", "first = 1\n");
        var second = file("source/deep/second.py", "second = 2\nthird = 3\n");
        markAll(first);
        var folder = first.getParent();

        var result = calculate(folder, first, second.getParent(), second, folder);

        assertEquals(2, result.files());
        assertEquals(3L, result.total());
        assertEquals(1L, result.reviewed());
        assertEquals(33, result.percent());
        assertEquals(0, result.skipped());
    }

    public void testBlankRowsDoNotCountAndAllBlankSelectionHasZeroPercent() {
        var source = file("source.py", "reviewed = 1\n\n   \n\t\n");
        var blank = file("blank.py", "\n   \n\t\n");
        markAll(source);

        var result = calculate(source, blank);

        assertEquals(2, result.files());
        assertEquals(1L, result.reviewed());
        assertEquals(1L, result.total());
        assertEquals(100, result.percent());
        var empty = calculate(blank);
        assertEquals(0L, empty.total());
        assertEquals(0, empty.percent());
    }

    public void testUsesUnsavedDocumentTextAndKeepsDiskContentsUnchanged() {
        var reviewed = file("reviewed.py", "reviewed = 1\n");
        var changed = file("unsaved.py", "original = 0\n");
        markAll(reviewed);
        var document = FileDocumentManager.getInstance().getDocument(changed);
        assertNotNull(document);
        FileDocumentManager.getInstance().saveAllDocuments();
        long diskStamp = changed.getModificationStamp();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(document.getTextLength(), "added = 2\n"));
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document));

        var result = calculate(reviewed, changed);

        assertEquals(3L, result.total());
        assertEquals(1L, result.reviewed());
        assertEquals(33, result.percent());
        assertTrue("Reading aggregate coverage must not save an unsaved source document",
                FileDocumentManager.getInstance().isDocumentUnsaved(document));
        assertEquals(diskStamp, changed.getModificationStamp());
    }

    public void testClosedGitFilesUseGitDiffAndIncludeUnmarkedFilesInTheDenominator() throws Exception {
        initGit();
        var reviewed = file("reviewed.py", "def compute(value):\n    first = value + 1\n    return first\n");
        var unmarked = file("unmarked.py", "left = 0\nright = 0\n");
        markAll(reviewed);
        var document = FileDocumentManager.getInstance().getDocument(reviewed);
        assertNotNull(document);
        FileDocumentManager.getInstance().saveAllDocuments();
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                document.replaceString(0, document.getLineEndOffset(0), "def compute(value, extra):"));
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document));
        var openFiles = FileEditorManager.getInstance(getProject()).getOpenFiles();

        var result = calculate(reviewed, unmarked);

        assertEquals(2, result.files());
        assertEquals("Only the changed header loses its mark; the unchanged body follows Git", 2L, result.reviewed());
        assertEquals(5L, result.total());
        assertEquals(40, result.percent());
        assertEquals("Git must not turn unchanged body rows yellow after the header edit", 0L, result.needsReview());
        assertTrue(Arrays.equals(openFiles, FileEditorManager.getInstance(getProject()).getOpenFiles()));
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document));
    }

    public void testChangingAnAlreadyCountedDocumentAbortsInsteadOfReturningAPartialPercentage() {
        var first = file("a-first.py", "first = 1\n");
        var second = file("z-second.py", "second = 2\n");
        var document = FileDocumentManager.getInstance().getDocument(first);
        assertNotNull(document);
        var edited = new AtomicBoolean();
        var indicator = new ProgressIndicatorBase() {
            @Override public void setText2(String text) {
                super.setText2(text);
                if (text.endsWith("z-second.py") && edited.compareAndSet(false, true)) {
                    // This callback runs after a-first.py has contributed to the result,
                    // outside a read action, exactly while the next file is about to load.
                    ApplicationManager.getApplication().invokeAndWait(() ->
                            WriteCommandAction.runWriteCommandAction(getProject(), () ->
                                    document.insertString(document.getTextLength(), "new_line = 3\n")));
                }
            }
        };
        var future = CompletableFuture.supplyAsync(() -> {
            try { return new ReviewSelectionCoverage(getProject()).calculate(new VirtualFile[]{first, second}, indicator); }
            catch (Exception failure) { throw new CompletionException(failure); }
        });
        PlatformTestUtil.waitWithEventsDispatching("The changing-input calculation did not finish", future::isDone, 20);

        assertTrue("The fixture must change the first file only after it was counted", edited.get());
        try {
            future.join();
            fail("Changed inputs must not produce a complete aggregate from inconsistent snapshots");
        } catch (CompletionException failure) {
            Throwable cause = failure;
            while (cause instanceof CompletionException) cause = cause.getCause();
            assertTrue(cause.toString(), cause instanceof IllegalStateException);
            assertTrue(cause.getMessage(), cause.getMessage().contains("changed during calculation"));
        }
    }

    public void testAddingAFileInsideTheSelectedFolderAbortsInsteadOfOmittingIt() {
        var first = file("selection/a-first.py", "first = 1\n");
        file("selection/z-second.py", "second = 2\n");
        markAll(first);
        var added = new AtomicBoolean();
        var indicator = new ProgressIndicatorBase() {
            @Override public void setText2(String text) {
                super.setText2(text);
                if (text.endsWith("z-second.py") && added.compareAndSet(false, true)) {
                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        var created = myFixture.getTempDirFixture().createFile("selection/added.py");
                        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                            try { created.setBinaryContent("additional = 3\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        });
                    });
                }
            }
        };
        var future = CompletableFuture.supplyAsync(() -> {
            try { return new ReviewSelectionCoverage(getProject()).calculate(new VirtualFile[]{first.getParent()}, indicator); }
            catch (Exception failure) { throw new CompletionException(failure); }
        });
        PlatformTestUtil.waitWithEventsDispatching("Coverage did not finish after a selected folder changed", future::isDone, 20);

        assertTrue("The fixture must add a new file after the initial folder traversal", added.get());
        try {
            future.join();
            fail("Folder coverage must not silently omit a file created during calculation");
        } catch (CompletionException failure) {
            Throwable cause = failure;
            while (cause instanceof CompletionException) cause = cause.getCause();
            assertTrue(cause.toString(), cause instanceof IllegalStateException);
            assertTrue(cause.getMessage(), cause.getMessage().contains("changed during calculation"));
        }
    }

    public void testFolderTraversalSkipsBinaryAndMetadataTrees() {
        var source = file("selection/code.py", "value = 1\n");
        // IDE metadata is intentionally unavailable through PSI; create it through VFS.
        myFixture.getTempDirFixture().createFile("selection/.git/config");
        myFixture.getTempDirFixture().createFile("selection/.doreview/v1/events/event.json");
        myFixture.getTempDirFixture().createFile("selection/.idea/workspace.xml");
        myFixture.getTempDirFixture().createFile("selection/image.png");

        var selection = collect(source.getParent());

        assertEquals(Set.of(source), Set.copyOf(selection.files()));
        assertFalse(ReviewSelectionCoverage.supports(getProject(), source.getParent().findChild(".git")));
        assertFalse(ReviewSelectionCoverage.supports(getProject(), source.getParent().findChild(".doreview")));
        assertFalse(ReviewSelectionCoverage.supports(getProject(), source.getParent().findChild(".idea")));
    }

    public void testExcludedSubdirectoryIsNotTraversed() {
        var source = file("selection/code.py", "value = 1\n");
        var excluded = file("selection/generated/output.py", "generated = 2\n").getParent();
        // This physical fixture has no implicit module; model the exclusion explicitly.
        var module = WriteAction.compute(() -> ModuleManager.getInstance(getProject())
                .newNonPersistentModule("selection-coverage", "EMPTY_MODULE"));
        ModuleRootModificationUtil.updateModel(module,
                model -> model.addContentEntry(source.getParent()).addExcludeFolder(excluded));

        var selection = collect(source.getParent());

        assertEquals(Set.of(source), Set.copyOf(selection.files()));
    }

    public void testOversizedFileIsReportedAsSkippedWithoutLoadingItsDocument() throws Exception {
        var source = file("ordinary.py", "value = 1\n");
        var oversized = myFixture.getTempDirFixture().createFile("oversized.py");
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            try { oversized.setBinaryContent(new byte[8 * 1024 * 1024 + 1]); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        assertNull(FileDocumentManager.getInstance().getCachedDocument(oversized));

        var result = calculate(source, oversized);

        assertEquals(1, result.files());
        assertEquals(1, result.skipped());
        assertEquals(1L, result.total());
        assertNull("Oversized files must be excluded before loading source buffers",
                FileDocumentManager.getInstance().getCachedDocument(oversized));
        assertTrue(result.message(), result.message().contains("Skipped"));
    }

    public void testLargeWeightedCountsDoNotRoundUpToCompleteCoverage() {
        var result = new ReviewSelectionCoverage.Result(2, 0, Long.MAX_VALUE - 1, Long.MAX_VALUE, 0, 0);
        assertEquals(99, result.percent());
    }

    public void testProjectViewActionWorksWithoutEditorForFolderProjectRootAndMultiselect() {
        var first = file("first.py", "first = 1\n");
        var second = file("nested/second.py", "second = 2\n");
        var action = ActionManager.getInstance().getAction("ReviewMarks.SelectionCoverage");
        assertTrue(action instanceof ShowSelectionCoverageAction);
        var group = ActionManager.getInstance().getAction("ReviewMarks.ProjectGroup");
        assertTrue(group instanceof ActionGroup);
        assertTrue(Arrays.asList(((ActionGroup) group).getChildren(null)).contains(action));
        for (VirtualFile[] selected : new VirtualFile[][]{{first, second}, {second.getParent()}, {first.getParent()}}) {
            var event = event(action, selected);
            assertNull(event.getData(CommonDataKeys.EDITOR));
            action.update(event);
            assertTrue("File/folder selection must work without an editor", event.getPresentation().isEnabledAndVisible());
        }
        var empty = event(action, VirtualFile.EMPTY_ARRAY);
        action.update(empty);
        assertFalse(empty.getPresentation().isEnabledAndVisible());
    }

    public void testCancellationDoesNotReturnACompleteAggregate() {
        var source = file("source.py", "value = 1\n");
        var indicator = new ProgressIndicatorBase();
        indicator.cancel();
        var future = CompletableFuture.supplyAsync(() -> {
            try { return new ReviewSelectionCoverage(getProject()).calculate(new VirtualFile[]{source}, indicator); }
            catch (Exception failure) { throw new CompletionException(failure); }
        });
        PlatformTestUtil.waitWithEventsDispatching("Cancelled coverage computation did not finish", future::isDone, 10);
        try {
            future.join();
            fail("Cancellation must not return a complete percentage");
        } catch (CompletionException failure) {
            Throwable cause = failure;
            while (cause instanceof CompletionException) cause = cause.getCause();
            assertTrue(cause.toString(), cause instanceof ProcessCanceledException);
        }
    }

    private VirtualFile file(String path, String text) { return myFixture.addFileToProject(path, text).getVirtualFile(); }

    private void markAll(VirtualFile file) {
        var document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        String relative = new ReviewFileAccess(getProject()).relativePath(file.getPath());
        assertNotNull(relative);
        assertNotNull(store.mark(getProject(), relative, document.getText(), 0, document.getTextLength()));
    }

    private ReviewSelectionCoverage.Result calculate(VirtualFile... selected) {
        var future = CompletableFuture.supplyAsync(() -> {
            try { return new ReviewSelectionCoverage(getProject()).calculate(selected, new ProgressIndicatorBase()); }
            catch (Exception failure) { throw new CompletionException(failure); }
        });
        PlatformTestUtil.waitWithEventsDispatching("Selection coverage did not finish", future::isDone, 20);
        return future.join();
    }

    private ReviewSelectionCoverage.Selection collect(VirtualFile... selected) {
        var future = CompletableFuture.supplyAsync(() -> ReviewSelectionCoverage.collect(getProject(), selected, new ProgressIndicatorBase()));
        PlatformTestUtil.waitWithEventsDispatching("Selection traversal did not finish", future::isDone, 20);
        return future.join();
    }

    private AnActionEvent event(com.intellij.openapi.actionSystem.AnAction action, VirtualFile[] selected) {
        var data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, selected).build();
        return AnActionEvent.createFromAnAction(action, null, ActionPlaces.PROJECT_VIEW_POPUP, data);
    }

    private void initGit() throws Exception {
        var builder = new ProcessBuilder("git", "-C", getProject().getBasePath(), "-c", "core.fsmonitor=false",
                "init", "--template=", "-q", "-b", "selection-coverage").redirectErrorStream(true);
        ReviewGitHistory.prepareEnvironment(builder.environment());
        var process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(output, process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(output, 0, process.exitValue());
    }
}
