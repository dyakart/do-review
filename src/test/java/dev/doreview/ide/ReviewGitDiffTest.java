package dev.doreview.ide;

import dev.doreview.core.ContextMap;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ProjectionWorkBudget;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewProjectionWorkspace;
import dev.doreview.core.ReviewedRange;
import dev.doreview.core.SnapshotReconciler;
import junit.framework.TestCase;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual Git hunks determine correspondence; only test-owned repositories are configured. */
public final class ReviewGitDiffTest extends TestCase {
    private Path temporary;
    private Path root;

    @Override protected void setUp() throws Exception {
        temporary = Files.createTempDirectory("doreview-git-diff-test-");
        root = Files.createDirectories(temporary.resolve("repo"));
        git("init", "--initial-branch=main");
        git("config", "diff.indentHeuristic", "true");
    }

    @Override protected void tearDown() throws Exception {
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public void testAddedDeletedAndChangedRowsFollowActualZeroContextHunks() throws Exception {
        String old = "head\nold\nretained\ndeleted\ntail\n";
        String current = "inserted\nhead\nnew\nretained\ntail\nappended\n";
        String patch = actualDiff(old, current);
        assertTrue(patch, patch.contains("@@ -0,0 +1 @@"));
        assertTrue(patch, patch.contains("@@ -2 +3 @@"));
        assertTrue(patch, patch.contains("@@ -4 +4,0 @@"));
        assertTrue(patch, patch.contains("@@ -5,0 +6 @@"));

        var mapping = map(old, current);

        assertEquals(List.of(1, -1, 3, -1, 4), mapping.exactLines());
        assertEquals(List.of(change(1, 2, 2, 3), deleted(3, 4, 4)), mapping.changes());
        assertEquals("", git("status", "--porcelain"));
    }

    public void testRepeatedRowsUseGitAlignmentWithoutReviewingTheInsertedCopy() throws Exception {
        String old = "head\nrepeat\nrepeat\ntail\n";
        String current = "head\nrepeat\nrepeat\nrepeat\ntail\n";
        String patch = actualDiff(old, current);
        assertTrue(patch, patch.contains("@@ -3,0 +4 @@"));

        var mapping = map(old, current);

        assertEquals(List.of(0, 1, 2, 4), mapping.exactLines());
        assertFalse(mapping.exactLines().contains(3));
        assertTrue(mapping.changes().isEmpty());
    }

    public void testMultilineReplacementDoesNotMatchEqualRowsInsideGitHunks() throws Exception {
        var mapping = map("before\nold one\nold two\nafter\n", "before\nnew\nafter\n");

        assertEquals(List.of(0, -1, -1, 2), mapping.exactLines());
        assertEquals(List.of(change(1, 3, 1, 2)), mapping.changes());
    }

    public void testEofLineTerminationWhitespaceAndEmptySnapshotsStayExact() throws Exception {
        assertEquals(List.of(-1), map("last", "last\n").exactLines());
        assertEquals(List.of(-1), map("last\n", "last").exactLines());
        assertEquals(List.of(-1), map("value \n", "value\n").exactLines());
        assertEquals(List.of(0, 1), map("\tvalue\n日本語", "\tvalue\n日本語").exactLines());
        assertTrue(map("", "added\n").exactLines().isEmpty());
        assertEquals(List.of(-1), map("removed\n", "").exactLines());
        assertEquals(List.of(deleted(0, 1, 0)), map("removed\n", "").changes());
        assertTrue(map("", "").exactLines().isEmpty());
    }

    public void testCrLfSnapshotsUseTheSameNormalizedRowsAsTheReviewModel() throws Exception {
        var mapping = map("one\r\ntwo\r\nthree\r", "one\nTWO\nthree\n");

        assertEquals(List.of(0, -1, 2), mapping.exactLines());
        assertEquals(List.of(change(1, 2, 1, 2)), mapping.changes());
    }

    public void testRepositoryAlgorithmChangesAreVisibleToTheSameMapperInstance() throws Exception {
        String old = "B\nA\nB\nB\nA\nB\nA\nD\nC\nC\n";
        String current = "D\nB\nC\nB\nB\nC\nB\nC\nC\nB\n";
        var diff = new ReviewGitDiff();
        git("config", "diff.algorithm", "myers");
        String myersPatch = actualDiff(old, current);
        var myers = diff.map(root, old, current, () -> { });
        assertEquals(List.of(1, -1, 3, 4, -1, 6, -1, -1, 7, 8), myers.exactLines());
        git("config", "diff.algorithm", "patience");
        String patiencePatch = actualDiff(old, current);
        var patience = diff.map(root, old, current, () -> { });

        assertFalse(myersPatch.equals(patiencePatch));
        assertEquals(List.of(-1, -1, -1, -1, -1, -1, -1, 0, 2, 5), patience.exactLines());
    }

    public void testWorkspaceUsesGitOnlyOncePerSnapshotAndPreservesExplicitRevocation() throws Exception {
        String old = "kept\nchanged\n", current = "kept\nnew\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), old, List.of(new ReviewedRange(0, old.length())), 1);
        var calls = new AtomicInteger();
        try (var gitMapping = new ReviewGitDiff().mapper(root, current, () -> { })) {
            var workspace = new ReviewProjectionWorkspace(current, ProjectionWorkBudget.defaults(), () -> { }, snapshot -> {
                calls.incrementAndGet();
                return gitMapping.apply(snapshot);
            });
            var context = new ContextMap("git:text", List.of("text", "text"));
            var contexts = Map.of(history.evidence().getFirst().snapshotId(), context);
            var projection = ReviewHistoryEngine.project(history, contexts, context, ProjectionHints.empty(), workspace);
            assertEquals(FileProjection.Status.REVIEWED, projection.rows().getFirst().status());
            assertEquals(FileProjection.Status.CHANGED, projection.rows().get(1).status());
            assertEquals(projection, ReviewHistoryEngine.project(history, contexts, context, ProjectionHints.empty(), workspace));
            assertEquals(1, calls.get());

            var revoked = ReviewHistoryEngine.revoke(history, projection.rows().getFirst().revocableRefs());
            var after = ReviewHistoryEngine.project(revoked, contexts, context, ProjectionHints.empty(), workspace);
            assertEquals(FileProjection.Status.UNREVIEWED, after.rows().getFirst().status());
            assertEquals(1, calls.get());
        }
    }

    public void testSessionMapsDifferentBaselinesAndCannotOutliveItsOwner() throws Exception {
        var session = new ReviewGitDiff().mapper(root, "kept\ncurrent\n", () -> { });
        try (session) {
            assertEquals(List.of(0, -1, -1),
                    session.apply(dev.doreview.core.FileSnapshot.create("kept\nold\nold tail\n")).exactLines());
            assertEquals(List.of(0),
                    session.apply(dev.doreview.core.FileSnapshot.create("kept\n")).exactLines());
        }
        session.close();
        try {
            session.apply(dev.doreview.core.FileSnapshot.create("kept\n"));
            fail("A disposed projection must not start another Git process");
        } catch (IllegalStateException expected) { }
    }

    public void testCancelledSessionCannotResumeWithItsEarlierResults() throws Exception {
        var cancelled = new AtomicBoolean();
        var signal = new IllegalStateException("cancel session");
        try (var session = new ReviewGitDiff().mapper(root, "kept\ncurrent\n", () -> {
            if (cancelled.get()) throw signal;
        })) {
            assertEquals(List.of(0, -1),
                    session.apply(dev.doreview.core.FileSnapshot.create("kept\nold\n")).exactLines());
            cancelled.set(true);
            try {
                session.apply(dev.doreview.core.FileSnapshot.create("kept\nold\n"));
                fail("Cancellation must propagate");
            } catch (IllegalStateException expected) { assertSame(signal, expected); }
            cancelled.set(false);
            try {
                session.apply(dev.doreview.core.FileSnapshot.create("kept\nold\n"));
                fail("Cancelled projection must not reuse temporary inputs");
            } catch (IllegalStateException expected) { assertNotSame(signal, expected); }
        }
    }

    public void testLimitsMissingExecutableAndSharedOperationDeadlineFailClosed() throws Exception {
        var tiny = new ReviewGitDiff("git", new ReviewGitDiff.Limits(2, 4096, 3000));
        assertEquals(List.of(0, 1), tiny.map(root, "\r\n\r\n", "\r\n\r\n", () -> { }).exactLines());
        expectIo(() -> tiny.map(root, "aaaaa", "", () -> { }));
        expectIo(() -> tiny.map(root, "", "aaaaa", () -> { }));
        expectIo(() -> tiny.map(root, "éé", "", () -> { }));
        expectIo(() -> new ReviewGitDiff("git", new ReviewGitDiff.Limits(1, 4096, 3000))
                .map(root, "large\n", "new\n", () -> { }));
        expectIo(() -> new ReviewGitDiff("git", new ReviewGitDiff.Limits(1024, 1, 3000))
                .map(root, "old\n", "new\n", () -> { }));
        expectIo(() -> new ReviewGitDiff("doreview-no-such-git", ReviewGitDiff.Limits.defaults())
                .map(root, "old\n", "new\n", () -> { }));
        try (var expired = new ReviewGitDiff("git", new ReviewGitDiff.Limits(1024, 4096, 0))
                .mapper(root, "current\n", () -> { })) {
            try {
                expired.apply(dev.doreview.core.FileSnapshot.create("old\n"));
                fail("Expired operation must not return a heuristic mapping");
            } catch (UncheckedIOException expected) { }
        }
    }

    public void testCancellationAfterStartingGitAndThreadInterruptionRemainVisible() throws Exception {
        var checks = new AtomicInteger();
        var cancelled = new IllegalStateException("cancel test");
        try {
            new ReviewGitDiff().map(root, "old\n", "new\n", () -> {
                if (checks.incrementAndGet() == 4) throw cancelled;
            });
            fail("Cancellation must propagate");
        } catch (IllegalStateException expected) { assertSame(cancelled, expected); }
        assertEquals(4, checks.get());
        Thread.currentThread().interrupt();
        try {
            map("old\n", "new\n");
            fail("Interrupted mapping must not complete");
        } catch (InterruptedIOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        var rejected = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            try { map("old\n", "new\n"); }
            catch (IllegalStateException expected) { rejected.set(true); }
            catch (Exception unexpected) { throw new AssertionError(unexpected); }
        });
        assertTrue(rejected.get());
    }

    public void testInheritedGitEnvironmentCannotRedirectOrConfigureTheDiff() throws Exception {
        var entries = new LinkedHashSet<String>();
        for (Class<?> type : List.of(ReviewGitDiffTest.class, ReviewGitDiff.class, ReviewHistory.class, TestCase.class)) {
            entries.add(classpathEntry(type).toString());
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) java = java.resolveSibling("java.exe");
        var builder = new ProcessBuilder(java.toString(), "-Djava.awt.headless=true", "-cp",
                String.join(File.pathSeparator, entries), ReviewGitDiffTest.class.getName(), root.toString());
        var environment = builder.environment();
        environment.put("GIT_DIR", temporary.resolve("missing-git-dir").toString());
        environment.put("GIT_WORK_TREE", temporary.resolve("unrelated-worktree").toString());
        environment.put("GIT_CONFIG_COUNT", "1");
        environment.put("GIT_CONFIG_KEY_0", "diff.algorithm");
        environment.put("GIT_CONFIG_VALUE_0", "doreview-invalid-algorithm");
        Path trace = temporary.resolve("unwanted-git-trace");
        environment.put("GIT_TRACE", trace.toString());
        var process = builder.redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Child diff test timed out");
        }
        assertEquals(output, 0, process.exitValue());
        assertTrue(output, output.contains("[0, -1]"));
        assertFalse(Files.exists(trace));
    }

    private static Path classpathEntry(Class<?> type) throws Exception {
        // IntelliJ's plugin classloader can omit ProtectionDomain.codeSource. Class
        // resources still identify the actual directory or jar used by that loader.
        String name = type.getName().replace('.', '/') + ".class";
        var resource = type.getResource("/" + name);
        assertNotNull("Missing class resource for " + type.getName(), resource);
        if (resource.getProtocol().equals("jar")) {
            // IntelliJ also supplies its own jar URLConnection implementation.
            String location = resource.toExternalForm();
            int entry = location.lastIndexOf("!/");
            assertTrue("Missing jar entry separator: " + resource, entry > "jar:".length());
            return Path.of(URI.create(location.substring("jar:".length(), entry)));
        }
        if (resource.getProtocol().equals("file")) {
            Path entry = Path.of(resource.toURI());
            for (String ignored : name.split("/")) entry = entry.getParent();
            return entry;
        }
        throw new AssertionError("Unsupported class resource: " + resource);
    }

    /** A separate JVM gives this fixture real inherited environment variables. */
    public static void main(String[] arguments) throws Exception {
        var mapping = new ReviewGitDiff().map(Path.of(arguments[0]), "kept\nold\n", "kept\nnew\n", () -> { });
        System.out.println(mapping.exactLines());
    }

    private SnapshotReconciler.Mapping map(String old, String current) throws IOException {
        return new ReviewGitDiff().map(root, old, current, () -> { });
    }

    private static SnapshotReconciler.Change change(int oldStart, int oldEnd, int newStart, int newEnd) {
        return new SnapshotReconciler.Change(oldStart, oldEnd, newStart, newEnd, SnapshotReconciler.ChangeReason.TEXT_CHANGED);
    }

    private static SnapshotReconciler.Change deleted(int oldStart, int oldEnd, int anchor) {
        return new SnapshotReconciler.Change(oldStart, oldEnd, anchor, anchor, SnapshotReconciler.ChangeReason.DELETED);
    }

    private String actualDiff(String old, String current) throws Exception {
        Path before = Files.writeString(temporary.resolve("oracle-before.txt"), old, StandardCharsets.UTF_8);
        Path after = Files.writeString(temporary.resolve("oracle-after.txt"), current, StandardCharsets.UTF_8);
        return git("diff", "--no-index", "--no-ext-diff", "--no-textconv", "--no-color", "--text", "--no-renames",
                "--unified=0", "--inter-hunk-context=0", "--", before.toString(), after.toString());
    }

    private String git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=", "-C", root.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Git fixture timed out");
        }
        boolean diff = arguments.length > 0 && arguments[0].equals("diff");
        assertTrue(command + "\n" + output, process.exitValue() == 0 || diff && process.exitValue() == 1);
        return output;
    }

    private static void expectIo(IoOperation operation) throws Exception {
        try { operation.run(); fail("Expected unavailable Git mapping"); }
        catch (IOException expected) { }
    }

    @FunctionalInterface private interface IoOperation { void run() throws Exception; }
}
