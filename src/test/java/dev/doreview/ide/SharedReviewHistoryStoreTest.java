package dev.doreview.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.extensions.LoadingOrder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.xmlb.XmlSerializer;
import dev.doreview.core.EvidenceRef;
import dev.doreview.core.ContextMap;
import dev.doreview.core.FileSnapshot;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ReviewedRange;
import dev.doreview.ide.context.ContextProvider;
import com.intellij.psi.PsiFile;
import org.jdom.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real default facade, asynchronous coordinator, portable files and native SQLite. */
public final class SharedReviewHistoryStoreTest extends BasePlatformTestCase {
    private static final String SOURCE = "def compute(value):\n    first = value + 1\n    second = first + 2\n    return second\n";
    private static final String AUTHOR = "Shared Review Author";
    private static final String EMAIL = "shared-review@example.test";
    private final List<ReviewHistoryStore> ownedStores = new ArrayList<>();
    private final Set<ReviewHistoryStore> closedStores = Collections.newSetFromMap(new IdentityHashMap<>());
    private ReviewHistoryStore store;
    private DoReviewService service;
    private String previousLegacySource;
    private Path legacySource;

    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture project = factory.createFixtureBuilder("shared-history").getFixture();
        return factory.createCodeInsightFixture(project, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(project.getProject().getBasePath()); }
        });
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertNotNull("The Gradle test runtime must isolate databases from real application data", System.getProperty("doreview.data.path"));
        previousLegacySource = System.getProperty("doreview.legacy.xml.path");
        legacySource = base().resolve("legacy-config/review-marks-history.xml");
        System.setProperty("doreview.legacy.xml.path", legacySource.toString());
        // Project startup can initialize its ordinary service before the fixture replaces it.
        // Remove its listeners and wait for that coordinator to release this project's SQLite session.
        Disposer.dispose(getProject().getService(DoReviewService.class));
        var originalIdle = ReviewHistoryStore.getInstance().storageIdle();
        PlatformTestUtil.waitWithEventsDispatching("The startup service did not release the fixture project",
                originalIdle::isDone, 20);
        originalIdle.join();
        installFreshFacade();
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (service != null) Disposer.dispose(service);
            for (var owned : ownedStores) {
                if (closedStores.contains(owned)) continue;
                try { drain(owned); } finally { close(owned); }
            }
        } finally {
            if (previousLegacySource == null) System.clearProperty("doreview.legacy.xml.path");
            else System.setProperty("doreview.legacy.xml.path", previousLegacySource);
            try { writableGitObjects(); } finally { super.tearDown(); }
        }
    }

    public void testFirstReviewWaitsForProductionLoadAndPersistsAuthorAndSQLite() throws Exception {
        initGit(base(), AUTHOR, EMAIL);
        var file = myFixture.addFileToProject("sample.py", SOURCE).getVirtualFile();
        Document document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        service.initialize();

        assertTrue("This must exercise the production coordinator, not detached XML state", store.isShared());
        assertNull("The production facade must not serialize a replacement XML journal", store.getState());
        assertFalse("The first command starts before its file is warm", store.isLoaded(getProject(), relative(file.getPath())));
        service.mark(document, 0, document.getTextLength());
        fresh(document);
        drain(store);
        fresh(document);

        assertEquals("The deferred first command must execute once", 1, service.history(document).evidence().size());
        assertEquals(4, service.coverage(document).reviewed());
        assertTrue(green(document, 1));
        assertTrue(store.diagnostics(getProject()).toString(), store.diagnostics(getProject()).isEmpty());
        assertFalse(events(base()).isEmpty());
        var snapshotRoot = base().resolve(".doreview/v1/snapshots");
        try (var files = Files.walk(snapshotRoot)) {
            assertEquals("Repeated UI refreshes must reuse a content-addressed snapshot", 1L,
                    files.filter(path -> path.toString().endsWith(".txt")).count());
        }
        assertSQLite(base());
        var versions = versions(store, relative(file.getPath()));
        assertEquals(1, versions.size());
        assertEquals(AUTHOR, versions.getFirst().author().name());
        assertEquals(EMAIL, versions.getFirst().author().email());
    }

    public void testNestedGitRootsHaveIndependentDatabasesAndPortableRelativePaths() throws Exception {
        initGit(base(), AUTHOR, EMAIL);
        var outer = myFixture.addFileToProject("outer.py", SOURCE).getVirtualFile();
        var inner = myFixture.addFileToProject("nested/inner.py", SOURCE).getVirtualFile();
        Path nested = Path.of(inner.getPath()).getParent();
        initGit(nested, "Nested Author", "nested@example.test");
        String outerPath = relative(outer.getPath()), innerPath = relative(inner.getPath());
        loaded(store, outerPath, SOURCE);
        loaded(store, innerPath, SOURCE);
        assertNotNull(store.mark(getProject(), outerPath, SOURCE, 0, SOURCE.length()));
        assertNotNull(store.mark(getProject(), innerPath, SOURCE, 0, SOURCE.length()));
        drain(store);

        assertSQLite(base());
        assertSQLite(nested);
        assertFalse(ReviewRepositoryPaths.databasePath(base()).equals(ReviewRepositoryPaths.databasePath(nested)));
        assertFalse(events(base()).isEmpty());
        assertFalse(events(nested).isEmpty());
        String outerEvent = Files.readString(events(base()).getFirst());
        String innerEvent = Files.readString(events(nested).getFirst());
        assertTrue(outerEvent, outerEvent.contains("\"path\":\"" + outerPath + "\""));
        assertTrue(innerEvent, innerEvent.contains("\"path\":\"inner.py\""));
        assertFalse("The portable path must be relative to the actual nested Git root", innerEvent.contains("nested/inner.py"));
        assertFalse("The shared journal must not encode a machine's absolute project directory", outerEvent.contains(base().toString().replace('\\', '/')));
        assertEquals("Nested Author", versions(store, innerPath).getFirst().author().name());
    }

    public void testFreshProductionFacadeReadsPortableReviewsWithoutRepublishing() throws Exception {
        initGit(base(), AUTHOR, EMAIL);
        var file = myFixture.addFileToProject("sample.py", SOURCE).getVirtualFile();
        String path = relative(file.getPath());
        loaded(store, path, SOURCE);
        var proof = store.mark(getProject(), path, SOURCE, 0, SOURCE.length());
        assertNotNull(proof);
        drain(store);
        var before = events(base());
        restartFacade();
        loaded(store, path, SOURCE);
        drain(store);

        assertTrue(store.isShared());
        assertEquals(proof.id(), store.history(getProject(), path).evidence().getFirst().id());
        assertEquals("Loading an existing checkout must not synthesize another public operation", before, events(base()));
        assertEquals(AUTHOR, versions(store, path).getFirst().author().name());
        assertSQLite(base());
    }

    public void testCopiedLineProvenanceSurvivesSaveCloseAndFreshFacade() throws Exception {
        initGit(base(), AUTHOR, EMAIL);
        var file = myFixture.addFileToProject("sample.py", SOURCE).getVirtualFile();
        myFixture.configureFromExistingVirtualFile(file);
        Document document = myFixture.getEditor().getDocument();
        service.initialize();
        fresh(document);
        int start = document.getLineStartOffset(1), end = document.getLineStartOffset(2);
        service.mark(document, start, end);
        fresh(document);
        String copied = document.getText().substring(start, end);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(start, copied));
        fresh(document);
        assertFalse("A newly inserted identical row is not reviewed", green(document, 1));
        assertTrue("The original reviewed row retains its identity", green(document, 2));
        FileDocumentManager.getInstance().saveDocument(document);
        store.flushShared(getProject());
        drain(store);
        store.release(getProject(), relative(file.getPath()));
        drain(store);
        myFixture.configureByText("other.py", "pass\n");
        restartFacade();

        myFixture.configureFromExistingVirtualFile(file);
        Document reopened = myFixture.getEditor().getDocument();
        service.initialize();
        fresh(reopened);
        assertEquals("The actual saved source, including its inserted copy, is reopened", SOURCE.substring(0, start) + copied + SOURCE.substring(start), reopened.getText());
        assertFalse("Cold loading exported provenance must not assign the original proof to its copy", green(reopened, 1));
        assertTrue("The original remains reviewed after the editor and storage facade are replaced", green(reopened, 2));
        assertEquals(1, service.history(reopened).evidence().size());
        assertTrue(store.diagnostics(getProject()).toString(), store.diagnostics(getProject()).isEmpty());
    }

    public void testLegacyXmlIsImportedWithAuthorAndByteExactBackupOnlyOnce() throws Exception {
        var file = myFixture.addFileToProject("legacy.py", SOURCE).getVirtualFile();
        String path = relative(file.getPath());
        var legacy = new LegacyReviewHistoryModel(base().resolve("legacy-fixture-backups"));
        var proof = legacy.mark(getProject(), path, SOURCE, 0, SOURCE.length());
        Element component = XmlSerializer.serialize(legacy.getState()).setName("component").setAttribute("name", "ReviewMarksHistory");
        byte[] raw = JDOMUtil.writeElement(new Element("application").addContent(component)).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(legacySource.getParent());
        Files.write(legacySource, raw);
        loaded(store, path, SOURCE);
        drain(store);

        assertTrue(store.isShared());
        assertEquals(proof.id(), store.history(getProject(), path).evidence().getFirst().id());
        assertEquals("Imported history", versions(store, path).getFirst().author().name());
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        Path backup = ReviewHistoryStore.backupDirectory().resolve("history-" + digest + ".xml");
        assertTrue("The original XML must be preserved before publication", Files.isRegularFile(backup));
        assertTrue(Arrays.equals(raw, Files.readAllBytes(backup)));
        assertTrue("Migration must not rewrite its source XML", Arrays.equals(raw, Files.readAllBytes(legacySource)));
        var published = events(base());
        assertFalse(published.isEmpty());
        restartFacade();
        loaded(store, path, SOURCE);
        drain(store);
        assertEquals(published, events(base()));
        assertEquals(1, store.history(getProject(), path).evidence().size());
    }

    public void testSharedArtifactsAreExcludedFromTheNormalServiceFileBoundary() throws Exception {
        var source = myFixture.addFileToProject("src/user.py", "value = 1\n").getVirtualFile();
        Path metadataPath = base().resolve(".doreview/internal.py");
        Files.createDirectories(metadataPath.getParent());
        Files.writeString(metadataPath, "value = 1\n");
        var metadata = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(metadataPath.toFile());
        assertNotNull(metadata);
        Document ordinary = FileDocumentManager.getInstance().getDocument(source);
        Document internal = FileDocumentManager.getInstance().getDocument(metadata);
        assertNotNull(ordinary); assertNotNull(internal);
        assertTrue(service.supports(ordinary));
        assertFalse("Publishing review metadata must not recursively review that metadata", service.supports(internal));
        assertNull(new ReviewFileAccess(getProject()).relativePath(metadata.getPath()));
    }

    public void testLegacyPositiveLocatorAndAliasCannotSupersedeExistingSharedOriginDenial() throws Exception {
        initGit(base(), AUTHOR, EMAIL);
        String current = SOURCE + "suffix = 4\n";
        var file = myFixture.addFileToProject("renamed.py", current).getVirtualFile();
        String path = relative(file.getPath());
        String oldPath = relative(Path.of(file.getPath()).resolveSibling("original.py").toString());
        int start = SOURCE.indexOf("    first"), end = SOURCE.indexOf("    second");
        var legacy = new LegacyReviewHistoryModel(base().resolve("legacy-fixture-backups"));
        var proof = legacy.mark(getProject(), oldPath, SOURCE, start, end);
        assertNotNull(proof);
        assertTrue(legacy.movePath(getProject(), oldPath, path));
        var ref = new EvidenceRef(proof.id(), start, end);
        legacy.checkpoint(getProject(), path, current,
                new ProjectionHints(Map.of(ref, new ReviewedRange(start, end)), Set.of(ref)));
        var legacyState = legacy.getState();
        var projectState = legacyState.projects.values().iterator().next();
        assertTrue("The migration fixture must include its old historical alias", projectState.aliases.containsKey(oldPath));
        var denied = ReviewHistoryStateCopies.file(projectState.files.get(projectState.aliases.get(path)));
        var history = ReviewJournalCodec.read(denied);
        denied.checkpoints.put(FileSnapshot.create(current).id(), ProjectionCheckpointCodec.encode(history, current,
                new ProjectionHints(Map.of(), Set.of(ref))));
        var seeded = CompletableFuture.runAsync(() -> {
            try (var repository = new SharedHistoryRepository(base(), ReviewRepositoryPaths.databasePath(base()))) {
                repository.publish(null, path, null, denied, new SharedHistoryRepository.Author(AUTHOR, EMAIL));
            }
        });
        PlatformTestUtil.waitWithEventsDispatching("The existing shared denial was not persisted", seeded::isDone, 20);
        seeded.join();
        Element component = XmlSerializer.serialize(legacyState).setName("component").setAttribute("name", "ReviewMarksHistory");
        Files.createDirectories(legacySource.getParent());
        Files.writeString(legacySource, JDOMUtil.writeElement(new Element("application").addContent(component)), StandardCharsets.UTF_8);
        loaded(store, path, current);
        drain(store);
        var hints = store.checkpoint(getProject(), path, current);
        assertTrue("Independent imported locators must not restore an origin already denied by shared history",
                hints.authoritativeRefs().contains(ref));
        assertFalse(hints.livePositions().containsKey(ref));
        restartFacade();
        loaded(store, path, current);
        var restored = store.checkpoint(getProject(), path, current);
        assertTrue(restored.authoritativeRefs().contains(ref));
        assertFalse("Alias publication must preserve the merged denial after a cold reload", restored.livePositions().containsKey(ref));
        myFixture.configureFromExistingVirtualFile(file);
        service.initialize();
        fresh(myFixture.getEditor().getDocument());
        assertFalse(green(myFixture.getEditor().getDocument(), 1));
    }

    public void testGitBranchSwitchAtSameCommitAndMergeFollowCheckedOutArtifacts() throws Exception {
        initGit(base(), AUTHOR, EMAIL);
        var common = myFixture.addFileToProject("common.py", SOURCE).getVirtualFile();
        String commonPath = relative(common.getPath());
        FileDocumentManager.getInstance().saveAllDocuments();
        git(base(), "add", commonPath);
        git(base(), "commit", "-qm", "Initial source");
        loaded(store, commonPath, SOURCE);
        var commonProof = store.mark(getProject(), commonPath, SOURCE, 0, SOURCE.length());
        assertNotNull(commonProof);
        drain(store);
        git(base(), "add", ".doreview");
        git(base(), "commit", "-qm", "Common review");
        store.reloadShared(getProject());
        loaded(store, commonPath, SOURCE);

        String sharedCommit = git(base(), "rev-parse", "HEAD");
        git(base(), "checkout", "-qb", "feature");
        assertEquals("Changing only the branch name must exercise the same-commit checkout boundary",
                sharedCommit, git(base(), "rev-parse", "HEAD"));
        store.reloadShared(getProject());
        loaded(store, commonPath, SOURCE);
        var feature = myFixture.addFileToProject("feature.py", "feature = 1\n").getVirtualFile();
        String featurePath = relative(feature.getPath());
        FileDocumentManager.getInstance().saveAllDocuments();
        loaded(store, featurePath, "feature = 1\n");
        var featureProof = store.mark(getProject(), featurePath, "feature = 1\n", 0, 12);
        assertNotNull(featureProof);
        drain(store);
        git(base(), "add", featurePath, ".doreview");
        git(base(), "commit", "-qm", "Feature review");

        git(base(), "checkout", "-q", "main");
        store.reloadShared(getProject());
        loaded(store, commonPath, SOURCE);
        loaded(store, featurePath, "feature = 1\n");
        assertEquals(commonProof.id(), store.history(getProject(), commonPath).evidence().getFirst().id());
        assertTrue("A review existing only on feature must disappear on main",
                store.history(getProject(), featurePath).evidence().isEmpty());
        var main = myFixture.addFileToProject("main.py", "main = 2\n").getVirtualFile();
        String mainPath = relative(main.getPath());
        FileDocumentManager.getInstance().saveAllDocuments();
        loaded(store, mainPath, "main = 2\n");
        var mainProof = store.mark(getProject(), mainPath, "main = 2\n", 0, 9);
        assertNotNull(mainProof);
        drain(store);
        git(base(), "add", mainPath, ".doreview");
        git(base(), "commit", "-qm", "Main review");

        git(base(), "merge", "--no-edit", "feature");
        store.reloadShared(getProject());
        loaded(store, commonPath, SOURCE);
        loaded(store, featurePath, "feature = 1\n");
        loaded(store, mainPath, "main = 2\n");
        assertEquals(commonProof.id(), store.history(getProject(), commonPath).evidence().getFirst().id());
        assertEquals(featureProof.id(), store.history(getProject(), featurePath).evidence().getFirst().id());
        assertEquals(mainProof.id(), store.history(getProject(), mainPath).evidence().getFirst().id());
        assertEquals("Ordinary Git merge must combine immutable operations from the same reviewer",
                3, events(base()).size());
    }

    public void testRapidRenameThenSaveKeepsFileIdentityAndModifiedRowUnreviewed() throws Exception {
        initGit(base(), AUTHOR, EMAIL);
        var file = myFixture.addFileToProject("sample.py", SOURCE).getVirtualFile();
        myFixture.configureFromExistingVirtualFile(file);
        Document document = myFixture.getEditor().getDocument();
        service.initialize();
        fresh(document);
        service.mark(document, 0, document.getTextLength());
        fresh(document);
        drain(store);
        fresh(document);
        String proofId = service.history(document).evidence().getFirst().id();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            try { file.rename(this, "renamed.py"); }
            catch (java.io.IOException failure) { throw new RuntimeException(failure); }
            int offset = document.getText().indexOf("first + 2");
            document.replaceString(offset, offset + "first + 2".length(), "first + 3");
            FileDocumentManager.getInstance().saveDocument(document);
        });
        PlatformTestUtil.waitWithEventsDispatching("Rename lost the existing proof while save was queued",
                () -> service.isProjectionReady(document) && service.history(document).evidence().stream()
                        .anyMatch(proof -> proof.id().equals(proofId)), 20);
        drain(store);
        fresh(document);
        assertTrue(green(document, 0)); assertTrue(green(document, 1)); assertTrue(green(document, 3));
        assertFalse("Saving immediately after rename must not restore a changed row's review", green(document, 2));
        assertEquals(1, service.history(document).evidence().size());
        myFixture.configureByText("other.py", "pass\n");
        restartFacade();
        myFixture.configureFromExistingVirtualFile(file);
        Document reopened = myFixture.getEditor().getDocument();
        service.initialize();
        fresh(reopened);
        assertEquals(proofId, service.history(reopened).evidence().getFirst().id());
        assertTrue(green(reopened, 0)); assertTrue(green(reopened, 1)); assertTrue(green(reopened, 3));
        assertFalse(green(reopened, 2));
    }

    public void testEditingSavingAndReleasingAnUnreviewedFileCreatesNoSharedArtifacts() throws Exception {
        initGit(base(), AUTHOR, EMAIL);
        var file = myFixture.addFileToProject("unreviewed.py", SOURCE).getVirtualFile();
        myFixture.configureFromExistingVirtualFile(file);
        Document document = myFixture.getEditor().getDocument();
        service.initialize();
        fresh(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            int start = document.getText().indexOf("first + 2");
            document.replaceString(start, start + "first + 2".length(), "first + 3");
        });
        fresh(document);
        FileDocumentManager.getInstance().saveDocument(document);
        store.release(getProject(), relative(file.getPath()));
        drain(store);
        assertTrue(service.history(document).evidence().isEmpty());
        assertEquals(0, service.coverage(document).reviewed());
        assertFalse("Opening and editing ordinary source must not create a public review journal",
                Files.exists(base().resolve(".doreview")));
    }

    public void testOrphanPendingAfterGitResetDoesNotBlockReviewingAnUnrelatedFile() throws Exception {
        initGit(base(), AUTHOR, EMAIL);
        var original = myFixture.addFileToProject("original.py", SOURCE).getVirtualFile();
        String originalPath = relative(original.getPath());
        FileDocumentManager.getInstance().saveAllDocuments();
        git(base(), "add", originalPath);
        git(base(), "commit", "-qm", "Source before review");
        var detached = new LegacyReviewHistoryModel(base().resolve("legacy-fixture-backups"));
        var proof = detached.mark(getProject(), originalPath, SOURCE, 0, SOURCE.length());
        var detachedProject = detached.getState().projects.values().iterator().next();
        var state = ReviewHistoryStateCopies.file(detachedProject.files.get(detachedProject.aliases.get(originalPath)));
        var published = CompletableFuture.runAsync(() -> {
            try (var repository = new SharedHistoryRepository(base(), ReviewRepositoryPaths.databasePath(base()))) {
                repository.publish(null, originalPath, null, state, new SharedHistoryRepository.Author(AUTHOR, EMAIL));
            }
        });
        await(published, "The parent review was not published");
        git(base(), "add", ".doreview");
        git(base(), "commit", "-qm", "Review that will leave this checkout");
        var pendingSaved = CompletableFuture.runAsync(() -> {
            try (var repository = new SharedHistoryRepository(base(), ReviewRepositoryPaths.databasePath(base()))) {
                var before = repository.load(originalPath);
                assertNotNull(before);
                var pending = ReviewHistoryStateCopies.file(before.state());
                var ref = new EvidenceRef(proof.id(), 0, SOURCE.indexOf('\n') + 1);
                pending.pendingCheckpoint = ProjectionCheckpointCodec.encode(ReviewJournalCodec.read(pending), SOURCE,
                        new ProjectionHints(Map.of(), Set.of(ref)));
                repository.savePending(before.fileId(), originalPath, pending,
                        new SharedHistoryRepository.Author(AUTHOR, EMAIL), repository.checkoutToken(), before.frontier());
            }
        });
        await(pendingSaved, "The unpublished provenance was not made durable");
        git(base(), "reset", "--hard", "HEAD^");
        assertTrue(events(base()).isEmpty());
        var unrelated = myFixture.addFileToProject("unrelated.py", "unrelated = 2\n").getVirtualFile();
        String path = relative(unrelated.getPath());
        var ready = new AtomicBoolean();
        store.whenLoaded(getProject(), path, "unrelated = 2\n", () -> ready.set(true));
        PlatformTestUtil.waitWithEventsDispatching("An orphan pending row blocked an unrelated file's initial load", ready::get, 20);
        assertTrue(store.isLoaded(getProject(), path));
        assertNotNull(store.mark(getProject(), path, "unrelated = 2\n", 0, 14));
        await(store.storageIdle(), "The unrelated review could not be published");
        assertFalse(events(base()).isEmpty());
        assertTrue("Retained work must be reported without blocking ordinary review commands",
                store.diagnostics(getProject()).stream().anyMatch(message -> message.contains("retained locally") && message.contains("dependencies")));
        Disposer.dispose(service);
        close(store);
        var retained = CompletableFuture.supplyAsync(() -> {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + ReviewRepositoryPaths.databasePath(base()));
                 var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM pending")) {
                rows.next(); return rows.getInt(1);
            } catch (java.sql.SQLException failure) { throw new RuntimeException(failure); }
        });
        await(retained, "The retained pending journal could not be inspected");
        assertEquals("The orphan must survive facade shutdown for later recovery", 1, retained.join().intValue());
    }

    public void testIncompleteLegacyMigrationRetriesAfterItsSourceIsRepaired() throws Exception {
        var file = myFixture.addFileToProject("legacy-retry.py", SOURCE).getVirtualFile();
        String path = relative(file.getPath());
        Path legacyProject = base().resolve(".idea/review-marks.xml");
        Files.createDirectories(legacyProject); // An unreadable source kind, independent of OS permissions.
        Path complete = ReviewRepositoryPaths.databasePath(base()).getParent().resolve("migration-"
                + ReviewRepositoryPaths.sha256(ReviewRepositoryPaths.canonicalRoot(legacySource) + "\n"
                + LegacyReviewHistoryModel.projectKey(getProject())) + ".complete");
        store.whenLoaded(getProject(), path, SOURCE, null);
        await(store.storageIdle(), "The failed legacy migration did not finish");

        assertFalse("A failed import must never be recorded as successful", Files.exists(complete));
        assertTrue("The failed source must be reported", store.diagnostics(getProject()).stream()
                .anyMatch(message -> message.toLowerCase(java.util.Locale.ROOT).contains("legacy")));
        assertTrue("The original unreadable source must remain intact", Files.isDirectory(legacyProject));

        var legacy = new ReviewHistoryStore.LegacyState();
        var saved = new ReviewHistoryStore.LegacyFileState();
        saved.snapshot = SOURCE;
        saved.ranges = List.of(new ReviewHistoryStore.RangeState(0, SOURCE.length()));
        legacy.files.put(path, saved);
        Element component = XmlSerializer.serialize(legacy).setName("component").setAttribute("name", "ReviewMarks");
        Files.delete(legacyProject);
        Files.writeString(legacyProject, JDOMUtil.writeElement(new Element("project").addContent(component)), StandardCharsets.UTF_8);
        Disposer.dispose(service);
        await(store.storageIdle(), "The failed facade did not finish its queued work");
        close(store);
        installFreshFacade();
        loaded(store, path, SOURCE);
        drain(store);

        assertTrue("A repaired source must be imported on the next facade start", Files.isRegularFile(complete));
        var history = store.history(getProject(), path);
        assertEquals(1, history.evidence().size());
        assertEquals(SOURCE, history.snapshots().get(history.evidence().getFirst().snapshotId()).text());
        assertEquals("Imported history", versions(store, path).getFirst().author().name());
    }

    public void testOwnPublicationPreservesFrozenClearOfOlderReviewedVariants() throws Exception {
        String older = SOURCE + "\n# older version\n";
        String current = SOURCE + "\n# current version\n";
        var file = myFixture.addFileToProject("frozen-clear.py", current).getVirtualFile();
        myFixture.configureFromExistingVirtualFile(file);
        Document document = myFixture.getEditor().getDocument();
        String path = relative(file.getPath());
        service.initialize();
        loaded(store, path, current);
        int start = document.getLineStartOffset(1), end = document.getLineStartOffset(2);
        var oldProof = store.mark(getProject(), path, older, start, end);
        var currentProof = store.mark(getProject(), path, current, start, end);
        assertNotNull(oldProof); assertNotNull(currentProof);
        drain(store); fresh(document);
        assertTrue(green(document, 1));

        var release = new CountDownLatch(1);
        var captureStarted = new AtomicBoolean();
        ContextProvider provider = new ContextProvider() {
            @Override public String id() { return "test:shared-frozen-clear-v1"; }
            @Override public boolean supports(PsiFile candidate) { return candidate.getName().equals("frozen-clear.py"); }
            @Override public ContextMap analyze(PsiFile candidate, String text) {
                captureStarted.set(true);
                try {
                    while (!release.await(20, TimeUnit.MILLISECONDS)) ProgressManager.checkCanceled();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    ProgressManager.checkCanceled();
                    throw new IllegalStateException(interrupted);
                }
                return new ContextMap(id(), Collections.nCopies(1 + (int) text.chars().filter(value -> value == '\n').count(), "same-context"));
            }
        };
        try {
            ContextProvider.EP.getPoint().registerExtension(provider, LoadingOrder.FIRST, getTestRootDisposable());
            PlatformTestUtil.waitWithEventsDispatching("The historical capture did not reach its barrier",
                    () -> { service.projection(document); return captureStarted.get() && !service.isProjectionReady(document); }, 20);
            service.clear(document, start, end);
            var pendingField = DoReviewService.class.getDeclaredField("pendingRevocations");
            pendingField.setAccessible(true);
            assertEquals(1, pendingField.getInt(service));

            // The exact current proof is persisted while the independent expansion is frozen.
            await(store.storageIdle(), "The local fast revocation was not persisted");
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            assertEquals("Publishing our own exact revocation must not cancel the older-variant command",
                    1, pendingField.getInt(service));
            // A normal IDE refresh also discovers the files our background publisher wrote.
            var metadata = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(base().resolve(".doreview").toFile());
            assertNotNull(metadata);
            metadata.refresh(false, true);
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            assertEquals("Discovering our already indexed metadata must preserve the frozen clear command",
                    1, pendingField.getInt(service));
            release.countDown();
            fresh(document); drain(store); fresh(document);
            var history = service.history(document);
            for (String id : List.of(oldProof.id(), currentProof.id())) {
                assertTrue("Every observed variant must be revoked: " + id, history.revocations().stream()
                        .filter(revocation -> revocation.evidenceId().equals(id)).flatMap(revocation -> revocation.ranges().stream())
                        .anyMatch(range -> range.start() <= start && range.end() >= end));
            }
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(older));
            fresh(document);
            assertFalse("Returning to the older source must not restore a cleared review", green(document, 1));
        } finally { release.countDown(); }
    }

    private void installFreshFacade() {
        store = new ReviewHistoryStore();
        ownedStores.add(store);
        ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), ReviewHistoryStore.class, store, getTestRootDisposable());
        service = new DoReviewService(getProject());
        ServiceContainerUtil.replaceService(getProject(), DoReviewService.class, service, getTestRootDisposable());
    }

    private void restartFacade() {
        Disposer.dispose(service);
        drain(store);
        close(store);
        installFreshFacade();
    }

    private void close(ReviewHistoryStore value) {
        if (closedStores.add(value)) value.dispose();
        PlatformTestUtil.waitWithEventsDispatching("SQLite worker did not finish closing", () -> value.storageClosed().isDone(), 20);
        value.storageClosed().join();
    }

    private void await(CompletableFuture<?> operation, String message) {
        PlatformTestUtil.waitWithEventsDispatching(message, operation::isDone, 20);
        operation.join();
    }

    private void loaded(ReviewHistoryStore target, String path, String text) {
        var completed = new AtomicBoolean();
        var onEdt = new AtomicBoolean();
        target.whenLoaded(getProject(), path, text, () -> {
            onEdt.set(ApplicationManager.getApplication().isDispatchThread());
            completed.set(true);
        });
        PlatformTestUtil.waitWithEventsDispatching("Production history did not load: " + path,
                completed::get, 20);
        assertTrue("Asynchronous load callbacks must return to EDT", onEdt.get());
        assertTrue(target.isLoaded(getProject(), path));
        assertTrue(target.diagnostics(getProject()).toString(), target.diagnostics(getProject()).isEmpty());
    }

    private void drain(ReviewHistoryStore target) {
        var completed = new AtomicBoolean();
        target.flushShared(getProject());
        target.queryPaths(getProject(), ignored -> completed.set(true));
        PlatformTestUtil.waitWithEventsDispatching("Queued production persistence did not complete", completed::get, 20);
        assertTrue(target.diagnostics(getProject()).toString(), target.diagnostics(getProject()).isEmpty());
    }

    private List<SharedHistoryRepository.Version> versions(ReviewHistoryStore target, String path) {
        var result = new AtomicReference<List<SharedHistoryRepository.Version>>();
        target.queryVersions(getProject(), path, result::set);
        PlatformTestUtil.waitWithEventsDispatching("Persisted author query did not complete", () -> result.get() != null, 20);
        return result.get();
    }

    private void fresh(Document document) {
        PlatformTestUtil.waitWithEventsDispatching("Production projection did not become ready", () -> service.isProjectionReady(document), 20);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
    }

    private boolean green(Document document, int row) {
        return service.getRanges(document).stream().anyMatch(range -> range.start() <= document.getLineStartOffset(row)
                && range.end() >= document.getLineEndOffset(row));
    }

    private void assertSQLite(Path root) throws Exception {
        Path database = ReviewRepositoryPaths.databasePath(root);
        Path isolated = Path.of(System.getProperty("doreview.data.path")).toAbsolutePath().normalize();
        assertTrue("The database must stay in the configured test data directory", database.startsWith(isolated));
        assertFalse("SQLite must not become a tracked worktree artifact", database.startsWith(root));
        assertTrue(Files.isRegularFile(database));
        try (var stream = Files.newInputStream(database)) {
            assertEquals("SQLite format 3\0", new String(stream.readNBytes(16), StandardCharsets.US_ASCII));
        }
        var engine = new AtomicReference<String>();
        var failure = new AtomicReference<Throwable>();
        var completed = new AtomicBoolean();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT sqlite_version()")) {
                if (rows.next()) engine.set(rows.getString(1));
            } catch (Throwable error) { failure.set(error); }
            finally { completed.set(true); }
        });
        PlatformTestUtil.waitWithEventsDispatching("The persisted SQLite database could not be reopened", completed::get, 20);
        if (failure.get() != null) throw new AssertionError("The real JDBC driver could not read its database", failure.get());
        assertEquals("Use the pinned native engine, including its WAL-reset corruption fix", "3.53.4", engine.get());
    }

    private static List<Path> events(Path root) throws Exception {
        Path directory = root.resolve(".doreview/v1/events");
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.walk(directory)) { return files.filter(path -> path.toString().endsWith(".json")).sorted().toList(); }
    }

    private Path base() { return Path.of(getProject().getBasePath()).toAbsolutePath().normalize(); }
    private String relative(String file) { return base().relativize(Path.of(file)).toString().replace('\\', '/'); }

    private void initGit(Path directory, String author, String email) throws Exception {
        Files.createDirectories(directory);
        Path template = Files.createDirectories(base().resolve("empty-git-template"));
        git(directory, "init", "-q", "-b", "main", "--template=" + template);
        git(directory, "config", "user.name", author);
        git(directory, "config", "user.email", email);
    }

    private String git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.autocrlf=false", "-c", "core.longpaths=true",
                "-c", "commit.gpgsign=false", "-c", "core.hooksPath=" + base().resolve("no-test-hooks")));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor());
        return output.strip();
    }

    private void writableGitObjects() throws Exception {
        if (myFixture == null || !Files.isDirectory(base())) return;
        Path root = base();
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(path -> path.toString().contains(java.io.File.separator + ".git" + java.io.File.separator)).toList()) {
                assertTrue("Fixture cleanup must stay below its own project", path.normalize().startsWith(root));
                var attributes = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes != null) attributes.setReadOnly(false);
            }
        }
    }
}
