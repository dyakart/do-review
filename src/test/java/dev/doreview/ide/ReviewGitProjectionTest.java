package dev.doreview.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.extensions.LoadingOrder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import dev.doreview.core.ContextMap;
import dev.doreview.core.EvidenceRef;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;
import dev.doreview.ide.context.ContextProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Physical Git projects exercise capture and background projection without a syntax provider. */
public final class ReviewGitProjectionTest extends BasePlatformTestCase {
    private final AtomicInteger syntaxCalls = new AtomicInteger();
    private boolean probeActive;
    private String previousLegacySource;
    private Path legacyDirectory;

    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture project = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(project, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(project.getProject().getBasePath()); }
        });
    }

    @Override protected void setUp() throws Exception {
        previousLegacySource = System.getProperty("doreview.legacy.xml.path");
        legacyDirectory = Files.createTempDirectory("doreview-git-projection-legacy-");
        System.setProperty("doreview.legacy.xml.path", legacyDirectory.resolve("absent.xml").toString());
        super.setUp();
        initGit();
        probeActive = true;
        ContextProvider.EP.getPoint().registerExtension(new ContextProvider() {
            @Override public String id() { return "git-projection-must-not-use-psi"; }
            @Override public boolean supports(PsiFile file) {
                if (!probeActive) return false;
                syntaxCalls.incrementAndGet();
                throw new AssertionError("Git projection must not select a PSI context provider");
            }
            @Override public ContextMap analyze(PsiFile file, String text) {
                syntaxCalls.incrementAndGet();
                throw new AssertionError("Git projection must not analyze syntax");
            }
        }, LoadingOrder.FIRST, getTestRootDisposable());
    }

    @Override protected void tearDown() throws Exception {
        probeActive = false;
        String restoreLegacySource = previousLegacySource;
        Path cleanupDirectory = legacyDirectory;
        try { super.tearDown(); }
        finally {
            if (restoreLegacySource == null) System.clearProperty("doreview.legacy.xml.path");
            else System.setProperty("doreview.legacy.xml.path", restoreLegacySource);
            Files.deleteIfExists(cleanupDirectory);
        }
    }

    public void testFunctionHeaderChangesDoNotInvalidateUnchangedPythonBody() {
        String reviewed = "def compute(value):\n    first = value + 1\n    return first\n";
        String current = reviewed.replace("compute(value)", "compute(value, other)");
        var file = physicalFile("sample.py", current);
        var result = compute(reviewed(reviewed), file, current, ProjectionHints.empty());

        assertEquals(FileProjection.Status.CHANGED, result.projection().rows().get(0).status());
        assertEquals(FileProjection.Status.REVIEWED, result.projection().rows().get(1).status());
        assertEquals(FileProjection.Status.REVIEWED, result.projection().rows().get(2).status());
        assertGitOnly(result);
    }

    public void testUnsupportedPlainTextKeepsReviewedRowsOutsideGitHunks() {
        String reviewed = "first=old\nreviewed=value\nlast=stable\n";
        String current = reviewed.replace("first=old", "first=new");
        var file = physicalFile("settings.unknown-language", current);
        var result = compute(reviewed(reviewed), file, current, ProjectionHints.empty());

        assertEquals(FileProjection.Status.CHANGED, result.projection().rows().get(0).status());
        assertEquals(FileProjection.Status.REVIEWED, result.projection().rows().get(1).status());
        assertEquals(FileProjection.Status.REVIEWED, result.projection().rows().get(2).status());
        assertGitOnly(result);
    }

    public void testOldAmbiguousCheckpointDenialsDoNotOverrideGitCorrespondence() {
        String reviewed = "prefix\nkeep\nsuffix\n", current = "changed\nkeep\nsuffix\n";
        var history = reviewed(reviewed);
        var file = physicalFile("denials.txt", current);
        int oldStart = reviewed.indexOf("keep"), currentStart = current.indexOf("keep");
        var ref = new EvidenceRef(history.evidence().getFirst().id(), oldStart, oldStart + "keep\n".length());
        var stale = new ProjectionHints(Map.of(), Set.of(ref), Set.of(), Map.of(), Map.of(ref, "keep\n"),
                Map.of(ref, new ProjectionHints.UnresolvedPosition(
                        new ReviewedRange(currentStart, currentStart + "keep\n".length()), FileProjection.Reason.AMBIGUOUS)));

        var result = compute(history, file, current, stale);
        var withoutOldHints = compute(history, file, current, ProjectionHints.empty());

        assertEquals(withoutOldHints.projection(), result.projection());
        assertEquals(FileProjection.Status.REVIEWED, result.projection().rows().get(1).status());
        assertGitOnly(result);
    }

    public void testUnsavedDocumentTextIsComparedAndExplicitRevocationRemainsEffective() throws Exception {
        String reviewed = "def sample():\n    keep = 1\n    return keep\n";
        String current = reviewed.replace("sample()", "sample(flag)") + "    added = flag\n";
        var file = physicalFile("unsaved.py", reviewed);
        var document = myFixture.getEditor().getDocument();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(current));
        assertEquals(reviewed, Files.readString(file.toNioPath(), StandardCharsets.UTF_8));
        var history = reviewed(reviewed);
        int start = reviewed.indexOf("    keep = 1");
        var cleared = new EvidenceRef(history.evidence().getFirst().id(), start, start + "    keep = 1\n".length());
        history = ReviewHistoryEngine.revoke(history, List.of(cleared));

        var result = compute(history, file, document.getText(), ProjectionHints.empty());

        assertEquals(FileProjection.Status.CHANGED, result.projection().rows().get(0).status());
        assertEquals(FileProjection.Status.UNREVIEWED, result.projection().rows().get(1).status());
        assertEquals(FileProjection.Status.REVIEWED, result.projection().rows().get(2).status());
        assertEquals(FileProjection.Status.UNREVIEWED, result.projection().rows().get(3).status());
        assertEquals(reviewed, Files.readString(file.toNioPath(), StandardCharsets.UTF_8));
        assertGitOnly(result);
    }

    private VirtualFile physicalFile(String name, String text) {
        var file = myFixture.addFileToProject(name, text).getVirtualFile();
        myFixture.configureFromExistingVirtualFile(file);
        assertTrue(file.isInLocalFileSystem());
        return file;
    }

    private ReviewProjectionComputer.Result compute(ReviewHistory history, VirtualFile file,
                                                    String text, ProjectionHints hints) {
        var computer = new ReviewProjectionComputer(getProject());
        var contexts = ReadAction.computeBlocking(() -> computer.capture(history, file, text));
        assertTrue("Physical repository files must select Git capture", contexts.gitBased());
        assertEquals(ReviewRepositoryPaths.canonicalRoot(Path.of(getProject().getBasePath())), contexts.gitRoot());
        assertEquals(ContextMap.textOnly().provider(), contexts.current().provider());
        var future = CompletableFuture.supplyAsync(() -> {
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed());
            var result = computer.computeResult(history, text, hints, contexts);
            assertEquals(result.projection(), computer.project(history, text, hints, contexts));
            return result;
        });
        PlatformTestUtil.waitWithEventsDispatching("Git projection did not finish outside the read action", future::isDone, 15);
        return future.join();
    }

    private static ReviewHistory reviewed(String text) {
        return ReviewHistoryEngine.review(ReviewHistory.empty(), text, List.of(new ReviewedRange(0, text.length())), 1);
    }

    private void assertGitOnly(ReviewProjectionComputer.Result result) {
        assertTrue(result.projection().yellowRanges().isEmpty());
        assertFalse(result.projection().rows().stream().anyMatch(row -> row.status() == FileProjection.Status.NEEDS_REVIEW));
        assertEquals(ProjectionHints.empty(), result.hints());
        assertEquals(ProjectionHints.empty(), result.checkpoint());
        assertEquals("Git capture must never call PSI providers", 0, syntaxCalls.get());
    }

    private void initGit() throws Exception {
        var process = new ProcessBuilder("git", "-c", "core.hooksPath=", "-c", "core.longpaths=true", "-C", getProject().getBasePath(),
                "init", "--initial-branch=main").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Git fixture initialization timed out");
        }
        assertEquals(output, 0, process.exitValue());
    }
}
