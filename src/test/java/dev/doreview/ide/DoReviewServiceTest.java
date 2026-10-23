package dev.doreview.ide;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.impl.text.TextEditorComponent;
import com.intellij.openapi.extensions.LoadingOrder;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LoggedErrorProcessor;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.xmlb.XmlSerializer;
import dev.doreview.core.FileSnapshot;
import dev.doreview.core.ContextMap;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ReviewHistory;
import dev.doreview.ide.context.ContextProvider;

import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real editor, PSI, persistence and Git transitions against local project files. */
public final class DoReviewServiceTest extends BasePlatformTestCase {
    private static final String SOURCE = "def compute(value):\n    first = value + 1\n    second = first + 2\n    return second\n";
    private DoReviewService service;
    private ReviewHistoryStore store;
    private TextEditorComponent stripeTestHost;

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
        service = DoReviewService.getInstance(getProject());
        service.historyReloaded();
    }
    @Override protected void tearDown() throws Exception {
        try {
            if (myFixture != null) {
                Path base = Path.of(getProject().getBasePath()).toAbsolutePath().normalize();
                Path git = base.resolve(".git").normalize();
                assertTrue("Fixture Git cleanup must stay inside its project", git.startsWith(base) && !git.equals(base));
                if (Files.isDirectory(git)) try (var entries = Files.walk(git)) {
                    for (Path entry : entries.toList()) {
                        DosFileAttributeView attributes = Files.getFileAttributeView(entry, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                        if (attributes != null) attributes.setReadOnly(false);
                    }
                }
            }
        } finally { super.tearDown(); }
    }

    public void testSingleRowEditKeepsOtherRowsAndImmutableHistory() {
        Document doc = open("sample.py", SOURCE);
        markAll(doc);
        ReviewHistory original = service.history(doc);
        edit(doc, () -> replace(doc, "first + 2", "first + 3"));
        coverage(doc, 3, 4);
        assertTrue(green(doc, 0)); assertTrue(green(doc, 1));
        assertFalse(green(doc, 2)); assertTrue(green(doc, 3));
        assertFalse(service.projection(doc).changedRanges().isEmpty());
        assertEquals(original, service.history(doc));
        assertEquals(2, service.nextUnreviewedLine(doc, 0));
    }

    public void testUndoRestoresEvidenceUnlessItWasExplicitlyRevoked() {
        Document doc = open("sample.py", SOURCE);
        markAll(doc);
        ReviewHistory original = service.history(doc);
        edit(doc, () -> replace(doc, "first + 2", "first + 3"));
        var editor = FileEditorManager.getInstance(getProject()).getSelectedEditor(FileDocumentManager.getInstance().getFile(doc));
        var undo = UndoManager.getInstance(getProject());
        assertTrue(undo.isUndoAvailable(editor));
        undo.undo(editor); fresh(doc);
        assertEquals(SOURCE, doc.getText());
        coverage(doc, 4, 4);
        assertEquals(original, service.history(doc));
    }

    public void testNewIdenticalCopyDoesNotStealShiftedOriginalReview() {
        Document doc = open("sample.py", SOURCE);
        markLine(doc, 1);
        String copy = doc.getText().substring(doc.getLineStartOffset(1), doc.getLineStartOffset(2));
        int offset = doc.getLineStartOffset(1);
        edit(doc, () -> doc.insertString(offset, copy));
        assertFalse("New code must not inherit a review", green(doc, 1));
        assertTrue("Known edit offsets identify the original row", green(doc, 2));
        coverage(doc, 1, 5);
    }

    public void testFailedProjectionStillGuardsCopiesOnTheNextEdit() {
        var failingText = new AtomicReference<String>();
        var failed = new AtomicBoolean();
        var injectedFailure = new AssertionError("Injected one-time projection capture failure");
        ContextProvider provider = new ContextProvider() {
            @Override public String id() { return "test:failure-provenance-v1"; }
            @Override public boolean supports(PsiFile file) { return true; }
            @Override public ContextMap analyze(PsiFile file, String text) {
                String expectedFailure = failingText.get();
                if (text.equals(expectedFailure) && failingText.compareAndSet(expectedFailure, null)) {
                    throw injectedFailure;
                }
                int rows = 1 + (int) text.chars().filter(c -> c == '\n').count();
                return new ContextMap(id(), Collections.nCopies(rows, "same-structure"));
            }
        };
        ContextProvider.EP.getPoint().registerExtension(provider, LoadingOrder.FIRST, getTestRootDisposable());
        LoggedErrorProcessor.executeWith(new LoggedErrorProcessor() {
            private boolean isInjected(Throwable failure) {
                for (Throwable current = failure; current != null; current = current.getCause()) {
                    if (current == injectedFailure) return true;
                }
                return false;
            }
            @Override public boolean processWarn(String category, String message, Throwable failure) {
                if (category.endsWith(".DoReviewService") && isInjected(failure)
                        && message.equals("Review projection failed; keeping only unconfirmed exact facts")) {
                    failed.set(true);
                    return false;
                }
                return super.processWarn(category, message, failure);
            }
            @Override public Set<Action> processError(String category, String message, String[] details, Throwable failure) {
                if (isInjected(failure)) {
                    return Set.of();
                }
                return super.processError(category, message, details, failure);
            }
        }, () -> {
            Document doc = open("failure.py", SOURCE);
            markAll(doc);
            String offline = SOURCE.replace("value + 1", "value + 9");
            failingText.set(offline);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.setText(offline));
            service.historyReloaded();
            service.projection(doc);
            PlatformTestUtil.waitWithEventsDispatching("Injected projection failure was not delivered",
                    failed::get, 10);
            assertFalse("Failed work has not resolved historical row identities", service.isProjectionReady(doc));

            int row = 2;
            String sourceRow = doc.getText().substring(doc.getLineStartOffset(row), doc.getLineStartOffset(row + 1));
            int split = doc.getLineStartOffset(row) + 8;
            WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.insertString(split, "\n" + sourceRow + "\n"));
            fresh(doc);
            assertEquals("A copy after failed analysis must not acquire the old row's proof",
                    FileProjection.Status.UNREVIEWED, service.projection(doc).rows().get(row + 1).status());
            assertTrue("Unaffected exact rows remain reviewed after recovery", green(doc, 0));
            reloadHistory();
            fresh(doc);
            assertFalse("The copy restriction survives a persisted state round trip", green(doc, row + 1));
        });
    }

    public void testDeletedReviewedRowIsRememberedAndRestoredWithItsText() {
        Document doc = open("sample.py", SOURCE);
        markAll(doc);
        ReviewHistory original = service.history(doc);
        int start = doc.getLineStartOffset(2), end = doc.getLineStartOffset(3);
        edit(doc, () -> doc.deleteString(start, end));
        coverage(doc, 3, 3);
        assertTrue(service.projection(doc).deletedCount() > 0);
        assertEquals(original, service.history(doc));
        edit(doc, () -> doc.setText(SOURCE));
        coverage(doc, 4, 4);
    }

    public void testWholeRowInsertionAndRemovalPreserveSurroundingRows() {
        Document doc = open("sample.py", SOURCE);
        markAll(doc);
        int offset = doc.getLineStartOffset(2);
        edit(doc, () -> doc.insertString(offset, "    extra = first + 5\n"));
        coverage(doc, 4, 5); assertFalse(green(doc, 2));
        int end = doc.getLineStartOffset(3);
        edit(doc, () -> doc.deleteString(offset, end));
        assertEquals(SOURCE, doc.getText()); coverage(doc, 4, 4);
    }

    public void testWholeTextFormattingRetainsOnlyExactUnchangedRows() {
        Document doc = open("sample.py", SOURCE);
        markAll(doc);
        edit(doc, () -> doc.setText(SOURCE.replace("value + 1\n", "value + 1  \n").replace("return second\n", "return second  \n")));
        assertTrue(green(doc, 0)); assertFalse(green(doc, 1));
        assertTrue(green(doc, 2)); assertFalse(green(doc, 3)); coverage(doc, 2, 4);
    }

    public void testUnsupportedLanguageTurnsYellowAfterEditAndRestoresOnExactReturn() {
        String text = "first\nsecond\nthird\n";
        Document doc = open("sample.txt", text);
        markAll(doc);
        ReviewHistory original = service.history(doc);
        edit(doc, () -> replace(doc, "second", "changed"));
        assertEmpty(service.getRanges(doc));
        assertFalse(service.projection(doc).yellowRanges().isEmpty());
        assertEquals(original, service.history(doc));
        edit(doc, () -> doc.setText(text)); coverage(doc, 3, 3);
    }

    public void testInlineTactAsmReviewSurvivesNeighborChangesAndRestoresAfterInstructionReturn() {
        String text = "asm fun clearNotificationActions() { NEWC ENDC c5 POP }\n";
        Document doc = open("inline-asm.tact", text);
        markAll(doc);
        assertTrue("Explicitly reviewed inline assembly must be green", green(doc, 0));
        coverage(doc, 1, 1);

        edit(doc, () -> doc.insertString(doc.getTextLength(), "fun ordinary(): Int { return 1; }\n"));
        edit(doc, () -> replace(doc, "return 1;", "return 2;"));
        assertTrue("Changing a neighboring function must not invalidate unchanged assembly", green(doc, 0));
        coverage(doc, 1, 2);

        edit(doc, () -> replace(doc, "c5 POP", "c5 PUSH"));
        assertFalse("A changed assembly instruction must lose confirmed review", green(doc, 0));
        coverage(doc, 0, 2);

        edit(doc, () -> doc.setText(text));
        assertTrue("Returning to the reviewed assembly must restore its review", green(doc, 0));
        coverage(doc, 1, 1);
    }

    public void testChangedConditionRequiresExplicitReviewOfUnchangedBody() {
        Document doc = open("sample.py", "def check(value):\n    if value > 0:\n        result = value + 1\n        return result\n    return 0\n");
        markLine(doc, 2);
        edit(doc, () -> replace(doc, "value > 0", "value >= 0"));
        assertFalse(green(doc, 2)); assertFalse(service.projection(doc).yellowRanges().isEmpty());
        markLine(doc, 2); assertTrue(green(doc, 2));
        assertEquals(2, service.history(doc).evidence().size());
    }

    public void testManualRevokeSurvivesEditReturnAndApplicationStateReload() {
        Document doc = open("sample.py", SOURCE);
        markLine(doc, 2);
        service.clear(doc, doc.getLineStartOffset(2), doc.getLineStartOffset(3)); fresh(doc);
        edit(doc, () -> replace(doc, "first + 2", "first + 3"));
        reloadHistory(); fresh(doc);
        edit(doc, () -> doc.setText(SOURCE));
        assertFalse(green(doc, 2)); assertFalse(service.history(doc).revocations().isEmpty());
        markLine(doc, 2); assertTrue(green(doc, 2));
    }

    public void testClearRevokesBothOlderAndNewerVersionsOfCurrentRow() {
        Document doc = open("sample.py", SOURCE);
        markLine(doc, 2);
        edit(doc, () -> replace(doc, "first + 2", "first + 3"));
        markLine(doc, 2);
        var ids = service.history(doc).evidence().stream().map(evidence -> evidence.id()).toList();
        service.clear(doc, doc.getLineStartOffset(2), doc.getLineStartOffset(3));
        PlatformTestUtil.waitWithEventsDispatching("Old historical proof was not revoked", () -> service.history(doc).revocations()
                .stream().map(revoke -> revoke.evidenceId()).distinct().toList().containsAll(ids), 10);
        fresh(doc);
        edit(doc, () -> doc.setText(SOURCE)); assertFalse(green(doc, 2));
        edit(doc, () -> doc.setText(SOURCE.replace("first + 2", "first + 3"))); assertFalse(green(doc, 2));
    }

    public void testOfflineVersionAndSecondRestartKeepOriginalProof() {
        Document doc = open("sample.py", SOURCE);
        markAll(doc);
        ReviewHistory original = service.history(doc);
        var saved = xmlState();
        edit(doc, () -> doc.setText(SOURCE.replace("first + 2", "first + 20")));
        store.loadState(saved); service.historyReloaded(); fresh(doc);
        coverage(doc, 3, 4); assertEquals(original, service.history(doc));
        reloadHistory(); fresh(doc); coverage(doc, 3, 4);
        edit(doc, () -> doc.setText(SOURCE)); coverage(doc, 4, 4);
    }

    public void testActualGitCheckoutRestoresOriginalAfterStateReloadOnOtherBranch() throws Exception {
        Document doc = open("sample.py", SOURCE);
        initGit(); markAll(doc);
        ReviewHistory original = service.history(doc);
        git("checkout", "-q", "-b", "review-branch-b");
        Files.writeString(sourcePath(), SOURCE.replace("first + 2", "first + 20"));
        git("add", "--", sourceRelative()); git("commit", "-q", "-m", "version B");
        reload(doc); coverage(doc, 3, 4);
        reloadHistory(); fresh(doc);
        git("checkout", "-q", "review-branch-a"); reload(doc);
        assertEquals(SOURCE, doc.getText()); coverage(doc, 4, 4);
        assertEquals(original, service.history(doc));
    }

    public void testPullStableRow() throws Exception {
        Document doc = open("sample.py", SOURCE);
        initGit(); markAll(doc);
        ReviewHistory original = service.history(doc);
        String pulled = SOURCE.replace("value + 1", "value + 10").replace("return second", "return second + 1");
        git("checkout", "-q", "-b", "review-pull-incoming");
        commitSource(pulled, "change surrounding rows");
        git("checkout", "-q", "review-branch-a");

        git("pull", "--ff-only", ".", "review-pull-incoming");
        reloadExternally(doc); fresh(doc);

        assertEquals(pulled, doc.getText());
        assertEquals(FileProjection.Status.REVIEWED, service.projection(doc).rows().get(2).status());
        assertEquals(FileProjection.Reason.EXACT, service.projection(doc).rows().get(2).reason());
        assertEquals(FileProjection.Status.CHANGED, service.projection(doc).rows().get(1).status());
        assertEquals(FileProjection.Reason.TEXT_CHANGED, service.projection(doc).rows().get(1).reason());
        assertEquals(FileProjection.Status.CHANGED, service.projection(doc).rows().get(3).status());
        coverage(doc, 2, 4);
        assertEquals(original, service.history(doc));
    }

    public void testStripePull() throws Exception {
        ReviewStripeSettings.getInstance(getProject()).setShown(true);
        Document doc = open("sample.py", SOURCE);
        initGit(); markAll(doc);
        ReviewHistory original = service.history(doc);
        var stripe = stripe(myFixture.getEditor());
        assertNotNull(stripe.currentSnapshot());
        assertEquals(doc.getModificationStamp(), stripe.currentSnapshot().stamp());
        assertTrue(stripe.bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.REVIEWED));

        String pulled = SOURCE.replace("first + 2", "first + 20");
        git("checkout", "-q", "-b", "review-stripe-incoming");
        commitSource(pulled, "change reviewed row");
        git("checkout", "-q", "review-branch-a");
        git("pull", "--ff-only", ".", "review-stripe-incoming");
        reloadExternally(doc); fresh(doc);

        assertEquals(pulled, doc.getText());
        assertSame(stripe, stripe(myFixture.getEditor()));
        assertNotNull(stripe.currentSnapshot());
        assertEquals(doc.getModificationStamp(), stripe.currentSnapshot().stamp());
        assertEquals(ReviewStripeModel.capture(service.projection(doc), doc.getModificationStamp()), stripe.currentSnapshot());
        assertTrue("Unchanged reviewed rows remain visible after pull",
                stripe.bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.REVIEWED));
        assertTrue("The actual pulled row must appear with the changed marker",
                stripe.bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.CHANGED));
        assertFalse(stripe.bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.NEEDS_REVIEW));
        assertEquals(original, service.history(doc));
    }

    public void testStripeUndoRedo() {
        ReviewStripeSettings.getInstance(getProject()).setShown(true);
        Document doc = open("sample.py", SOURCE);
        Editor editor = myFixture.getEditor();
        editor.getCaretModel().moveToOffset(doc.getLineStartOffset(2));
        var action = ActionManager.getInstance().getAction("ReviewMarks.Mark");
        assertNotNull(action);
        myFixture.testAction(action); fresh(doc);
        ReviewHistory original = service.history(doc);
        var stripe = stripe(editor);
        assertTrue(stripe.bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.REVIEWED));
        var beforeSeen = new AtomicBoolean();
        var afterSeen = new AtomicBoolean();
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void beforeDocumentChange(DocumentEvent event) {
                if (event.getDocument() != doc) return;
                beforeSeen.set(true);
                assertNull("The stripe must drop confirmed data before the document stamp changes", stripe.currentSnapshot());
                assertEmpty(stripe.bands());
            }
            @Override public void documentChanged(DocumentEvent event) {
                if (event.getDocument() != doc) return;
                afterSeen.set(true);
                assertNull("New text must never reuse the preceding review snapshot", stripe.currentSnapshot());
            }
        }, getTestRootDisposable());

        WriteCommandAction.runWriteCommandAction(getProject(), () -> replace(doc, "first + 2", "first + 20"));
        assertTrue(beforeSeen.get()); assertTrue(afterSeen.get());
        assertNull(stripe.currentSnapshot());
        fresh(doc);
        assertTrue(stripe.bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.CHANGED));
        assertFalse(stripe.bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.REVIEWED));

        var fileEditor = FileEditorManager.getInstance(getProject()).getSelectedEditor(FileDocumentManager.getInstance().getFile(doc));
        var undo = UndoManager.getInstance(getProject());
        assertTrue(undo.isUndoAvailable(fileEditor));
        undo.undo(fileEditor); fresh(doc);
        assertEquals(SOURCE, doc.getText());
        assertTrue(stripe.bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.REVIEWED));
        assertFalse(stripe.bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.CHANGED));
        assertTrue(undo.isRedoAvailable(fileEditor));
        undo.redo(fileEditor); fresh(doc);
        assertEquals(SOURCE.replace("first + 2", "first + 20"), doc.getText());
        assertTrue(stripe.bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.CHANGED));
        assertEquals(original, service.history(doc));

        editor.getCaretModel().moveToOffset(doc.getLineStartOffset(2));
        myFixture.testAction(action); fresh(doc);
        assertTrue(stripe.bands().stream().anyMatch(band -> band.kind() == ReviewStripeModel.Kind.REVIEWED));
        myFixture.testAction(action); fresh(doc);
        assertEmpty("Repeating Ctrl+M clears all review markers for the selected row", stripe.bands());
    }

    public void testPullRestoresText() throws Exception {
        String baseline = SOURCE + "\n# footer = 0\n";
        Document doc = open("sample.py", baseline);
        initGit(); markAll(doc);
        ReviewHistory original = service.history(doc);
        String intermediate = baseline.replace("first + 2", "first + 20").replace("footer = 0", "footer = 1");
        String pulled = baseline.replace("footer = 0", "footer = 1");
        prepareLocalPullVersions(intermediate, pulled);

        git("pull", "--ff-only", ".", "review-pull-intermediate");
        reloadExternally(doc); fresh(doc);
        assertEquals(FileProjection.Status.CHANGED, service.projection(doc).rows().get(2).status());
        assertEquals(FileProjection.Reason.TEXT_CHANGED, service.projection(doc).rows().get(2).reason());

        git("pull", "--ff-only", ".", "review-pull-incoming");
        reloadExternally(doc); fresh(doc);

        assertEquals(pulled, doc.getText());
        assertEquals("A tracked one-row change does not lose the original row's identity",
                FileProjection.Status.REVIEWED, service.projection(doc).rows().get(2).status());
        assertEquals(FileProjection.Reason.EXACT, service.projection(doc).rows().get(2).reason());
        assertEquals(FileProjection.Status.CHANGED, service.projection(doc).rows().get(5).status());
        assertEquals(FileProjection.Reason.TEXT_CHANGED, service.projection(doc).rows().get(5).reason());
        coverage(doc, 4, 5);
        assertEquals(original, service.history(doc));
    }

    public void testPullAfterLocalEdit() throws Exception {
        String baseline = "def check(value):\n    require(value != 0,\n        \"LP exchange request pending\"\n    )\n    return value\n\n# footer = 0\n";
        Document doc = open("sample.py", baseline);
        initGit(); markAll(doc);
        ReviewHistory original = service.history(doc);
        var target = service.projection(doc).rows().get(2).revocableRefs().getFirst();

        edit(doc, () -> replace(doc, "LP exchange request pending", "Temporary diagnostic"));
        assertEquals(FileProjection.Status.CHANGED, service.projection(doc).rows().get(2).status());
        assertEquals(FileProjection.Reason.TEXT_CHANGED, service.projection(doc).rows().get(2).reason());
        var savedFile = store.getState().projects.values().iterator().next().files.values().iterator().next();
        assertTrue("The temporary edit must leave a historical positive row locator",
                savedFile.checkpoints.values().stream().flatMap(checkpoint -> checkpoint.payload.refs.stream())
                        .anyMatch(ref -> ref.evidenceId.equals(target.evidenceId()) && ref.start == target.baselineStart()
                                && ref.end == target.baselineEnd() && ref.authoritative && ref.live != null));
        edit(doc, () -> replace(doc, "Temporary diagnostic", "LP exchange request pending"));
        assertEquals(FileProjection.Status.REVIEWED, service.projection(doc).rows().get(2).status());
        FileDocumentManager.getInstance().saveDocument(doc);

        String pulled = baseline.replace("footer = 0", "footer = 1");
        git("checkout", "-q", "-b", "review-pull-incoming");
        commitSource(pulled, "change independent footer");
        git("checkout", "-q", "review-branch-a");
        git("pull", "--ff-only", ".", "review-pull-incoming");
        reloadExternally(doc); fresh(doc);

        assertEquals(pulled, doc.getText());
        assertEquals(FileProjection.Status.REVIEWED, service.projection(doc).rows().get(2).status());
        assertEquals(FileProjection.Reason.EXACT, service.projection(doc).rows().get(2).reason());
        assertEquals(FileProjection.Status.CHANGED, service.projection(doc).rows().get(6).status());
        assertEquals(FileProjection.Reason.TEXT_CHANGED, service.projection(doc).rows().get(6).reason());
        coverage(doc, 5, 6);
        assertEquals(original, service.history(doc));
    }

    public void testPullRapidReloads() throws Exception {
        Document doc = open("sample.py", SOURCE);
        initGit(); markAll(doc);
        ReviewHistory original = service.history(doc);
        String intermediate = SOURCE.replace("value + 1", "value + 10").replace("first + 2", "first + 20");
        String pulled = SOURCE.replace("value + 1", "value + 10");
        prepareLocalPullVersions(intermediate, pulled);

        git("pull", "--ff-only", ".", "review-pull-intermediate");
        reloadExternally(doc);
        service.projection(doc); // Start the intermediate worker without waiting for its result.
        git("pull", "--ff-only", ".", "review-pull-incoming");
        reloadExternally(doc); fresh(doc);

        assertEquals(pulled, doc.getText());
        assertEquals(FileSnapshot.create(pulled).id(), service.projection(doc).currentSnapshotId());
        assertEquals(FileProjection.Status.REVIEWED, service.projection(doc).rows().get(2).status());
        assertEquals(FileProjection.Reason.EXACT, service.projection(doc).rows().get(2).reason());
        assertEquals(FileProjection.Status.CHANGED, service.projection(doc).rows().get(1).status());
        coverage(doc, 3, 4);
        assertEquals(original, service.history(doc));
    }

    public void testGitDeleteAndRecreateDoNotDeleteApplicationHistory() throws Exception {
        Document doc = open("sample.py", SOURCE);
        initGit(); markAll(doc);
        ReviewHistory original = service.history(doc);
        git("checkout", "-q", "-b", "review-delete-b");
        git("rm", "-q", "--", sourceRelative()); git("commit", "-q", "-m", "remove source");
        FileDocumentManager.getInstance().getFile(doc).refresh(false, false); flush();
        assertEquals(original, store.history(getProject(), sourceRelative()));
        reloadHistory();
        git("checkout", "-q", "review-branch-a");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourcePath());
        assertNotNull(file); myFixture.configureFromExistingVirtualFile(file);
        doc = myFixture.getEditor().getDocument(); service.historyReloaded(); fresh(doc);
        coverage(doc, 4, 4); assertEquals(original, service.history(doc));
    }

    public void testRapidChangesCannotPublishAnOlderSnapshotProjection() {
        Document doc = open("sample.py", SOURCE);
        markAll(doc);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            replace(doc, "first + 2", "first + 20"); replace(doc, "value + 1", "value + 10"); replace(doc, "first + 20", "first + 200");
        });
        fresh(doc);
        assertEquals(FileSnapshot.create(doc.getText()).id(), service.projection(doc).currentSnapshotId());
        assertEquals(service.history(doc).revision(), service.projection(doc).historyRevision());
        assertFalse(green(doc, 1)); assertFalse(green(doc, 2)); assertTrue(green(doc, 0)); assertTrue(green(doc, 3));
    }

    public void testCoverageNavigationSaveAndSplitEditorsShareCurrentProjection() {
        Document doc = open("sample.py", "one = 1\n \ntwo = 2\nthree = 3\n");
        Editor second = EditorFactory.getInstance().createEditor(doc, getProject());
        try {
            markLine(doc, 0); markLine(doc, 3); coverage(doc, 2, 3);
            assertEquals(2, service.nextUnreviewedLine(doc, 0)); assertEquals(2, service.nextUnreviewedLine(doc, 3));
            markLine(doc, 2); assertEquals(-1, service.nextUnreviewedLine(doc, 0));
            FileDocumentManager.getInstance().saveDocument(doc); fresh(doc); coverage(doc, 3, 3);
            assertEquals(highlights(myFixture.getEditor()), highlights(second));
            assertTrue(highlights(second) > 0);
        } finally { EditorFactory.getInstance().releaseEditor(second); }
    }

    public void testScratchDocumentsAreNotPersistedAndSelectionBoundsRemainWholeLines() {
        Document scratch = EditorFactory.getInstance().createDocument("scratch");
        assertFalse(service.supports(scratch)); service.mark(scratch, 0, scratch.getTextLength());
        assertEmpty(service.history(scratch).evidence()); assertEmpty(service.getRanges(scratch));
        Document doc = open("sample.py", SOURCE);
        myFixture.getEditor().getSelectionModel().setSelection(2, doc.getLineStartOffset(2));
        int[] range = ReviewEditorAction.selectedLines(myFixture.getEditor());
        assertEquals(0, range[0]); assertEquals(doc.getLineStartOffset(2), range[1]);
    }

    public void testRenderDoReviewPreview() throws Exception {
        Document doc = open("sample.py", "# DoReview: immutable history\n\n" + SOURCE);
        service.mark(doc, doc.getLineStartOffset(2), doc.getTextLength()); fresh(doc);
        edit(doc, () -> replace(doc, "first + 2", "first + 3"));
        Editor editor = myFixture.getEditor(); editor.getSettings().setLineNumbersShown(true);
        var component = editor.getComponent(); component.setSize(1060, 400); layout(component); flush();
        var image = new BufferedImage(1060, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); try { component.printAll(graphics); } finally { graphics.dispose(); }
        Path output = Path.of("build", "preview", "do-review.png"); Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(image, "png", output.toFile()));
    }

    private Document open(String name, String text) {
        var file = myFixture.addFileToProject(name, text); myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        Document doc = myFixture.getEditor().getDocument(); assertTrue(service.supports(doc)); fresh(doc); return doc;
    }
    private void markAll(Document doc) { service.mark(doc, 0, doc.getTextLength()); fresh(doc); }
    private void markLine(Document doc, int line) {
        int end = doc.getLineEndOffset(line); if (end < doc.getTextLength()) end++;
        service.mark(doc, doc.getLineStartOffset(line), end); fresh(doc);
    }
    private void edit(Document doc, Runnable edit) { fresh(doc); WriteCommandAction.runWriteCommandAction(getProject(), edit); fresh(doc); }
    private static void replace(Document doc, String before, String after) {
        int offset = doc.getText().indexOf(before); assertTrue("Missing replacement text", offset >= 0);
        doc.replaceString(offset, offset + before.length(), after);
    }
    private void fresh(Document doc) {
        PlatformTestUtil.waitWithEventsDispatching("Current review projection did not finish", () -> service.isProjectionReady(doc), 10); flush();
    }
    private boolean green(Document doc, int line) {
        return service.getRanges(doc).stream().anyMatch(range -> range.start() <= doc.getLineStartOffset(line) && range.end() >= doc.getLineEndOffset(line));
    }
    private void coverage(Document doc, int reviewed, int total) {
        assertEquals(reviewed, service.coverage(doc).reviewed()); assertEquals(total, service.coverage(doc).total());
    }
    private ReviewHistoryStore.StoreState xmlState() {
        return XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class);
    }
    private void reloadHistory() { store.loadState(xmlState()); service.historyReloaded(); }
    private String sourceRelative() {
        return Path.of(getProject().getBasePath()).relativize(Path.of(myFixture.getTempDirFixture().getTempDirPath(), "sample.py")).toString().replace('\\', '/');
    }
    private Path sourcePath() { return Path.of(getProject().getBasePath(), sourceRelative()); }
    private void initGit() throws Exception {
        FileDocumentManager.getInstance().saveAllDocuments();
        Path template = Path.of(getProject().getBasePath(), ".git-empty-template");
        Files.createDirectories(template);
        git("init", "-q", "-b", "review-branch-a", "--template=" + template);
        git("add", "--", sourceRelative()); git("commit", "-q", "-m", "version A");
    }
    private void commitSource(String text, String message) throws Exception {
        Files.writeString(sourcePath(), text);
        git("add", "--", sourceRelative()); git("commit", "-q", "-m", message);
    }
    private void prepareLocalPullVersions(String intermediate, String current) throws Exception {
        git("checkout", "-q", "-b", "review-pull-incoming");
        commitSource(intermediate, "intermediate source");
        git("branch", "review-pull-intermediate");
        commitSource(current, "restore reviewed row while keeping neighbor change");
        git("checkout", "-q", "review-branch-a");
    }
    private void git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=DoReview Test", "-c", "user.email=doreview@test.invalid",
                "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false", "-c", "core.longpaths=true",
                "-c", "core.hooksPath=" + Path.of(getProject().getBasePath(), ".test-no-hooks")));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(Path.of(getProject().getBasePath()).toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(output, 0, process.waitFor());
    }
    private void reload(Document doc) {
        FileDocumentManager.getInstance().getFile(doc).refresh(false, false); FileDocumentManager.getInstance().reloadFromDisk(doc);
        service.historyReloaded(); fresh(doc);
    }
    private static void reloadExternally(Document doc) {
        var manager = FileDocumentManager.getInstance();
        manager.getFile(doc).refresh(false, false);
        manager.reloadFromDisk(doc);
    }
    private static int highlights(Editor editor) {
        return (int) Arrays.stream(editor.getMarkupModel().getAllHighlighters()).filter(highlight -> Boolean.TRUE.equals(highlight.getUserData(DoReviewService.REVIEWED_HIGHLIGHTER))).count();
    }
    private ReviewStripePanel stripe(Editor editor) {
        var host = ReviewStripeHost.find(editor);
        if (host == null) {
            if (stripeTestHost == null) {
                var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
                assertNotNull(file);
                var mirror = EditorFactory.getInstance().createEditor(editor.getDocument(), getProject(), file,
                        false, EditorKind.MAIN_EDITOR);
                var createdHost = new TextEditorComponent(file, (EditorImpl) mirror);
                stripeTestHost = createdHost;
                Disposer.register(getTestRootDisposable(), () -> {
                    if (!mirror.isDisposed()) createdHost.dispose();
                });
            }
            assertSame(editor.getDocument(), stripeTestHost.getEditor().getDocument());
            host = stripeTestHost;
            flush();
        }
        assertNotNull("A real main editor must have its outer stripe host", host);
        var panels = Arrays.stream(host.getComponents()).filter(ReviewStripePanel.class::isInstance)
                .map(ReviewStripePanel.class::cast).toList();
        assertEquals(1, panels.size());
        host.setSize(1000, 400);
        layout(host); flush();
        assertTrue("The actual editor viewport must give the stripe a paintable track", panels.getFirst().trackBounds().height > 0);
        return panels.getFirst();
    }
    private static void flush() { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); }
    private static void layout(Container container) { container.doLayout(); for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested); }
}
