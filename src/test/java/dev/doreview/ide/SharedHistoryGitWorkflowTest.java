package dev.doreview.ide;

import dev.doreview.core.EvidenceRef;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Real Git operations over isolated repositories, including separate native SQLite connections. */
public final class SharedHistoryGitWorkflowTest extends TestCase {
    private static final SharedHistoryRepository.Author ALICE = new SharedHistoryRepository.Author("Alice", "alice@example.test");
    private static final SharedHistoryRepository.Author BOB = new SharedHistoryRepository.Author("Bob", "bob@example.test");
    private Path temporary;
    private Path root;

    @Override protected void setUp() throws Exception {
        temporary = Files.createTempDirectory("doreview-git-");
        root = Files.createDirectory(temporary.resolve("repo"));
        git(root, "init", "-q", "-b", "main");
        configure(root);
        Files.writeString(root.resolve("source.txt"), "one\ntwo\n");
        commit(root, "Initial source");
    }

    @Override protected void tearDown() throws Exception {
        try (var paths = Files.walk(temporary)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                var attributes = Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class);
                if (attributes != null) attributes.setReadOnly(false);
                Files.deleteIfExists(path);
            }
        }
    }

    public void testRebaseCherryPickAndNewCloneUseOnlyCheckedOutEvents() throws Exception {
        try (var repository = repository(root, "original")) {
            var common = repository.publish(null, "source.txt", null, reviewed("source.txt"), ALICE);
            commit(root, "Common review");
            git(root, "checkout", "-q", "-b", "feature");
            repository.rescan();
            Files.writeString(root.resolve("feature.txt"), "one\ntwo\n");
            repository.publish(null, "feature.txt", null, reviewed("feature.txt"), BOB);
            commit(root, "Feature review");
            git(root, "checkout", "-q", "main");
            Files.writeString(root.resolve("unrelated.txt"), "advance main\n");
            commit(root, "Advance main");
            repository.rescan();
            assertNull(repository.load("feature.txt"));
            git(root, "checkout", "-q", "feature");
            git(root, "rebase", "main");
            repository.rescan();
            assertEquals(common.fileId(), repository.load("source.txt").fileId());
            String rebased = git(root, "rev-parse", "HEAD");
            String proof = repository.load("feature.txt").state().evidence.getFirst().id;
            git(root, "checkout", "-q", "main");
            repository.rescan();
            assertNull(repository.load("feature.txt"));
            git(root, "cherry-pick", rebased);
            repository.rescan();
            assertEquals(proof, repository.load("feature.txt").state().evidence.getFirst().id);
            Path clone = temporary.resolve("clone");
            git(temporary, "clone", "-q", root.toString(), clone.toString());
            try (var fresh = repository(clone, "fresh-clone")) {
                assertEquals(proof, fresh.load("feature.txt").state().evidence.getFirst().id);
                assertEquals(Set.of(BOB), Set.copyOf(fresh.load("feature.txt").evidenceAuthors().values()));
                assertEquals(common.fileId(), fresh.load("source.txt").fileId());
            }
        }
    }

    public void testTwoClonesMergeConcurrentRereviewAndTargetedRevocation() throws Exception {
        try (var alice = repository(root, "alice")) {
            var first = alice.publish(null, "source.txt", null, reviewed("source.txt"), ALICE);
            commit(root, "Alice review");
            Path clone = temporary.resolve("bob");
            git(temporary, "clone", "-q", root.toString(), clone.toString());
            configure(clone);
            try (var bob = repository(clone, "bob")) {
                var observed = bob.load("source.txt");
                var after = SharedHistoryRepository.copyFile(observed.state());
                ReviewJournalCodec.write(after, ReviewHistoryEngine.review(ReviewJournalCodec.read(after),
                        "one\ntwo\n", List.of(new ReviewedRange(0, 8)), 2));
                var rereview = bob.publish(observed.fileId(), "source.txt", observed.state(), after, BOB, observed.frontier());
                String bobProof = rereview.state().evidence.stream().filter(proof -> !proof.id.equals(first.state().evidence.getFirst().id)).findFirst().orElseThrow().id;
                commit(clone, "Bob independently rereviews");

                var cleared = SharedHistoryRepository.copyFile(first.state());
                String original = first.state().evidence.getFirst().id;
                ReviewJournalCodec.write(cleared, ReviewHistoryEngine.revoke(ReviewJournalCodec.read(cleared),
                        List.of(new EvidenceRef(original, 0, 8))));
                alice.publish(first.fileId(), "source.txt", first.state(), cleared, ALICE, first.frontier());
                commit(root, "Alice revokes observed review");
                git(root, "fetch", "-q", clone.toString(), "main");
                git(root, "merge", "--no-edit", "FETCH_HEAD");
                alice.rescan();
                var merged = alice.load("source.txt");
                assertEquals(2, merged.state().evidence.size());
                assertEquals(1, merged.state().revocations.size());
                assertEquals(original, merged.state().revocations.getFirst().evidenceId);
                assertEquals(BOB, merged.evidenceAuthors().get(bobProof));
                assertFalse(merged.state().revocations.stream().anyMatch(revocation -> revocation.evidenceId.equals(bobProof)));
                bob.rescan();
                assertTrue("The other clone keeps its own checkout until Git brings the merge", bob.load("source.txt").state().revocations.isEmpty());
            }
        }
    }

    public void testLinkedWorktreeKeepsIndependentDatabaseAndBranchEvidence() throws Exception {
        try (var main = repository(root, "main")) {
            var first = main.publish(null, "source.txt", null, reviewed("source.txt"), ALICE);
            commit(root, "Shared base review");
            Path linked = temporary.resolve("linked");
            git(root, "worktree", "add", "-q", "-b", "linked", linked.toString());
            assertFalse(ReviewRepositoryPaths.databasePath(root).equals(ReviewRepositoryPaths.databasePath(linked)));
            assertEquals(linked.toRealPath(), ReviewRepositoryPaths.rootFor(linked.resolve("source.txt"), root));
            try (var other = repository(linked, "linked")) {
                assertEquals(first.fileId(), other.load("source.txt").fileId());
                Files.writeString(linked.resolve("linked.txt"), "one\ntwo\n");
                other.publish(null, "linked.txt", null, reviewed("linked.txt"), BOB);
                commit(linked, "Linked-only review");
                main.rescan();
                assertNull(main.load("linked.txt"));
                assertNotNull(other.load("linked.txt"));
                git(root, "merge", "--no-edit", "linked");
                main.rescan();
                assertNotNull(main.load("linked.txt"));
            }
        }
    }

    private SharedHistoryRepository repository(Path workingTree, String name) {
        return new SharedHistoryRepository(workingTree, temporary.resolve("databases").resolve(name).resolve("history.sqlite3"));
    }
    private static ReviewHistoryStore.FileState reviewed(String path) {
        var state = new ReviewHistoryStore.FileState(); state.path = path;
        ReviewJournalCodec.write(state, ReviewHistoryEngine.review(ReviewHistory.empty(), "one\ntwo\n", List.of(new ReviewedRange(0, 8)), 1));
        return state;
    }
    private static void configure(Path directory) throws Exception {
        git(directory, "config", "user.name", "Test reviewer");
        git(directory, "config", "user.email", "reviewer@example.test");
        git(directory, "config", "commit.gpgsign", "false");
    }
    private static void commit(Path directory, String message) throws Exception {
        git(directory, "add", "--all"); git(directory, "commit", "-q", "-m", message);
    }
    private static String git(Path directory, String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git"); command.add("-C"); command.add(directory.toString());
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue("Git command timed out: " + command, process.waitFor(30, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals("Git command failed: " + command + "\n" + output, 0, process.exitValue());
        return output;
    }
}
