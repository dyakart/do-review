package dev.doreview.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.extensions.LoadingOrder;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import dev.doreview.core.ContextMap;
import dev.doreview.core.FileProjection;
import dev.doreview.ide.context.ContextProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Stable editor resources across metadata refreshes, with real production storage. */
public final class ReviewBackgroundRefreshTest extends BasePlatformTestCase {
    private static final String SOURCE = "def compute(value):\n    first = value + 1\n    second = first + 2\n    return second\n";
    private ReviewHistoryStore store;
    private DoReviewService service;
    private String previousDataPath;

    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture project = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(project, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(project.getProject().getBasePath()); }
        });
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        Disposer.dispose(getProject().getService(DoReviewService.class));
        var idle = ReviewHistoryStore.getInstance().storageIdle();
        PlatformTestUtil.waitWithEventsDispatching("Startup storage did not release the project", idle::isDone, 20);
        idle.join();
        previousDataPath = System.getProperty("doreview.data.path");
        assertNotNull("Test history must stay outside the user's application data", previousDataPath);
        System.setProperty("doreview.data.path", Path.of(previousDataPath).resolve("background-" + UUID.randomUUID()).toString());
        store = new ReviewHistoryStore();
        ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), ReviewHistoryStore.class, store, getTestRootDisposable());
        service = new DoReviewService(getProject());
        ServiceContainerUtil.replaceService(getProject(), DoReviewService.class, service, getTestRootDisposable());
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (service != null) Disposer.dispose(service);
            if (store != null) {
                store.dispose();
                PlatformTestUtil.waitWithEventsDispatching("Storage did not close", () -> store.storageClosed().isDone(), 20);
                store.storageClosed().join();
            }
            if (myFixture != null && Files.isDirectory(base())) try (var files = Files.walk(base())) {
                for (var path : files.toList()) {
                    var attributes = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes != null) attributes.setReadOnly(false);
                }
            }
        } finally {
            if (previousDataPath != null) System.setProperty("doreview.data.path", previousDataPath);
            super.tearDown();
        }
    }

    public void testRepeatedOwnHistoryRefreshKeepsProjectionAndHighlighters() throws Exception {
        Document document = reviewed(true);
        var projection = service.projection(document);
        var highlighters = confirmed();
        long generation = generation(document);
        assertFalse(highlighters.isEmpty());
        for (int i = 0; i < 30; i++) {
            notifyHistory(false);
            service.historyReloaded();
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            assertSame("Unchanged history must reuse its projection", projection, service.projection(document));
            assertEquals("Background refresh must retain the same editor resources", highlighters, confirmed());
            assertEquals("Unchanged facts must not start replacement projection jobs", generation, generation(document));
        }
        assertTrue(highlighters.stream().allMatch(RangeHighlighter::isValid));
    }

    public void testGitMetadataAndOwnCheckpointVfsHintsDoNotBlankTheEditor() throws Exception {
        Document document = reviewed(true);
        var projection = service.projection(document);
        var highlighters = confirmed();
        long generation = generation(document);
        var head = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base().resolve(".git/HEAD"));
        var source = FileDocumentManager.getInstance().getFile(document);
        Path root = ReviewRepositoryPaths.rootFor(source.toNioPath(), base());
        List<Path> artifacts;
        try (var files = Files.walk(root.resolve(".doreview"))) { artifacts = files.toList(); }
        var eventsOnDisk = artifacts.stream().filter(path -> path.toString().endsWith(".json")).toList();
        assertFalse("New review evidence must exist before testing its metadata hints", service.history(document).evidence().isEmpty());
        assertFalse("A completed mark must publish a portable event: " + artifacts, eventsOnDisk.isEmpty());
        Path sharedEvent = eventsOnDisk.getFirst();
        var shared = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sharedEvent);
        assertNotNull(head); assertNotNull(shared);
        var events = List.of(new VFileContentChangeEvent(this, head, head.getModificationStamp(), head.getModificationStamp() + 1, false),
                new VFileContentChangeEvent(this, shared, shared.getModificationStamp(), shared.getModificationStamp() + 1, false));
        var listener = getProject().getMessageBus().syncPublisher(VirtualFileManager.VFS_CHANGES);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            listener.before(events);
            assertEquals("Metadata hints must not synchronously clear valid marks", highlighters, confirmed());
            listener.after(events);
        });
        assertEquals(highlighters, confirmed());
        settle(document);
        assertSame(projection, service.projection(document));
        assertEquals(highlighters, confirmed());
        assertEquals(generation, generation(document));
    }

    public void testAdditiveHistoryRefreshKeepsValidMarksWhileCaptureRuns() throws Exception {
        var blockedText = new AtomicReference<String>();
        var started = new AtomicBoolean();
        var release = new CountDownLatch(1);
        installBlockingProvider(blockedText, started, release);
        Document document = reviewed(false);
        var highlighters = confirmed();
        String archived = SOURCE + "\n# archived evidence\n";
        blockedText.set(archived);
        int start = archived.indexOf("# archived");
        assertNotNull(store.mark(getProject(), relative(), archived, start, archived.length()));
        try {
            notifyHistory(false);
            service.projection(document);
            PlatformTestUtil.waitWithEventsDispatching("Historical capture did not reach its barrier", started::get, 20);
            assertFalse(service.isProjectionReady(document));
            assertEquals("Previously confirmed unchanged rows stay visible", highlighters, confirmed());
            assertEquals(4, service.coverage(document).reviewed());
            assertTrue(highlighters.stream().allMatch(RangeHighlighter::isValid));
        } finally { release.countDown(); }
        settle(document);
        assertEquals(4, service.coverage(document).reviewed());
    }

    public void testRealTextEditClearsConfirmedMarksBeforeBackgroundCapture() throws Exception {
        var blockedText = new AtomicReference<String>();
        var started = new AtomicBoolean();
        var release = new CountDownLatch(1);
        installBlockingProvider(blockedText, started, release);
        Document document = reviewed(false);
        var highlighters = confirmed();
        String changed = SOURCE.replace("first + 2", "first + 20");
        blockedText.set(changed);
        try {
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(changed));
            assertEmpty("An edited document cannot keep confirmed highlighters from the old text", confirmed());
            assertTrue(highlighters.stream().noneMatch(RangeHighlighter::isValid));
            service.projection(document);
            PlatformTestUtil.waitWithEventsDispatching("Changed capture did not reach its barrier", started::get, 20);
            assertEmpty(confirmed());
        } finally { release.countDown(); }
        settle(document);
        assertEquals(FileProjection.Status.CHANGED, service.projection(document).rows().get(2).status());
    }

    public void testIncomingRevocationCannotRetainConfirmedPresentation() throws Exception {
        Document document = reviewed(true);
        var highlighters = confirmed();
        var refs = service.projection(document).rows().getFirst().revocableRefs();
        assertFalse(refs.isEmpty());
        store.revoke(getProject(), relative(), refs);
        notifyHistory(false);
        assertEmpty("A changed revocation set must clear old confirmed resources immediately", confirmed());
        assertTrue(highlighters.stream().noneMatch(RangeHighlighter::isValid));
        assertEquals(FileProjection.Status.UNREVIEWED, service.projection(document).rows().getFirst().status());
        settle(document);
        assertEquals(3, service.coverage(document).reviewed());
    }

    public void testLoadingReplacementRetainsConfirmedResourcesAndAuthors() throws Exception {
        Document document = reviewed(true);
        var projection = service.projection(document);
        var highlighters = confirmed();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workerField = SharedReviewHistoryStore.class.getDeclaredField("worker");
        workerField.setAccessible(true);
        var worker = (ExecutorService) workerField.get(store.sharedStorage());
        var blocker = worker.submit(() -> {
            started.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(started.await(20, TimeUnit.SECONDS));
            store.reloadShared(getProject());
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            assertFalse(store.isLoaded(getProject(), relative()));
            assertSame(projection, service.projection(document));
            assertEquals("A replacement load must retain resources and their existing author metadata", highlighters, confirmed());
            assertTrue(highlighters.stream().allMatch(RangeHighlighter::isValid));
        } finally { release.countDown(); }
        blocker.get(20, TimeUnit.SECONDS);
        settle(document);
        assertEquals(highlighters, confirmed());
    }

    public void testGitProjectionIgnoresSyntaxProviderChanges() throws Exception {
        Document document = reviewed(true);
        var projection = service.projection(document);
        var highlighters = confirmed();
        long generation = generation(document);
        installBlockingProvider(new AtomicReference<>(SOURCE), new AtomicBoolean(), new CountDownLatch(0));
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        assertSame("Git mappings do not depend on syntax context providers", projection, service.projection(document));
        assertEquals(highlighters, confirmed());
        assertEquals(generation, generation(document));
    }

    public void testRepositoryDiffConfigurationRecomputesGitRowsWithoutBlanking() throws Exception {
        String baseline = "B\nA\nB\nB\nA\nB\nA\nD\nC\nC\n";
        String current = "D\nB\nC\nB\nB\nC\nB\nC\nC\nB\n";
        Document document = reviewed(true, baseline);
        git("config", "diff.algorithm", "myers");
        var config = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base().resolve(".git/config"));
        assertNotNull(config);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(current));
        settle(document);
        assertEquals(6, service.coverage(document).reviewed());
        var previousResources = confirmed();
        long previousGeneration = generation(document);
        git("config", "diff.algorithm", "patience");
        var listener = getProject().getMessageBus().syncPublisher(VirtualFileManager.VFS_CHANGES);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> listener.after(List.of(
                new VFileContentChangeEvent(this, config, config.getModificationStamp(), config.getModificationStamp() + 1, false))));
        assertEquals("Configuration hints retain the last confirmed presentation until replacement delivery", previousResources, confirmed());
        settle(document);
        assertTrue("A changed diff configuration must start a replacement Git mapping", generation(document) > previousGeneration);
        assertEquals(3, service.coverage(document).reviewed());
        assertEquals(FileProjection.Status.REVIEWED, service.projection(document).rows().getFirst().status());
    }

    public void testUnrelatedConfigEventDoesNotRecomputeGitProjection() throws Exception {
        Document document = reviewed(true);
        var unrelated = myFixture.addFileToProject("unrelated/config", "unrelated settings\n").getVirtualFile();
        long previousGeneration = generation(document);
        var projection = service.projection(document);
        var listener = getProject().getMessageBus().syncPublisher(VirtualFileManager.VFS_CHANGES);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> listener.after(List.of(
                new VFileContentChangeEvent(this, unrelated, unrelated.getModificationStamp(), unrelated.getModificationStamp() + 1, false))));
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        assertSame(projection, service.projection(document));
        assertEquals(previousGeneration, generation(document));
    }

    private void installBlockingProvider(AtomicReference<String> blockedText, AtomicBoolean started, CountDownLatch release) {
        ContextProvider provider = new ContextProvider() {
            @Override public String id() { return "test:background-refresh-v1"; }
            @Override public boolean supports(PsiFile file) { return file.getName().equals("sample.py"); }
            @Override public ContextMap analyze(PsiFile file, String text) {
                if (text.equals(blockedText.get())) {
                    started.set(true);
                    try {
                        while (!release.await(20, TimeUnit.MILLISECONDS)) ProgressManager.checkCanceled();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        ProgressManager.checkCanceled();
                        throw new IllegalStateException(interrupted);
                    }
                }
                return new ContextMap(id(), Collections.nCopies(1 + (int) text.chars().filter(c -> c == '\n').count(), "same-context"));
            }
        };
        ContextProvider.EP.getPoint().registerExtension(provider, LoadingOrder.FIRST, getTestRootDisposable());
    }

    private Document reviewed(boolean git) throws Exception {
        return reviewed(git, SOURCE);
    }

    private Document reviewed(boolean git, String source) throws Exception {
        if (git) git("init", "-q", "-b", "review-a", "--template=" + Files.createDirectories(base().resolve("empty-template")));
        var file = myFixture.addFileToProject("sample.py", source).getVirtualFile();
        Document document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        if (git) {
            FileDocumentManager.getInstance().saveDocument(document);
            git("add", base().relativize(file.toNioPath()).toString()); git("commit", "-qm", "Source baseline");
        }
        myFixture.configureFromExistingVirtualFile(file);
        service.initialize();
        service.mark(document, 0, document.getTextLength());
        // The initial load installs its empty model before delivering the deferred mark
        // callback on EDT. Projection readiness alone does not mean that command ran.
        PlatformTestUtil.waitWithEventsDispatching("Deferred mark did not complete",
                () -> !service.history(document).evidence().isEmpty(), 20);
        settle(document);
        assertEquals((int) source.lines().filter(line -> !line.isBlank()).count(), service.coverage(document).reviewed());
        return document;
    }

    private void settle(Document document) {
        PlatformTestUtil.waitWithEventsDispatching("Projection did not settle", () -> service.isProjectionReady(document), 20);
        store.flushShared(getProject());
        var idle = store.storageIdle();
        PlatformTestUtil.waitWithEventsDispatching("Storage did not settle", idle::isDone, 20);
        idle.join();
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        PlatformTestUtil.waitWithEventsDispatching("Refreshed projection did not settle", () -> service.isProjectionReady(document), 20);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        assertTrue(store.diagnostics(getProject()).toString(), store.diagnostics(getProject()).isEmpty());
    }

    private void notifyHistory(boolean replacement) {
        ApplicationManager.getApplication().getMessageBus().syncPublisher(ReviewHistoryStore.SHARED_UPDATES)
                .historyUpdated(getProject(), relative(), replacement);
    }

    private List<RangeHighlighter> confirmed() {
        return Arrays.stream(myFixture.getEditor().getMarkupModel().getAllHighlighters())
                .filter(highlighter -> Boolean.TRUE.equals(highlighter.getUserData(DoReviewService.REVIEWED_HIGHLIGHTER))).toList();
    }

    private long generation(Document document) throws Exception {
        var runtimeField = DoReviewService.class.getDeclaredField("runtime"); runtimeField.setAccessible(true);
        var runtime = (Map<?, ?>) runtimeField.get(service);
        var state = runtime.get(document);
        var field = state.getClass().getDeclaredField("generation"); field.setAccessible(true);
        return field.getLong(state);
    }

    private Path base() { return Path.of(getProject().getBasePath()).toAbsolutePath().normalize(); }

    private String relative() {
        var file = FileDocumentManager.getInstance().getFile(myFixture.getEditor().getDocument());
        return base().relativize(Path.of(file.getPath())).toString().replace('\\', '/');
    }

    private void git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Refresh Test", "-c", "user.email=refresh@example.invalid",
                "-c", "commit.gpgsign=false", "-c", "core.longpaths=true",
                "-c", "core.hooksPath=" + base().resolve("no-test-hooks")));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(base().toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor());
    }
}
