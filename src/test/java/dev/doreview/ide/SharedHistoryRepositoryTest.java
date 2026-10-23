package dev.doreview.ide;

import dev.doreview.core.EvidenceRef;
import dev.doreview.core.FileSnapshot;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;
import junit.framework.TestCase;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Every repository and database lives under a test-owned temporary directory. */
public final class SharedHistoryRepositoryTest extends TestCase {
    private Path temporary;
    private Path root;
    private Path database;
    private static final SharedHistoryRepository.Author ALICE = new SharedHistoryRepository.Author("Alice", "alice@example.test");
    private static final SharedHistoryRepository.Author BOB = new SharedHistoryRepository.Author("Bob", "bob@example.test");

    @Override protected void setUp() throws Exception {
        temporary = Files.createTempDirectory("doreview-repository-");
        root = Files.createDirectories(temporary.resolve("worktree"));
        database = temporary.resolve("local/history.sqlite3");
    }
    @Override protected void tearDown() throws Exception {
        try (var files = Files.walk(temporary)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public void testRoundTripCreatesPortableEventsAndCompressedDatabaseSnapshots() throws Exception {
        String text = "first\r\nsecond\r\n";
        String id;
        try (var repository = new SharedHistoryRepository(root, database)) {
            var state = reviewed("a.txt", text);
            var loaded = repository.publish(null, "a.txt", null, state, ALICE);
            id = loaded.fileId();
            assertEquals(1, loaded.state().evidence.size());
            assertEquals(ALICE, loaded.evidenceAuthors().values().iterator().next());
            assertEquals("first\nsecond\n", repository.snapshot(state.evidence.getFirst().snapshotId));
            assertEquals(1, repository.versions(id).size());
            assertTrue(Files.readString(root.resolve(".doreview/.gitattributes")).contains("-text"));
        }
        try (var repository = new SharedHistoryRepository(root, database)) {
            assertEquals(id, repository.load("a.txt").fileId());
            assertEquals(1, count(root.resolve(".doreview/v1/events"), ".json"));
            assertEquals(1, count(root.resolve(".doreview/v1/snapshots"), ".txt"));
        }
        try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement(); var rows = statement.executeQuery("SELECT encoding,data FROM snapshots")) {
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            byte[] data = rows.getBytes(2); assertEquals(0x1f, data[0] & 255); assertEquals(0x8b, data[1] & 255);
        }
    }

    public void testCloneRebuildsWithoutOriginalDatabase() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) { repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE); }
        Path clone = Files.createDirectories(temporary.resolve("clone"));
        copyTree(root.resolve(".doreview"), clone.resolve(".doreview"));
        try (var repository = new SharedHistoryRepository(clone, temporary.resolve("clone-local/history.sqlite3"))) {
            assertEquals(1, repository.load("a.txt").state().evidence.size());
            assertEquals(ALICE, repository.load("a.txt").evidenceAuthors().values().iterator().next());
        }
    }

    public void testPublishOnlyAddsDeltaAndDeduplicatesSnapshots() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var first = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\ntwo\n"), ALICE);
            var next = SharedHistoryRepository.copyFile(first.state());
            ReviewJournalCodec.write(next, ReviewHistoryEngine.review(ReviewJournalCodec.read(next), "one\ntwo\n", List.of(new ReviewedRange(0, 4)), 2));
            repository.publish(first.fileId(), "a.txt", first.state(), next, BOB);
            assertEquals(2, count(root.resolve(".doreview/v1/events"), ".json"));
            assertEquals(1, count(root.resolve(".doreview/v1/snapshots"), ".txt"));
            repository.publish(first.fileId(), "a.txt", next, next, BOB);
            assertEquals(2, count(root.resolve(".doreview/v1/events"), ".json"));
        }
    }

    public void testConcurrentNewReviewsShareFileIdentityAndSurviveMerge() throws Exception {
        Path other = Files.createDirectories(temporary.resolve("other"));
        try (var alice = new SharedHistoryRepository(root, database); var bob = new SharedHistoryRepository(other, temporary.resolve("bob-local/history.sqlite3"))) {
            var first = alice.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            var second = bob.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), BOB);
            assertEquals(first.fileId(), second.fileId());
            copyTree(other.resolve(".doreview"), root.resolve(".doreview"));
            alice.rescan();
            var merged = alice.load("a.txt");
            assertEquals(2, merged.state().evidence.size());
            assertEquals(Set.of(ALICE, BOB), Set.copyOf(merged.evidenceAuthors().values()));
        }
    }

    public void testConcurrentRevocationOnlyRevokesObservedEvidence() throws Exception {
        Path other = Files.createDirectories(temporary.resolve("other"));
        try (var alice = new SharedHistoryRepository(root, database)) {
            var first = alice.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            copyTree(root.resolve(".doreview"), other.resolve(".doreview"));
            try (var bob = new SharedHistoryRepository(other, temporary.resolve("bob-local/history.sqlite3"))) {
                var second = bob.load("a.txt");
                var rereviewed = SharedHistoryRepository.copyFile(second.state());
                ReviewJournalCodec.write(rereviewed, ReviewHistoryEngine.review(ReviewJournalCodec.read(rereviewed), "one\n", List.of(new ReviewedRange(0, 4)), 3));
                bob.publish(second.fileId(), "a.txt", second.state(), rereviewed, BOB);
                var revoked = SharedHistoryRepository.copyFile(first.state());
                var proof = first.state().evidence.getFirst();
                ReviewJournalCodec.write(revoked, ReviewHistoryEngine.revoke(ReviewJournalCodec.read(revoked), List.of(new EvidenceRef(proof.id, 0, 4))));
                alice.publish(first.fileId(), "a.txt", first.state(), revoked, ALICE);
                copyTree(other.resolve(".doreview"), root.resolve(".doreview")); alice.rescan();
                ReviewHistory merged = ReviewJournalCodec.read(alice.load("a.txt").state());
                assertEquals(2, merged.evidence().size()); assertEquals(1, merged.revocations().size());
                assertEquals(proof.id, merged.revocations().getFirst().evidenceId());
            }
        }
    }

    public void testRevocationAcceptsAnyCausalOriginOfIdenticalImportedProof() throws Exception {
        Path other = Files.createDirectories(temporary.resolve("other"));
        var imported = reviewed("a.txt", "one\n");
        try (var alice = new SharedHistoryRepository(root, database); var bob = new SharedHistoryRepository(other, temporary.resolve("bob-local/history.sqlite3"))) {
            var first = alice.publish("legacy-file", "a.txt", null, imported, SharedHistoryRepository.Author.imported());
            var second = bob.publish("legacy-file", "a.txt", null, imported, SharedHistoryRepository.Author.imported());
            String firstOrigin = first.frontier().iterator().next(), secondOrigin = second.frontier().iterator().next();
            var origin = firstOrigin.compareTo(secondOrigin) < 0 ? second : first;
            var writer = firstOrigin.compareTo(secondOrigin) < 0 ? bob : alice;
            var revoked = SharedHistoryRepository.copyFile(origin.state());
            var proof = origin.state().evidence.getFirst();
            ReviewJournalCodec.write(revoked, ReviewHistoryEngine.revoke(ReviewJournalCodec.read(revoked), List.of(new EvidenceRef(proof.id, 0, 4))));
            writer.publish(origin.fileId(), "a.txt", origin.state(), revoked, BOB, origin.frontier());
            copyTree(other.resolve(".doreview"), root.resolve(".doreview")); alice.rescan();
            assertEquals(1, alice.load("a.txt").state().evidence.size());
            assertEquals(1, alice.load("a.txt").state().revocations.size());
        }
    }

    public void testRemovedCheckoutEventsDoNotLeakThroughSQLiteCache() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            repository.publish(null, "main.txt", null, reviewed("main.txt", "main\n"), ALICE);
            var before = eventFiles(root);
            repository.publish(null, "feature.txt", null, reviewed("feature.txt", "feature\n"), BOB);
            var feature = eventFiles(root).stream().filter(path -> !before.contains(path)).findFirst().orElseThrow();
            byte[] bytes = Files.readAllBytes(feature);
            assertNotNull(repository.load("feature.txt"));
            Files.delete(feature); repository.rescan();
            assertNull(repository.load("feature.txt")); assertNotNull(repository.load("main.txt"));
            Files.write(feature, bytes); repository.rescan();
            assertNotNull(repository.load("feature.txt"));
        }
    }

    public void testGitCheckoutTokenIsStableAcrossPublishAndDifferentAcrossSameCommitBranches() throws Exception {
        Path git = Files.createDirectories(root.resolve(".git/refs/heads")).getParent().getParent();
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(git.resolve("refs/heads/main"), "0123456789012345678901234567890123456789\n");
        Files.writeString(git.resolve("refs/heads/feature"), "0123456789012345678901234567890123456789\n");
        try (var repository = new SharedHistoryRepository(root, database)) {
            String main = repository.checkoutToken();
            repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            assertEquals(main, repository.checkoutToken());
            var reviewed = repository.load("a.txt");
            repository.savePending(reviewed.fileId(), "a.txt", reviewed.state(), ALICE, main, reviewed.frontier());
            Files.writeString(git.resolve("refs/heads/main"), "1123456789012345678901234567890123456789\n");
            assertTrue(repository.rescan());
            assertEquals(main, repository.checkoutToken());
            assertNotNull(repository.pending(reviewed.fileId(), main));
            repository.flushPending(reviewed.fileId(), main);
            Files.writeString(git.resolve("HEAD"), "ref: refs/heads/feature\n"); repository.rescan();
            assertFalse(main.equals(repository.checkoutToken()));
        }
    }

    public void testMissingCausalDependencyFailsClosed() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var first = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            Path original = eventFiles(root).getFirst();
            var next = SharedHistoryRepository.copyFile(first.state());
            ReviewJournalCodec.write(next, ReviewHistoryEngine.review(ReviewJournalCodec.read(next), "two\n", List.of(new ReviewedRange(0, 4)), 2));
            repository.publish(first.fileId(), "a.txt", first.state(), next, BOB);
            Files.delete(original);
            expectFailure(repository::rescan, "Missing causal dependency");
            assertNull(repository.load("a.txt"));
        }
    }

    public void testChangedImmutableEventIdentifierIsRejected() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            Path event = eventFiles(root).getFirst();
            Files.writeString(event, Files.readString(event).replace("Alice", "Changed author"));
            expectFailure(repository::rescan, "Conflicting immutable review event");
            assertNull(repository.load("a.txt"));
        }
    }

    public void testGitEventLineEndingConversionDoesNotChangeEventIdentity() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            Path event = eventFiles(root).getFirst();
            Files.writeString(event, Files.readString(event).replace("\n", "\r\n"));
            repository.rescan();
            assertNotNull(repository.load("a.txt"));
            assertEquals(1, repository.load("a.txt").state().evidence.size());
            assertTrue(Files.readString(root.resolve(".doreview/.gitattributes")).contains("v1/events/** text eol=lf"));
        }
    }

    public void testDamagedSnapshotFailsClosedEvenWhenPreviouslyIndexed() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            Path snapshot;
            try (var files = Files.walk(root.resolve(".doreview/v1/snapshots"))) { snapshot = files.filter(Files::isRegularFile).findFirst().orElseThrow(); }
            Files.writeString(snapshot, "corrupted\n");
            expectFailure(repository::rescan, "Snapshot id must match");
            assertNull(repository.load("a.txt"));
        }
    }

    public void testCheckpointV2RoundTripsWithoutXmlLossAndPendingRemainsLocal() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var state = reviewed("a.txt", "one\n");
            ReviewHistory history = ReviewJournalCodec.read(state);
            var proof = history.evidence().getFirst(); var ref = new EvidenceRef(proof.id(), 0, 4);
            state.checkpoints.put(FileSnapshot.create("one\n").id(), ProjectionCheckpointCodec.encode(history, "one\n", new ProjectionHints(Map.of(ref, new ReviewedRange(0, 4)), Set.of(ref))));
            var loaded = repository.publish(null, "a.txt", null, state, ALICE);
            var checkpoint = loaded.state().checkpoints.values().iterator().next();
            assertEquals(state.checkpoints.values().iterator().next().checksum, checkpoint.checksum);
            assertEquals(2, checkpoint.payload.version);
            var pending = SharedHistoryRepository.copyFile(loaded.state()); pending.pendingCheckpoint = checkpoint;
            repository.savePending(loaded.fileId(), "a.txt", pending, ALICE, repository.checkoutToken());
            assertNotNull(repository.pending(loaded.fileId(), repository.checkoutToken()).pendingCheckpoint);
            assertEquals(1, count(root.resolve(".doreview/v1/events"), ".json"));
            repository.flushPending(loaded.fileId(), repository.checkoutToken());
            assertNull(repository.pending(loaded.fileId(), repository.checkoutToken()));
        }
    }

    public void testPendingCannotBeExportedAfterCheckoutChange() throws Exception {
        Path git = Files.createDirectories(root.resolve(".git")); Files.writeString(git.resolve("HEAD"), "a\n");
        try (var repository = new SharedHistoryRepository(root, database)) {
            var loaded = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            String token = repository.checkoutToken();
            repository.savePending(loaded.fileId(), "a.txt", loaded.state(), ALICE, token);
            Files.writeString(git.resolve("HEAD"), "b\n"); repository.rescan();
            String secondToken = repository.checkoutToken();
            repository.savePending(loaded.fileId(), "a.txt", loaded.state(), BOB, secondToken, loaded.frontier());
            expectFailure(() -> repository.flushPending(loaded.fileId(), token), "another checkout");
            assertNotNull(repository.pending(loaded.fileId(), token));
            assertNotNull(repository.pending(loaded.fileId(), secondToken));
            assertEquals(1, count(root.resolve(".doreview/v1/events"), ".json"));
        }
    }

    public void testStalePendingPositiveCannotSupersedeUnseenProvenanceDenial() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            ReviewHistory history = ReviewJournalCodec.read(base.state());
            var ref = new EvidenceRef(history.evidence().getFirst().id(), 0, 4);
            String hash = FileSnapshot.create("one\n").id();
            var stalePositive = SharedHistoryRepository.copyFile(base.state());
            stalePositive.pendingCheckpoint = ProjectionCheckpointCodec.encode(history, "one\n", new ProjectionHints(Map.of(ref, new ReviewedRange(0, 4)), Set.of(ref)));
            repository.savePending(base.fileId(), "a.txt", stalePositive, BOB, repository.checkoutToken(), base.frontier());
            var denial = SharedHistoryRepository.copyFile(base.state());
            denial.checkpoints.put(hash, ProjectionCheckpointCodec.encode(history, "one\n", new ProjectionHints(Map.of(), Set.of(ref))));
            repository.publish(base.fileId(), "a.txt", base.state(), denial, ALICE, base.frontier());
            var merged = repository.flushPending(base.fileId(), repository.checkoutToken());
            assertEquals(2, merged.frontier().size());
            var hints = ProjectionCheckpointCodec.decode(ReviewJournalCodec.read(merged.state()), "one\n", merged.state().checkpoints.get(hash));
            assertTrue(hints.livePositions().isEmpty());
            assertTrue(hints.authoritativeRefs().contains(ref));
        }
    }

    public void testLegacyV1CheckpointChecksumIsPreserved() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var state = reviewed("a.txt", "one\n");
            ReviewHistory history = ReviewJournalCodec.read(state);
            var proof = history.evidence().getFirst(); var ref = new EvidenceRef(proof.id(), 0, 4);
            var checkpoint = ProjectionCheckpointCodec.encode(history, "one\n", new ProjectionHints(Map.of(ref, new ReviewedRange(0, 4)), Set.of(ref)));
            checkpoint.payload.version = 1; checkpoint.checksum = ProjectionCheckpointCodec.checksum(checkpoint.payload);
            state.checkpoints.put(checkpoint.payload.textHash, checkpoint);
            var loaded = repository.publish("legacy-file-id", "a.txt", null, state, SharedHistoryRepository.Author.imported());
            var reopened = loaded.state().checkpoints.get(checkpoint.payload.textHash);
            assertEquals(1, reopened.payload.version); assertEquals(checkpoint.checksum, reopened.checksum);
            assertEquals(Set.of(ref), ProjectionCheckpointCodec.decode(ReviewJournalCodec.read(loaded.state()), "one\n", reopened).authoritativeRefs());
        }
    }

    public void testPendingWritersDoNotOverwriteAndRecoveryMergesConservatively() throws Exception {
        String fileId; String token;
        try (var alice = new SharedHistoryRepository(root, database); var bob = new SharedHistoryRepository(root, database)) {
            var base = alice.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            bob.rescan(); fileId = base.fileId(); token = alice.checkoutToken();
            ReviewHistory history = ReviewJournalCodec.read(base.state());
            var ref = new EvidenceRef(history.evidence().getFirst().id(), 0, 4);
            var positive = SharedHistoryRepository.copyFile(base.state());
            positive.pendingCheckpoint = ProjectionCheckpointCodec.encode(history, "one\n", new ProjectionHints(Map.of(ref, new ReviewedRange(0, 4)), Set.of(ref)));
            var denial = SharedHistoryRepository.copyFile(base.state());
            denial.pendingCheckpoint = ProjectionCheckpointCodec.encode(history, "one\n", new ProjectionHints(Map.of(), Set.of(ref)));
            alice.savePending(fileId, "a.txt", positive, ALICE, token, base.frontier());
            bob.savePending(fileId, "a.txt", denial, BOB, token, base.frontier());
            var merged = alice.pending(fileId, token);
            var hints = ProjectionCheckpointCodec.decode(ReviewJournalCodec.read(merged), "one\n", merged.pendingCheckpoint);
            assertTrue(hints.livePositions().isEmpty()); assertTrue(hints.authoritativeRefs().contains(ref));
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM pending")) { rows.next(); assertEquals(2, rows.getInt(1)); }
        }
        try (var restarted = new SharedHistoryRepository(root, database)) {
            assertNotNull(restarted.pending(fileId, token));
            var published = restarted.flushPending(fileId, token);
            assertEquals(2, published.frontier().size());
            assertNull(restarted.pending(fileId, token));
            assertEquals(3, count(root.resolve(".doreview/v1/events"), ".json"));
        }
    }

    public void testAcknowledgementOnlyClearsCurrentWriterPending() throws Exception {
        try (var alice = new SharedHistoryRepository(root, database); var bob = new SharedHistoryRepository(root, database)) {
            var base = alice.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE); bob.rescan();
            String token = alice.checkoutToken();
            alice.savePending(base.fileId(), "a.txt", base.state(), ALICE, token, base.frontier());
            bob.savePending(base.fileId(), "a.txt", base.state(), BOB, token, base.frontier());
            alice.acknowledgePending(base.fileId(), token);
            assertNotNull(alice.pending(base.fileId(), token));
            bob.acknowledgePending(base.fileId(), token);
            assertNull(alice.pending(base.fileId(), token));
        }
    }

    public void testRecreatedOldPathDoesNotInheritRenamedFileIdentity() throws Exception {
        Path original = Files.writeString(root.resolve("a.txt"), "one\n");
        try (var repository = new SharedHistoryRepository(root, database)) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            Files.move(original, root.resolve("b.txt"));
            var renamed = SharedHistoryRepository.copyFile(base.state()); renamed.path = "b.txt";
            repository.publish(base.fileId(), "b.txt", base.state(), renamed, ALICE, base.frontier());
            Files.writeString(original, "one\n");
            assertNull(repository.load("a.txt")); assertNotNull(repository.load("b.txt"));
            assertFalse(base.fileId().equals(repository.fileIdFor("a.txt")));
            var fresh = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), BOB, Set.of());
            assertFalse(base.fileId().equals(fresh.fileId()));
            assertEquals(1, fresh.state().evidence.size()); assertEquals(BOB, fresh.evidenceAuthors().values().iterator().next());
        }
    }

    public void testUnchangedRescanDoesNotDecodeArchiveAndDeltaOnlyIndexesNewEvent() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            for (int i = 0; i < 20; i++) repository.publish(null, "file-" + i + ".txt", null, reviewed("file-" + i + ".txt", "same snapshot\n"), ALICE);
            assertFalse(repository.rescan());
            assertEquals(0L, repository.lastScanStatistics().decodedEventFiles());
            assertEquals(0L, repository.lastScanStatistics().readSnapshotFiles());
            assertEquals(0L, repository.lastScanStatistics().indexedEvents());
            repository.publish(null, "new.txt", null, reviewed("new.txt", "same snapshot\n"), BOB);
            assertEquals(1L, repository.lastScanStatistics().decodedEventFiles());
            assertEquals(0L, repository.lastScanStatistics().readSnapshotFiles());
            assertEquals(1L, repository.lastScanStatistics().indexedEvents());
        }
    }

    public void testRebuildPublishesPendingBacksUpAndRepairsCachedSnapshotBlob() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            repository.savePending(base.fileId(), "a.txt", base.state(), ALICE, repository.checkoutToken(), base.frontier());
            Path backup = repository.rebuildIndex();
            assertTrue(Files.isRegularFile(backup)); assertNull(repository.pending(base.fileId(), repository.checkoutToken()));
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement()) { statement.execute("UPDATE snapshots SET data=X'00010203'"); }
            expectFailure(() -> repository.snapshot(base.state().evidence.getFirst().snapshotId), "Cannot load review snapshot");
            repository.rebuildIndex();
            assertEquals("one\n", repository.snapshot(base.state().evidence.getFirst().snapshotId));
            assertNotNull(repository.load("a.txt"));
        }
    }

    public void testRebuildRefusesPendingFromAnotherCheckoutWithoutDeletingIt() throws Exception {
        Path git = Files.createDirectories(root.resolve(".git")); Files.writeString(git.resolve("HEAD"), "a\n");
        try (var repository = new SharedHistoryRepository(root, database)) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            String old = repository.checkoutToken(); repository.savePending(base.fileId(), "a.txt", base.state(), ALICE, old, base.frontier());
            Files.writeString(git.resolve("HEAD"), "b\n"); repository.rescan();
            expectFailure(repository::rebuildIndex, "another checkout");
            assertNotNull(repository.pending(base.fileId(), old)); assertNotNull(repository.load("a.txt"));
        }
    }

    public void testUnknownPortableVersionIsPreservedAndCannotConfirmOldGreen() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            Path future = Files.createDirectories(root.resolve(".doreview/v2"));
            Path data = Files.writeString(future.resolve("future-format.json"), "{\"future\":true}\n");
            expectFailure(repository::rescan, "Unsupported shared review history version");
            assertEquals("{\"future\":true}\n", Files.readString(data));
            assertNull(repository.load("a.txt"));
        }
    }

    public void testSameBranchResetRetainsOrphanPendingInsteadOfReplacingIt() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            String token = repository.checkoutToken();
            repository.savePending(base.fileId(), "a.txt", base.state(), ALICE, token, base.frontier());
            for (Path event : eventFiles(root)) Files.delete(event);
            repository.rescan();
            assertNull(repository.pending(base.fileId(), token));
            repository.savePending(base.fileId(), "a.txt", reviewed("a.txt", "two\n"), ALICE, token, Set.of());
            assertNotNull(repository.pending(base.fileId(), token));
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM pending")) { rows.next(); assertEquals(2, rows.getInt(1)); }
        }
    }

    public void testOrphanProvenanceStillRestrictsSameProofReimportedInNewLineage() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            var denied = SharedHistoryRepository.copyFile(base.state());
            ReviewHistory history = ReviewJournalCodec.read(denied);
            var ref = new EvidenceRef(history.evidence().getFirst().id(), 0, 4);
            denied.pendingCheckpoint = ProjectionCheckpointCodec.encode(history, "one\n", new ProjectionHints(Map.of(), Set.of(ref)));
            repository.savePending(base.fileId(), "a.txt", denied, ALICE, repository.checkoutToken(), base.frontier());
            for (Path event : eventFiles(root)) Files.delete(event);
            repository.rescan();
            repository.publish(base.fileId(), "a.txt", null, base.state(), SharedHistoryRepository.Author.imported(), Set.of());
            var pending = repository.pending(base.fileId(), repository.checkoutToken());
            assertNotNull(pending);
            var checkpoint = pending.checkpoints.get(FileSnapshot.create("one\n").id());
            var hints = ProjectionCheckpointCodec.decode(ReviewJournalCodec.read(pending), "one\n", checkpoint);
            assertTrue(hints.authoritativeRefs().contains(ref)); assertTrue(hints.livePositions().isEmpty());
        }
    }

    public void testLargeArchiveUsesMetadataOnlyWarmScanAndIndexesOneAppendedCommand() throws Exception {
        var json = new Gson();
        var snapshot = FileSnapshot.create("one\n");
        Path snapshotPath = root.resolve(".doreview/v1/snapshots/" + snapshot.id().substring(0, 2) + "/" + snapshot.id() + ".txt");
        Files.createDirectories(snapshotPath.getParent()); Files.writeString(snapshotPath, snapshot.text());
        for (int file = 0; file < 500; file++) {
            String path = "file-" + file + ".txt";
            String fileId = ReviewRepositoryPaths.sha256("file\n" + path);
            String parent = null;
            for (int command = 0; command < 10; command++) {
                var event = new SharedHistoryRepository.Event(); event.id = UUID.randomUUID().toString();
                event.fileId = fileId; event.path = path; event.aliases.add(path);
                event.authorName = ALICE.name(); event.authorEmail = ALICE.email(); event.createdAt = 1;
                if (parent != null) event.parents.add(parent);
                var proof = new ReviewHistoryStore.EvidenceState(); proof.id = UUID.randomUUID().toString();
                proof.snapshotId = snapshot.id(); proof.createdAt = 1; proof.ranges.add(new ReviewHistoryStore.RangeState(0, 4));
                event.evidence.add(proof); event.snapshots.add(snapshot.id());
                Path eventPath = root.resolve(".doreview/v1/events/" + event.id.substring(0, 2) + "/" + event.id + ".json");
                Files.createDirectories(eventPath.getParent()); Files.writeString(eventPath, json.toJson(event) + "\n");
                parent = event.id;
            }
        }
        Runtime runtime = Runtime.getRuntime();
        long beforeHeap = runtime.totalMemory() - runtime.freeMemory(), coldStart = System.nanoTime();
        try (var repository = new SharedHistoryRepository(root, database)) {
            long coldNanos = System.nanoTime() - coldStart;
            assertEquals(5000L, repository.lastScanStatistics().decodedEventFiles());
            assertEquals(5000L, repository.lastScanStatistics().indexedEvents());
            long warmStart = System.nanoTime(); assertFalse(repository.rescan()); long warmNanos = System.nanoTime() - warmStart;
            assertEquals(0L, repository.lastScanStatistics().decodedEventFiles());
            assertEquals(0L, repository.lastScanStatistics().readSnapshotFiles());
            var loaded = repository.load("file-0.txt"); assertEquals(10, loaded.state().evidence.size());
            assertEquals(1, loaded.state().snapshots.size());
            var next = SharedHistoryRepository.copyFile(loaded.state());
            ReviewJournalCodec.write(next, ReviewHistoryEngine.review(ReviewJournalCodec.read(next), "one\n", List.of(new ReviewedRange(0, 4)), 2));
            long appendStart = System.nanoTime();
            repository.publish(loaded.fileId(), "file-0.txt", loaded.state(), next, BOB, loaded.frontier());
            long appendNanos = System.nanoTime() - appendStart;
            assertEquals(1L, repository.lastScanStatistics().decodedEventFiles());
            assertEquals(1L, repository.lastScanStatistics().indexedEvents());
            assertEquals(0L, repository.lastScanStatistics().readSnapshotFiles());
            long heapDelta = runtime.totalMemory() - runtime.freeMemory() - beforeHeap;
            System.out.printf(java.util.Locale.ROOT, "DoReview archive: events=5000 files=500 cold=%.1fms warm=%.1fms append=%.1fms heapDelta=%d bytes%n", coldNanos / 1_000_000.0, warmNanos / 1_000_000.0, appendNanos / 1_000_000.0, heapDelta);
        }
    }

    public void testSqliteRuntimeFloorRejectsAffectedAndUnknownVersions() {
        assertFalse(SharedHistoryRepository.supportedSqliteVersion("3.51.2"));
        assertTrue(SharedHistoryRepository.supportedSqliteVersion("3.51.3"));
        assertTrue(SharedHistoryRepository.supportedSqliteVersion("3.53.4"));
        assertTrue(SharedHistoryRepository.supportedSqliteVersion("4.0.0"));
        assertFalse(SharedHistoryRepository.supportedSqliteVersion("unknown"));
    }

    public void testConcurrentFirstOpenSerializesSchemaInitializationAndWriters() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CyclicBarrier(2);
        try {
            var first = pool.submit(() -> {
                ready.await();
                try (var repository = new SharedHistoryRepository(root, database)) {
                    repository.publish(null, "alice.txt", null, reviewed("alice.txt", "one\n"), ALICE);
                }
                return true;
            });
            var second = pool.submit(() -> {
                ready.await();
                try (var repository = new SharedHistoryRepository(root, database)) {
                    repository.publish(null, "bob.txt", null, reviewed("bob.txt", "two\n"), BOB);
                }
                return true;
            });
            assertTrue(first.get(30, TimeUnit.SECONDS)); assertTrue(second.get(30, TimeUnit.SECONDS));
            try (var repository = new SharedHistoryRepository(root, database)) {
                assertNotNull(repository.load("alice.txt")); assertNotNull(repository.load("bob.txt"));
            }
        } finally { pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS); }
    }

    public void testOwnPublicationImportsConcurrentExternalEventThroughFallbackScan() throws Exception {
        var armed = new AtomicBoolean();
        final SharedHistoryRepository.Event[] external = {null};
        try (var repository = new SharedHistoryRepository(root, database, () -> {
            if (!armed.get()) return;
            try {
                var event = external[0];
                Path path = root.resolve(".doreview/v1/events/" + event.id.substring(0, 2) + "/" + event.id + ".json");
                Files.createDirectories(path.getParent()); Files.writeString(path, new Gson().toJson(event) + "\n");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        })) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            var event = new SharedHistoryRepository.Event(); event.id = UUID.randomUUID().toString();
            event.fileId = base.fileId(); event.path = "a.txt"; event.aliases.add("a.txt");
            event.parents.addAll(base.frontier()); event.authorName = BOB.name(); event.authorEmail = BOB.email(); event.createdAt = 2;
            var revoked = new ReviewHistoryStore.RevocationState(); revoked.evidenceId = base.state().evidence.getFirst().id;
            revoked.ranges.add(new ReviewHistoryStore.RangeState(0, 4)); event.revocations.add(revoked); external[0] = event;
            armed.set(true);
            repository.publish(null, "b.txt", null, reviewed("b.txt", "two\n"), ALICE);
            assertEquals(1, repository.load("a.txt").state().revocations.size());
            assertEquals(2L, repository.lastScanStatistics().decodedEventFiles());
        }
    }

    public void testOwnPublicationCannotHideConcurrentCurrentFileSnapshotCorruption() throws Exception {
        var armed = new AtomicBoolean();
        String hash = FileSnapshot.create("one\n").id();
        try (var repository = new SharedHistoryRepository(root, database, () -> {
            if (!armed.get()) return;
            try { Files.writeString(root.resolve(".doreview/v1/snapshots/" + hash.substring(0, 2) + "/" + hash + ".txt"), "corruption\n"); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        })) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            var next = SharedHistoryRepository.copyFile(base.state());
            ReviewJournalCodec.write(next, ReviewHistoryEngine.review(ReviewJournalCodec.read(next), "one\n", List.of(new ReviewedRange(0, 4)), 2));
            armed.set(true);
            expectFailure(() -> repository.publish(base.fileId(), "a.txt", base.state(), next, ALICE, base.frontier()), "Snapshot id must match");
            assertNull(repository.load("a.txt"));
        }
    }

    public void testCheckoutRaceRemovesOnlyOwnedEventAndRetainsPendingCommand() throws Exception {
        Path git = Files.createDirectories(root.resolve(".git")); Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        var armed = new AtomicBoolean();
        try (var repository = new SharedHistoryRepository(root, database, () -> {
            if (!armed.get()) return;
            try { Files.writeString(git.resolve("HEAD"), "ref: refs/heads/feature\n"); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        })) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            String token = repository.checkoutToken();
            var next = SharedHistoryRepository.copyFile(base.state());
            ReviewJournalCodec.write(next, ReviewHistoryEngine.review(ReviewJournalCodec.read(next), "two\n", List.of(new ReviewedRange(0, 4)), 2));
            repository.savePending(base.fileId(), "a.txt", next, ALICE, token, base.frontier());
            armed.set(true);
            expectFailure(() -> repository.publish(base.fileId(), "a.txt", base.state(), next, ALICE, base.frontier(), token), "Checkout changed during review publication");
            assertEquals(1, count(root.resolve(".doreview/v1/events"), ".json"));
            assertNotNull(repository.pending(base.fileId(), token));
        }
    }

    public void testRecoverySkipsRetainedOrphanWithoutBlockingOtherFiles() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            String token = repository.checkoutToken();
            repository.savePending(base.fileId(), "a.txt", base.state(), ALICE, token, base.frontier());
            for (Path event : eventFiles(root)) Files.delete(event);
            repository.rescan();
            repository.publish(null, "b.txt", null, reviewed("b.txt", "two\n"), BOB);
            assertEquals(1, repository.flushRecoverablePending(token));
            assertNotNull(repository.load("b.txt"));
            assertTrue(repository.pendingFiles(token).containsKey(base.fileId()));
            expectFailure(repository::rebuildIndex, "Observed review frontier is absent");
        }
    }

    public void testPendingReferencesDeduplicatedCompressedSnapshotsAndSurvivesRestart() throws Exception {
        String text = "pending source text must not be copied into every journal row ".repeat(2000) + "\n";
        var state = reviewed("pending.txt", text);
        String fileId = ReviewRepositoryPaths.sha256("file\npending.txt"), token;
        try (var repository = new SharedHistoryRepository(root, database)) {
            token = repository.checkoutToken();
            repository.savePending(fileId, "pending.txt", state, ALICE, token, Set.of());
            repository.savePending(fileId, "pending.txt", state, ALICE, token, Set.of());
            assertFalse(Files.exists(root.resolve(".doreview")));
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement()) {
                try (var rows = statement.executeQuery("SELECT payload FROM pending")) {
                    rows.next(); String payload = rows.getString(1);
                    var envelope = new Gson().fromJson(payload, com.google.gson.JsonObject.class);
                    assertEquals(2, envelope.get("version").getAsInt());
                    assertEquals(0, envelope.getAsJsonObject("state").getAsJsonObject("snapshots").size());
                    assertEquals(1, envelope.getAsJsonArray("snapshotIds").size());
                    assertTrue(payload.length() < text.length() / 10);
                }
                try (var rows = statement.executeQuery("SELECT count(*) FROM snapshots")) { rows.next(); assertEquals(1, rows.getInt(1)); }
                try (var rows = statement.executeQuery("SELECT encoding,data FROM snapshots")) { rows.next(); assertEquals(1, rows.getInt(1)); assertEquals(0x1f, rows.getBytes(2)[0] & 255); }
            }
        }
        try (var repository = new SharedHistoryRepository(root, database)) {
            var recovered = repository.pending(fileId, token);
            assertEquals(text, recovered.snapshots.values().iterator().next());
            assertEquals(state.evidence.getFirst().id, recovered.evidence.getFirst().id);
            repository.flushPending(fileId, token);
            assertEquals(text, repository.load("pending.txt").state().snapshots.values().iterator().next());
        }
    }

    public void testLegacyPendingEnvelopeWithEmbeddedSnapshotsRemainsReadable() throws Exception {
        var state = reviewed("legacy.txt", "old pending data\n");
        String fileId = "legacy-pending-file";
        try (var repository = new SharedHistoryRepository(root, database)) {
            String token = repository.checkoutToken();
            String payload = new Gson().toJson(Map.of("state", state, "parents", List.of(), "createdAt", 1));
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var insert = sql.prepareStatement("INSERT INTO pending(file_id,path,checkout_token,writer_id,payload,author) VALUES(?,?,?,?,?,?)")) {
                insert.setString(1, fileId); insert.setString(2, "legacy.txt"); insert.setString(3, token);
                insert.setString(4, "legacy-writer"); insert.setString(5, payload); insert.setString(6, new Gson().toJson(SharedHistoryRepository.Author.imported())); insert.executeUpdate();
            }
            var recovered = repository.pending(fileId, token);
            assertEquals("old pending data\n", recovered.snapshots.values().iterator().next());
            repository.flushPending(fileId, token);
            assertEquals(SharedHistoryRepository.Author.imported(), repository.load("legacy.txt").evidenceAuthors().values().iterator().next());
        }
    }

    public void testPendingSnapshotAndMetadataCommitAtomically() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement()) {
                statement.execute("CREATE TRIGGER test_reject_pending BEFORE INSERT ON pending BEGIN SELECT RAISE(ABORT,'injected pending failure'); END");
            }
            expectFailure(() -> repository.savePending("pending-file", "pending.txt", reviewed("pending.txt", "unpublished\n"), ALICE, repository.checkoutToken(), Set.of()), "injected pending failure");
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement()) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM pending")) { rows.next(); assertEquals(0, rows.getInt(1)); }
                try (var rows = statement.executeQuery("SELECT count(*) FROM snapshots")) { rows.next(); assertEquals(0, rows.getInt(1)); }
            }
        }
    }

    public void testRebuildRecoversPendingFromValidPortableSnapshotAfterCachedBlobCorruption() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            var pending = SharedHistoryRepository.copyFile(base.state());
            ReviewJournalCodec.write(pending, ReviewHistoryEngine.review(ReviewJournalCodec.read(pending), "one\n", List.of(new ReviewedRange(0, 4)), 2));
            String newProofId = pending.evidence.getLast().id;
            repository.savePending(base.fileId(), "a.txt", pending, ALICE, repository.checkoutToken(), base.frontier());
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement()) { statement.execute("UPDATE snapshots SET data=X'00010203'"); }
            Path backup = repository.rebuildIndex();
            assertTrue(Files.isRegularFile(backup));
            var restored = repository.load("a.txt");
            assertEquals(2, restored.state().evidence.size());
            assertTrue(restored.state().evidence.stream().anyMatch(proof -> proof.id.equals(newProofId)));
            assertEquals("one\n", repository.snapshot(base.state().evidence.getFirst().snapshotId));
            assertNull(repository.pending(base.fileId(), repository.checkoutToken()));
        }
    }

    public void testPendingRecoveryDoesNotOverwriteUnknownSnapshotEncoding() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var base = repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            repository.savePending(base.fileId(), "a.txt", base.state(), ALICE, repository.checkoutToken(), base.frontier());
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement()) { statement.execute("UPDATE snapshots SET encoding=99"); }
            expectFailure(() -> repository.pending(base.fileId(), repository.checkoutToken()), "Unknown snapshot compression");
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement(); var rows = statement.executeQuery("SELECT encoding FROM snapshots")) { rows.next(); assertEquals(99, rows.getInt(1)); }
            assertTrue(repository.pendingFiles(repository.checkoutToken()).containsKey(base.fileId()));
        }
    }

    public void testDamagedUnpublishedSnapshotRetainsPendingWhenNoPortableRecoveryExists() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            var state = reviewed("pending.txt", "only local pending data\n");
            repository.savePending("pending-file", "pending.txt", state, ALICE, repository.checkoutToken(), Set.of());
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement()) { statement.execute("UPDATE snapshots SET data=X'00010203'"); }
            expectFailure(() -> repository.pending("pending-file", repository.checkoutToken()), "Cannot restore durable pending snapshot");
            assertTrue(repository.pendingFiles(repository.checkoutToken()).containsKey("pending-file"));
            assertFalse(Files.exists(root.resolve(".doreview")));
        }
    }

    public void testPublishingUnrelatedFileDoesNotRewriteExistingEventRows() throws Exception {
        try (var repository = new SharedHistoryRepository(root, database)) {
            repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement()) {
                statement.execute("CREATE TABLE test_write_audit(id TEXT)");
                statement.execute("CREATE TRIGGER test_record_update AFTER UPDATE ON events BEGIN INSERT INTO test_write_audit VALUES(OLD.id); END");
            }
            String original;
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.createStatement(); var rows = statement.executeQuery("SELECT id FROM events")) { rows.next(); original = rows.getString(1); }
            repository.publish(null, "b.txt", null, reviewed("b.txt", "two\n"), BOB);
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = sql.prepareStatement("SELECT count(*) FROM test_write_audit WHERE id=?")) {
                statement.setString(1, original); try (var rows = statement.executeQuery()) { rows.next(); assertEquals(0, rows.getInt(1)); }
            }
        }
    }

    public void testBackupIncludesWalAndUnknownSchemasArePreserved() throws Exception {
        Path backup = temporary.resolve("backups/history.sqlite3");
        try (var repository = new SharedHistoryRepository(root, database)) {
            repository.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE); repository.backup(backup);
        }
        try (var repository = new SharedHistoryRepository(root, backup)) { assertNotNull(repository.load("a.txt")); }
        try (var sql = DriverManager.getConnection("jdbc:sqlite:" + backup); var statement = sql.createStatement()) { statement.execute("PRAGMA user_version=999"); }
        long size = Files.size(backup);
        expectFailure(() -> { try (var ignored = new SharedHistoryRepository(root, backup)) { fail(); } }, "Unsupported DoReview database schema");
        assertEquals(size, Files.size(backup));
    }

    public void testSecondConnectionInvalidatesFirstConnectionCache() throws Exception {
        try (var first = new SharedHistoryRepository(root, database); var second = new SharedHistoryRepository(root, database)) {
            first.publish(null, "a.txt", null, reviewed("a.txt", "one\n"), ALICE);
            second.rescan(); assertNotNull(second.load("a.txt"));
            first.publish(null, "b.txt", null, reviewed("b.txt", "two\n"), BOB);
            assertNotNull(second.load("b.txt"));
        }
    }

    public void testRepositoryPathsFindNestedGitAndKeepWorktreesSeparate() throws Exception {
        Path outer = Files.createDirectories(root.resolve(".git"));
        Path nested = Files.createDirectories(root.resolve("nested")); Files.writeString(nested.resolve(".git"), "gitdir: ../.git/modules/nested\n");
        Path file = Files.writeString(nested.resolve("a.txt"), "one\n");
        assertEquals(nested.toRealPath(), ReviewRepositoryPaths.rootFor(file, root));
        assertFalse(ReviewRepositoryPaths.databasePath(nested).equals(ReviewRepositoryPaths.databasePath(root)));
        assertEquals("a.txt", ReviewRepositoryPaths.relativePath(nested, file));
        expectFailure(() -> ReviewRepositoryPaths.validateRelativePath("../outside"), "Unsafe repository-relative path");
        expectFailure(() -> ReviewRepositoryPaths.validateRelativePath(".doreview/events"), "Unsafe repository-relative path");
    }

    private static ReviewHistoryStore.FileState reviewed(String path, String text) {
        var state = new ReviewHistoryStore.FileState(); state.path = path;
        String normalized = FileSnapshot.normalize(text);
        ReviewJournalCodec.write(state, ReviewHistoryEngine.review(ReviewHistory.empty(), text, List.of(new ReviewedRange(0, normalized.length())), 1));
        return state;
    }
    private static long count(Path directory, String suffix) throws Exception { try (var files = Files.walk(directory)) { return files.filter(path -> path.toString().endsWith(suffix)).count(); } }
    private static List<Path> eventFiles(Path root) throws Exception { try (var files = Files.walk(root.resolve(".doreview/v1/events"))) { return files.filter(path -> path.toString().endsWith(".json")).sorted().toList(); } }
    private static void copyTree(Path source, Path target) throws Exception {
        try (var files = Files.walk(source)) {
            for (Path path : files.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else if (!Files.exists(destination)) Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
    private static void expectFailure(Checked action, String message) throws Exception {
        try { action.run(); fail("Expected failure containing " + message); }
        catch (IllegalArgumentException | IllegalStateException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
