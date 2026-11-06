package dev.doreview.ide;

import dev.doreview.core.EvidenceRef;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Real checkout boundaries: untracked files, committed artifacts and independent worktrees. */
public final class SharedHistoryCheckoutTest extends TestCase {
    private static final SharedHistoryRepository.Author AUTHOR = new SharedHistoryRepository.Author("Reviewer", "reviewer@example.test");
    private Path temporary, root, database;

    @Override protected void setUp() throws Exception {
        temporary = Files.createTempDirectory("doreview-checkout-");
        root = Files.createDirectory(temporary.resolve("repository"));
        database = temporary.resolve("local/history.sqlite3");
        git(root, "init", "--template=", "-q", "-b", "source");
        Files.writeString(root.resolve("a.txt"), "one\ntwo\n");
        git(root, "add", "a.txt"); git(root, "commit", "-qm", "Initial source");
    }

    @Override protected void tearDown() throws Exception {
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                var dos = Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (dos != null) dos.setReadOnly(false);
                Files.deleteIfExists(path);
            }
        }
    }

    public void testUntrackedBranchCommandsRemainIsolatedAcrossReturnAndRestart() throws Exception {
        String originalProof, newProof;
        try (var repository = new SharedHistoryRepository(root, database)) {
            var first = repository.publish(null, "a.txt", null, reviewed("one\ntwo\n"), AUTHOR);
            originalProof = first.state().evidence.getFirst().id;
            git(root, "checkout", "-qb", "feature"); repository.rescan();
            assertEquals(originalProof, repository.load("a.txt").state().evidence.getFirst().id);
            var next = SharedHistoryRepository.copyFile(first.state());
            var history = ReviewHistoryEngine.revoke(ReviewJournalCodec.read(next), List.of(new EvidenceRef(originalProof, 0, 4)));
            history = ReviewHistoryEngine.review(history, "one\ntwo\n", List.of(new ReviewedRange(4, 8)), 2);
            ReviewJournalCodec.write(next, history);
            newProof = next.evidence.getLast().id;
            repository.publish(first.fileId(), "a.txt", first.state(), next, AUTHOR);
            assertEquals(2, events().size());
            git(root, "checkout", "-q", "source"); repository.rescan();
            assertSource(repository, originalProof);
            assertEquals("Switching branches must not publish review operations", 2, events().size());
        }
        try (var repository = new SharedHistoryRepository(root, database)) {
            assertSource(repository, originalProof);
            git(root, "checkout", "-q", "feature"); repository.rescan();
            var restored = repository.load("a.txt").state();
            assertEquals(2, restored.evidence.size()); assertEquals(1, restored.revocations.size());
            assertEquals(newProof, restored.evidence.getLast().id);
        }
        try (var repository = new SharedHistoryRepository(root, database)) {
            assertEquals(2, repository.load("a.txt").state().evidence.size());
            git(root, "checkout", "-q", "source"); repository.rescan(); assertSource(repository, originalProof);
        }
    }

    public void testMissingArtifactsAreBorrowedWithoutWritesAndRestoredOnlyByExplicitMutation() throws Exception {
        git(root, "branch", "feature");
        String eventBytes, originalProof;
        Path originalEvent;
        try (var repository = new SharedHistoryRepository(root, database)) {
            var first = repository.publish(null, "a.txt", null, reviewed("one\ntwo\n"), AUTHOR);
            originalProof = first.state().evidence.getFirst().id;
            originalEvent = events().getFirst(); eventBytes = Files.readString(originalEvent);
            git(root, "add", ".doreview"); git(root, "commit", "-qm", "Source review");
            repository.rescan();
            git(root, "checkout", "-q", "feature"); repository.rescan();
            assertFalse(Files.exists(root.resolve(".doreview")));
            assertSource(repository, originalProof);
        }
        try (var repository = new SharedHistoryRepository(root, database)) {
            assertSource(repository, originalProof);
            assertFalse("A cold projection must remain read-only", Files.exists(root.resolve(".doreview")));
            var first = repository.load("a.txt");
            var next = SharedHistoryRepository.copyFile(first.state());
            ReviewJournalCodec.write(next, ReviewHistoryEngine.revoke(ReviewJournalCodec.read(next), List.of(new EvidenceRef(originalProof, 0, 4))));
            var published = repository.publish(first.fileId(), "a.txt", first.state(), next, AUTHOR);
            assertEquals(1, published.state().revocations.size());
            assertEquals(eventBytes, Files.readString(originalEvent));
            assertEquals(2, events().size());
            git(root, "add", ".doreview"); git(root, "commit", "-qm", "Feature review");
            git(root, "checkout", "-q", "source"); repository.rescan(); assertSource(repository, originalProof);
            // Git intentionally introduces feature artifacts into another branch. Local owner
            // filtering must not override that committed sharing decision.
            git(root, "checkout", "-qb", "integrated"); repository.rescan();
            git(root, "checkout", "feature", "--", ".doreview");
            git(root, "commit", "-qm", "Integrate review artifacts"); repository.rescan();
            assertEquals(1, repository.load("a.txt").state().revocations.size());
        }
    }

    public void testDeletingNativeEventsOnTheSameBranchDoesNotRestoreCachedHistory() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            repository.publish(null, "a.txt", null, reviewed("one\ntwo\n"), AUTHOR);
            for (Path event : events()) Files.delete(event);
            repository.rescan(); assertNull(repository.load("a.txt"));
        }
        try (var repository = new SharedHistoryRepository(root, database)) { assertNull(repository.load("a.txt")); }
    }

    public void testIndependentGitWorktreeHasIndependentHistoryAndCheckoutBasis() throws Exception {
        Path parallel = temporary.resolve("parallel");
        git(root, "worktree", "add", "-qb", "parallel", parallel.toString());
        assertFalse(ReviewRepositoryPaths.databasePath(root).equals(ReviewRepositoryPaths.databasePath(parallel)));
        try (var first = new SharedHistoryRepository(root, database);
             var second = new SharedHistoryRepository(parallel, temporary.resolve("other/history.sqlite3"))) {
            assertFalse(first.checkoutToken().equals(second.checkoutToken()));
            var source = first.publish(null, "a.txt", null, reviewed("one\ntwo\n"), AUTHOR);
            second.rescan(); assertNull(second.load("a.txt"));
            second.publish(null, "a.txt", null, reviewed("parallel\n"), AUTHOR);
            first.rescan(); assertSource(first, source.state().evidence.getFirst().id);
            assertEquals("parallel\n", second.load("a.txt").state().snapshots.values().iterator().next());
        }
    }

    public void testIdenticalPendingStateDoesNotRewriteItsTimestampOrSnapshot() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var state = reviewed("one\ntwo\n"); String token = repository.checkoutToken();
            repository.savePending("pending-file", "a.txt", state, AUTHOR, token, Set.of());
            String originalPayload;
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement()) {
                try (var rows = statement.executeQuery("SELECT payload FROM pending")) { assertTrue(rows.next()); originalPayload = rows.getString(1); }
                statement.execute("CREATE TABLE test_write_audit(kind TEXT)");
                statement.execute("CREATE TRIGGER test_pending_update AFTER UPDATE ON pending BEGIN INSERT INTO test_write_audit VALUES('pending'); END");
                statement.execute("CREATE TRIGGER test_snapshot_insert AFTER INSERT ON snapshots BEGIN INSERT INTO test_write_audit VALUES('snapshot'); END");
            }
            for (int i = 0; i < 20; i++) repository.savePending("pending-file", "a.txt", state, AUTHOR, token, Set.of());
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM test_write_audit")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement(); var rows = statement.executeQuery("SELECT payload FROM pending")) {
                assertTrue(rows.next()); assertEquals("The durable createdAt must also remain unchanged", originalPayload, rows.getString(1));
            }
            assertFalse(Files.exists(root.resolve(".doreview")));
        }
    }

    public void testFailedFirstVisitDoesNotFreezeAnUnvalidatedBranchBasis() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var source = repository.publish(null, "a.txt", null, reviewed("one\ntwo\n"), AUTHOR);
            git(root, "checkout", "-qb", "feature");
            Path invalid = root.resolve(".doreview/v1/events/00/00000000-0000-0000-0000-000000000000.json");
            Files.createDirectories(invalid.getParent()); Files.writeString(invalid, "invalid");
            try { repository.rescan(); fail("Expected invalid event rejection"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Cannot read shared review history")); }
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM known_checkouts")) {
                assertTrue(rows.next()); assertEquals("Only the successfully scanned source checkout is known", 1, rows.getInt(1));
            }
            Files.delete(invalid); repository.rescan();
            assertSource(repository, source.state().evidence.getFirst().id);
        }
    }

    public void testInterruptionAfterPortablePublicationCannotLeakCommandToAnotherBranch() throws Exception {
        String fileId, sourceToken, proof;
        try (var repository = new SharedHistoryRepository(root, database, () -> { throw new IllegalStateException("injected interruption after publication"); })) {
            var state = reviewed("one\ntwo\n"); proof = state.evidence.getFirst().id;
            fileId = repository.fileIdFor("a.txt"); sourceToken = repository.checkoutToken();
            repository.savePending(fileId, "a.txt", state, AUTHOR, sourceToken, Set.of());
            try { repository.publish(fileId, "a.txt", null, state, AUTHOR); fail("Expected interrupted publication"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("injected interruption")); }
            assertEquals("The interruption occurs after the immutable file is visible", 1, events().size());
        }
        git(root, "checkout", "-qb", "feature");
        try (var repository = new SharedHistoryRepository(root, database)) {
            assertNull("An interrupted source command must not look like an external feature event", repository.load("a.txt"));
            assertTrue(repository.hasPendingOtherCheckouts(repository.checkoutToken()));
        }
        git(root, "checkout", "-q", "source");
        try (var repository = new SharedHistoryRepository(root, database)) {
            assertSource(repository, proof);
            assertNotNull("The interrupted command remains recoverable on its source branch", repository.pending(fileId, sourceToken));
        }
    }

    public void testFailedOwnershipReservationPublishesNoEventAndRetainsPendingState() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var state = reviewed("one\ntwo\n");
            String fileId = repository.fileIdFor("a.txt"), token = repository.checkoutToken();
            repository.savePending(fileId, "a.txt", state, AUTHOR, token, Set.of());
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement()) {
                statement.execute("CREATE TRIGGER test_owner_failure BEFORE INSERT ON local_event_owners BEGIN SELECT RAISE(ABORT,'injected owner failure'); END");
            }
            try { repository.publish(fileId, "a.txt", null, state, AUTHOR); fail("Expected ownership failure"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("injected owner failure")); }
            assertTrue("An event must not be visible without its committed checkout owner", events().isEmpty());
            assertNotNull(repository.pending(fileId, token));
        }
    }

    public void testAnotherConnectionCannotLeaveAReplacementBranchesEventsActive() throws Exception {
        try (var first = new SharedHistoryRepository(root, database)) {
            var source = first.publish(null, "a.txt", null, reviewed("one\ntwo\n"), AUTHOR);
            String proof = source.state().evidence.getFirst().id;
            git(root, "checkout", "-qb", "feature"); first.rescan();
            var denied = SharedHistoryRepository.copyFile(source.state());
            ReviewJournalCodec.write(denied, ReviewHistoryEngine.revoke(ReviewJournalCodec.read(denied), List.of(new EvidenceRef(proof, 0, 4))));
            first.publish(source.fileId(), "a.txt", source.state(), denied, AUTHOR);
            // Both immutable files remain untracked throughout. First remembers identical
            // source HEAD/disk fingerprints while second installs feature into shared SQL.
            git(root, "checkout", "-q", "source"); first.rescan(); assertSource(first, proof);
            try (var second = new SharedHistoryRepository(root, database)) {
                git(root, "checkout", "-q", "feature"); second.rescan();
                assertEquals(1, second.load("a.txt").state().revocations.size());
                git(root, "checkout", "-q", "source");
                assertTrue("External active-set replacement must be reconciled despite unchanged source fingerprints", first.rescan());
                assertSource(first, proof);
            }
        }
    }

    private void assertSource(SharedHistoryRepository repository, String proof) {
        var state = repository.load("a.txt").state();
        assertEquals(1, state.evidence.size()); assertEquals(proof, state.evidence.getFirst().id); assertTrue(state.revocations.isEmpty());
    }

    private List<Path> events() throws Exception {
        if (!Files.isDirectory(root.resolve(".doreview/v1/events"))) return List.of();
        try (var paths = Files.walk(root.resolve(".doreview/v1/events"))) {
            return paths.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static ReviewHistoryStore.FileState reviewed(String text) {
        var state = new ReviewHistoryStore.FileState(); state.path = "a.txt";
        ReviewJournalCodec.write(state, ReviewHistoryEngine.review(ReviewHistory.empty(), text, List.of(new ReviewedRange(0, text.length())), 1));
        return state;
    }

    private String git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", directory.toString(), "-c", "core.hooksPath=" + temporary.resolve("disabled-hooks"),
                "-c", "user.name=Reviewer", "-c", "user.email=reviewer@example.test", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true); ReviewGitHistory.prepareEnvironment(builder.environment());
        var process = builder.start(); String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor()); return output.trim();
    }
}
