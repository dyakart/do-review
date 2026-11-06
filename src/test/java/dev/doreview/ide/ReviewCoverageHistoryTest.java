package dev.doreview.ide;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.EvidenceRef;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

/** Coverage inspection must not become editor loading or pending-event publication. */
public final class ReviewCoverageHistoryTest extends BasePlatformTestCase {
    private ReviewHistoryStore store;
    private Path data;
    private String previousData, previousLegacy;

    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture project = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(project, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(project.getProject().getBasePath()); }
        });
    }

    @Override protected void setUp() throws Exception {
        previousData = System.getProperty("doreview.data.path");
        previousLegacy = System.getProperty("doreview.legacy.xml.path");
        data = Files.createTempDirectory("doreview-coverage-data-");
        System.setProperty("doreview.data.path", data.toString());
        System.setProperty("doreview.legacy.xml.path", data.resolve("absent.xml").toString());
        super.setUp();
        store = new ReviewHistoryStore();
    }

    @Override protected void tearDown() throws Exception {
        var owned = store;
        var directory = data;
        String restoreData = previousData, restoreLegacy = previousLegacy;
        try {
            if (owned != null) {
                owned.dispose();
                await(owned.storageClosed());
            }
            if (myFixture != null) {
                Path git = Path.of(getProject().getBasePath(), ".git");
                if (Files.isDirectory(git)) try (var paths = Files.walk(git)) {
                    for (Path path : paths.toList()) {
                        var dos = Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS);
                        if (dos != null) dos.setReadOnly(false);
                    }
                }
            }
            super.tearDown();
        } finally {
            restore("doreview.data.path", restoreData); restore("doreview.legacy.xml.path", restoreLegacy);
            if (directory != null) try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    public void testColdPendingReadDoesNotPublishWarmViewsOrOpenEditors() throws Exception {
        String text = "one\ntwo\n";
        myFixture.addFileToProject("closed.txt", text);
        FileDocumentManager.getInstance().saveAllDocuments();
        var base = Path.of(getProject().getBasePath());
        await(CompletableFuture.runAsync(() -> {
            try (var repository = new SharedHistoryRepository(base, ReviewRepositoryPaths.databasePath(base))) {
                var state = new ReviewHistoryStore.FileState(); state.path = "closed.txt";
                ReviewJournalCodec.write(state, reviewed(text));
                repository.savePending(repository.fileIdFor("closed.txt"), "closed.txt", state,
                        new SharedHistoryRepository.Author("Coverage reviewer", ""), repository.checkoutToken());
            }
        }));
        assertFalse(Files.exists(base.resolve(".doreview")));
        int openEditors = FileEditorManager.getInstance(getProject()).getOpenFiles().length;
        var reader = store.coverageHistory(getProject());
        var result = await(reader.read("closed.txt", text, false));
        assertEquals(1, result.history().evidence().size());
        assertTrue(await(reader.isCurrent()));
        assertTrue("Inspecting a closed file must not create an editor view", store.paths(getProject()).isEmpty());
        assertEquals(openEditors, FileEditorManager.getInstance(getProject()).getOpenFiles().length);
        assertFalse("Reading pending evidence must not flush portable events or snapshots", Files.exists(base.resolve(".doreview")));
        await(CompletableFuture.runAsync(() -> {
            try (var repository = new SharedHistoryRepository(base, ReviewRepositoryPaths.databasePath(base))) {
                assertFalse("Pending work must remain durable and unpublished", repository.pendingFiles(repository.checkoutToken()).isEmpty());
            }
        }));
        store.dispose();
        await(store.storageClosed());
        assertFalse("Closing a coverage-only session must not publish inspected pending work",
                Files.exists(base.resolve(".doreview")));
    }

    public void testGitCheckoutChangeInvalidatesCoverageWithoutPublishingFiles() throws Exception {
        String text = "one\ntwo\n";
        Path base = Path.of(getProject().getBasePath());
        await(CompletableFuture.runAsync(() -> {
            try { Files.writeString(base.resolve("branch.txt"), text); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            git(base, "init", "--template=", "-q", "-b", "source");
            git(base, "add", "branch.txt"); git(base, "commit", "-qm", "Initial source");
        }));
        var reader = store.coverageHistory(getProject());
        assertTrue(await(reader.read("branch.txt", text, true)).history().evidence().isEmpty());
        assertTrue(await(reader.isCurrent()));
        await(CompletableFuture.runAsync(() -> git(base, "checkout", "-qb", "feature")));
        assertFalse("Different branch names at the same commit must invalidate a captured report", await(reader.isCurrent()));
        assertFalse(Files.exists(base.resolve(".doreview")));
        assertTrue(store.paths(getProject()).isEmpty());
        store.dispose(); await(store.storageClosed());
        assertFalse(Files.exists(base.resolve(".doreview")));
    }

    public void testFreshExternalRevocationCannotBeHiddenByAWarmView() throws Exception {
        // The query itself must reconcile and notify views; a periodic poll must not hide
        // a stale-view bug by racing the reproduction before its first explicit query.
        var field = SharedReviewHistoryStore.class.getDeclaredField("watcher"); field.setAccessible(true);
        ((ScheduledExecutorService) field.get(store.sharedStorage())).shutdownNow();
        String text = "one\ntwo\n";
        myFixture.addFileToProject("warm.txt", text);
        var loaded = new CompletableFuture<Void>();
        store.whenLoaded(getProject(), "warm.txt", text, () -> loaded.complete(null));
        await(loaded);
        var proof = store.mark(getProject(), "warm.txt", text, 0, text.length());
        assertNotNull(proof); await(store.storageIdle());
        Path base = Path.of(getProject().getBasePath());
        await(CompletableFuture.runAsync(() -> {
            try (var repository = new SharedHistoryRepository(base, ReviewRepositoryPaths.databasePath(base))) {
                var before = repository.load("warm.txt");
                var after = SharedHistoryRepository.copyFile(before.state());
                ReviewJournalCodec.write(after, ReviewHistoryEngine.revoke(ReviewJournalCodec.read(after),
                        List.of(new EvidenceRef(proof.id(), 0, 4))));
                repository.publish(before.fileId(), "warm.txt", before.state(), after,
                        new SharedHistoryRepository.Author("Other reviewer", ""));
            }
        }));
        assertTrue("The fixture must start with a stale warmed model", store.history(getProject(), "warm.txt").revocations().isEmpty());
        try {
            var result = await(store.coverageHistory(getProject()).read("warm.txt", text, true));
            assertEquals("A new snapshot cannot use pre-revocation warm facts", 1, result.history().revocations().size());
        } catch (CompletionException changed) {
            assertTrue(changed.getCause().toString(), changed.getCause() instanceof java.util.ConcurrentModificationException);
        }
        PlatformTestUtil.waitWithEventsDispatching("Coverage reconciliation must also reload the editor's warmed model",
                () -> store.isLoaded(getProject(), "warm.txt") && store.history(getProject(), "warm.txt").revocations().size() == 1, 20);
        var current = store.coverageHistory(getProject());
        assertEquals(1, await(current.read("warm.txt", text, true)).history().revocations().size());
        assertTrue(await(current.isCurrent()));
    }

    public void testWarmUnsavedEvidenceIsReadAndSubsequentLocalChangeInvalidatesReader() {
        String disk = "disk\n", unsaved = "unsaved\nsecond\n";
        myFixture.addFileToProject("warm.txt", disk);
        var loaded = new CompletableFuture<Void>();
        store.whenLoaded(getProject(), "warm.txt", unsaved, () -> loaded.complete(null));
        await(loaded);
        var proof = store.mark(getProject(), "warm.txt", unsaved, 0, unsaved.length());
        assertNotNull(proof);
        var reader = store.coverageHistory(getProject());
        var input = await(reader.read("warm.txt", unsaved, true));
        assertEquals(proof.id(), input.history().evidence().getFirst().id());
        assertEquals(unsaved, input.history().snapshots().get(proof.snapshotId()).text());
        assertTrue(input.hints().authoritativeRefs().isEmpty());
        assertTrue(await(reader.isCurrent()));
        store.clearFile(getProject(), "warm.txt");
        assertFalse("A report must not publish counts captured before a local clear", await(reader.isCurrent()));
    }

    public void testDisposedStorageCompletesCoverageQueryExceptionally() {
        var reader = store.coverageHistory(getProject());
        store.dispose();
        var result = reader.read("closed.txt", "one\n", true);
        assertTrue(result.isDone());
        try { result.join(); fail("A closed storage query must not wait forever"); }
        catch (CancellationException expected) { }
    }

    public void testDetachedReaderRejectsHistoryChangesAndDoesNotWriteCheckpoints() throws Exception {
        var detached = new ReviewHistoryStore(data.resolve("legacy-backup"));
        detached.mark(getProject(), "detached.txt", "one\ntwo\n", 0, 4);
        var reader = detached.coverageHistory(getProject());
        var input = await(reader.read("detached.txt", "one\ntwo\n", false));
        assertEquals(1, input.history().evidence().size());
        assertTrue(await(reader.isCurrent()));
        var file = detached.getState().projects.values().iterator().next().files.values().iterator().next();
        assertTrue(file.checkpoints.isEmpty());
        detached.mark(getProject(), "detached.txt", "one\ntwo\n", 4, 8);
        assertFalse(await(reader.isCurrent()));
    }

    private static ReviewHistory reviewed(String text) {
        return ReviewHistoryEngine.review(ReviewHistory.empty(), text, List.of(new ReviewedRange(0, text.length())), 1);
    }

    private static <T> T await(CompletableFuture<T> future) {
        PlatformTestUtil.waitWithEventsDispatching("Coverage storage query did not finish", future::isDone, 20);
        return future.join();
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }

    private static void git(Path root, String... arguments) {
        try {
            var command = new ArrayList<>(List.of("git", "-C", root.toString(), "-c", "user.name=Coverage reviewer",
                    "-c", "user.email=coverage@example.test", "-c", "commit.gpgsign=false", "-c", "core.longpaths=true",
                    "-c", "core.hooksPath=" + root.resolve("disabled-hooks")));
            command.addAll(List.of(arguments));
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            ReviewGitHistory.prepareEnvironment(builder.environment());
            var process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Git test timed out"); }
            assertEquals(output, 0, process.exitValue());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
