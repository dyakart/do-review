package dev.doreview.ide;

import dev.doreview.core.FileSnapshot;
import junit.framework.TestCase;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real local Git objects establish labels; all repositories live in a test-owned directory. */
public final class ReviewGitHistoryTest extends TestCase {
    private Path temporary;
    private Path root;

    @Override protected void setUp() throws Exception {
        temporary = Files.createTempDirectory("doreview-git-label-");
        root = Files.createDirectories(temporary.resolve("repo"));
        initialize(root);
    }

    @Override protected void tearDown() throws Exception {
        try (var files = Files.walk(temporary)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                assertTrue(path.toAbsolutePath().normalize().startsWith(temporary.toAbsolutePath().normalize()));
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    public void testExactContentGetsSourceCommitSubjectRatherThanReviewTransportCommit() throws Exception {
        Path file = write(root, "sample.tact", "fun sample() {}\n");
        String source = commit(root, "fix: validate the pool");
        write(root, ".doreview/example.json", "{}\n");
        String transport = commit(root, "docs: carry review records");
        String hash = hash("fun sample() {}\n");

        var labels = new ReviewGitHistory().find(file, root, Set.of(hash, hash("fun sample() { }\n")));

        assertEquals(Set.of(hash), labels.keySet());
        assertEquals(new ReviewGitHistory.Revision(source, "fix: validate the pool"), labels.get(hash));
        assertFalse(transport.equals(labels.get(hash).hash()));
    }

    public void testOnlyLineEndingsAreNormalizedAndDirtyCurrentTextIsNotACommit() throws Exception {
        Path file = write(root, "sample.txt", "first\r\nsecond\r\n");
        String source = commit(root, "style: windows lines");
        Files.writeString(file, "uncommitted\n", StandardCharsets.UTF_8);
        String baseline = hash("first\nsecond\n"), changed = hash("first \nsecond\n"), dirty = hash("uncommitted\n");

        var labels = new ReviewGitHistory().find(file, root, Set.of(baseline, changed, dirty));

        assertEquals(Set.of(baseline), labels.keySet());
        assertEquals(source, labels.get(baseline).hash());
    }

    public void testRenamedLiteralUnicodePathKeepsOlderSourceVersions() throws Exception {
        String oldName = "old [name] ü.txt", newName = "new [name] ü.txt";
        Path file = write(root, oldName, "before\n");
        String original = commit(root, "feat: original file");
        Files.writeString(file, "after\n", StandardCharsets.UTF_8);
        commit(root, "fix: update file");
        git(root, "mv", oldName, newName);
        String renamed = commit(root, "refactor: rename file");

        var labels = new ReviewGitHistory().find(root.resolve(newName), root, Set.of(hash("before\n"), hash("after\n")));

        assertEquals(original, labels.get(hash("before\n")).hash());
        assertEquals(renamed, labels.get(hash("after\n")).hash());
    }

    public void testNearestMatchingFileRevisionWinsAfterRevert() throws Exception {
        Path file = write(root, "sample.txt", "original\n");
        String first = commit(root, "feat: first");
        Files.writeString(file, "changed\n", StandardCharsets.UTF_8);
        commit(root, "feat: changed");
        Files.writeString(file, "original\n", StandardCharsets.UTF_8);
        String reverted = commit(root, "revert: original text");

        var labels = new ReviewGitHistory().find(file, root, Set.of(hash("original\n")));

        assertEquals(reverted, labels.get(hash("original\n")).hash());
        assertFalse(first.equals(reverted));
    }

    public void testCurrentHeadSeparatesCacheAcrossBranchesAndNewCommits() throws Exception {
        Path file = write(root, "sample.txt", "original\n");
        String first = commit(root, "feat: first");
        git(root, "branch", "original-only", first);
        String main = git(root, "branch", "--show-current").strip();
        Files.writeString(file, "new\n", StandardCharsets.UTF_8);
        String second = commit(root, "feat: second");
        var finder = new ReviewGitHistory();
        Set<String> snapshots = Set.of(hash("original\n"), hash("new\n"));
        assertEquals(second, finder.find(file, root, snapshots).get(hash("new\n")).hash());

        git(root, "checkout", "original-only");
        assertEquals(Set.of(hash("original\n")), finder.find(file, root, snapshots).keySet());
        git(root, "checkout", main);
        assertEquals(second, finder.find(file, root, snapshots).get(hash("new\n")).hash());
        Files.writeString(file, "third\n", StandardCharsets.UTF_8);
        String third = commit(root, "feat: third");
        assertEquals(third, finder.find(file, root, Set.of(hash("third\n"))).get(hash("third\n")).hash());
    }

    public void testNestedRepositoryAndLinkedWorktreeUseTheirOwnGitBoundary() throws Exception {
        Path outer = write(root, "outer.txt", "outside\n");
        commit(root, "feat: outer");
        Path nested = Files.createDirectories(root.resolve("nested"));
        initialize(nested);
        Path inner = write(nested, "inner.txt", "inside\n");
        String innerCommit = commit(nested, "feat: inner");
        assertEquals(innerCommit, new ReviewGitHistory().find(inner, root, Set.of(hash("inside\n"))).get(hash("inside\n")).hash());

        Path linked = temporary.resolve("linked");
        git(root, "worktree", "add", "--detach", linked.toString(), "HEAD");
        assertTrue(Files.isRegularFile(linked.resolve(".git")));
        assertEquals(1, new ReviewGitHistory().find(linked.resolve(outer.getFileName()), linked, Set.of(hash("outside\n"))).size());
    }

    public void testUnbornRepositoryMissingGitAndNonRepositoryHaveNoLabels() throws Exception {
        Path file = write(root, "sample.txt", "before any commit\n");
        var snapshots = Set.of(hash("before any commit\n"));
        assertTrue(new ReviewGitHistory().find(file, root, snapshots).isEmpty());
        assertTrue(new ReviewGitHistory("doreview-nonexistent-git-command", ReviewGitHistory.Limits.defaults())
                .find(file, root, snapshots).isEmpty());
        Path plain = Files.createDirectories(temporary.resolve("plain"));
        Path plainFile = write(plain, "plain.txt", "before any commit\n");
        assertTrue(new ReviewGitHistory().find(plainFile, plain, snapshots).isEmpty());
    }

    public void testInvalidUtf8AndNulBytesCannotCreateTextMatches() throws Exception {
        Path file = write(root, "sample.txt", "old\n");
        commit(root, "feat: valid source");
        Files.write(file, new byte[]{(byte) 0xc3, 0x28});
        commit(root, "test: invalid encoding");
        Files.write(file, new byte[]{'a', 0, 'b'});
        commit(root, "test: binary source");

        var labels = new ReviewGitHistory().find(file, root, Set.of(hash("old\n"), hash("\ufffd("), hash("a\0b")));

        assertEquals(Set.of(hash("old\n")), labels.keySet());
    }

    public void testHistoryByteAndTimeBudgetsFallBackConservatively() throws Exception {
        Path file = write(root, "sample.txt", "old\n");
        commit(root, "feat: old");
        Files.writeString(file, "x".repeat(200), StandardCharsets.UTF_8);
        commit(root, "feat: large");
        var snapshots = Set.of(hash("old\n"), hash("x".repeat(200)));
        var small = new ReviewGitHistory("git", new ReviewGitHistory.Limits(256, 4096, 16, 16, 3000));
        assertEquals(Set.of(hash("old\n")), small.find(file, root, snapshots).keySet());
        var history = new ReviewGitHistory("git", new ReviewGitHistory.Limits(1, 4096, 1024, 1024, 3000));
        assertEquals(Set.of(hash("x".repeat(200))), history.find(file, root, snapshots).keySet());
        var output = new ReviewGitHistory("git", new ReviewGitHistory.Limits(256, 8, 1024, 1024, 3000));
        assertTrue(output.find(file, root, snapshots).isEmpty());
        var expired = new ReviewGitHistory("git", new ReviewGitHistory.Limits(256, 4096, 1024, 1024, 0));
        assertTrue(expired.find(file, root, snapshots).isEmpty());
    }

    public void testGitIsRejectedOnEdtAndInterruptedLookupReturnsNoLabels() throws Exception {
        Path file = write(root, "sample.txt", "source\n");
        commit(root, "feat: source");
        var rejected = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            try { new ReviewGitHistory().find(file, root, Set.of(hash("source\n"))); }
            catch (IllegalStateException expected) { rejected.set(true); }
        });
        assertTrue(rejected.get());
        Thread.currentThread().interrupt();
        try {
            assertTrue(new ReviewGitHistory().find(file, root, Set.of(hash("source\n"))).isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    public void testChildEnvironmentCannotRedirectRepositoryObjectsConfigOrTrace() {
        var environment = new HashMap<String, String>();
        var selectors = List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_OBJECT_DIRECTORY",
                "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_INDEX_FILE", "GIT_NAMESPACE", "GIT_PREFIX",
                "GIT_CEILING_DIRECTORIES", "GIT_DISCOVERY_ACROSS_FILESYSTEM", "GIT_REPLACE_REF_BASE",
                "GIT_EXEC_PATH", "GIT_CONFIG", "GIT_CONFIG_COUNT", "GIT_CONFIG_PARAMETERS",
                "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0", "GIT_TRACE", "GIT_TRACE2_EVENT", "git_dir");
        selectors.forEach(name -> environment.put(name, "inherited-other-repository"));
        environment.put("PATH", "preserved-executable-search");
        environment.put("HOME", "preserved-user-home");
        environment.put("GIT_OPTIONAL_LOCKS", "1");
        environment.put("GIT_NO_LAZY_FETCH", "0");

        ReviewGitHistory.prepareEnvironment(environment);

        for (String selector : selectors) assertFalse(selector, environment.containsKey(selector));
        assertEquals("preserved-executable-search", environment.get("PATH"));
        assertEquals("preserved-user-home", environment.get("HOME"));
        assertEquals("0", environment.get("GIT_OPTIONAL_LOCKS"));
        assertEquals("1", environment.get("GIT_NO_LAZY_FETCH"));
        assertEquals("0", environment.get("GIT_TERMINAL_PROMPT"));
        assertEquals("1", environment.get("GIT_NO_REPLACE_OBJECTS"));
    }

    private static Path write(Path root, String path, String text) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text, StandardCharsets.UTF_8);
    }
    private static void initialize(Path root) throws Exception {
        git(root, "init", "--initial-branch=main");
        git(root, "config", "user.name", "Review test");
        git(root, "config", "user.email", "review@example.test");
        git(root, "config", "core.autocrlf", "false");
        git(root, "config", "commit.gpgsign", "false");
    }
    private static String commit(Path root, String subject) throws Exception {
        git(root, "add", "--all"); git(root, "commit", "-m", subject);
        return git(root, "rev-parse", "HEAD").strip();
    }
    private static String git(Path root, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=", "-C", root.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Git fixture timed out"); }
        assertEquals(command + "\n" + output, 0, process.exitValue());
        return output;
    }
    private static String hash(String text) { return FileSnapshot.create(text).id(); }
}
