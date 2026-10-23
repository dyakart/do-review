package dev.doreview.ide;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.configurationStore.ApplicationStateStorageManager;
import com.intellij.configurationStore.FileBasedStorage;
import com.intellij.configurationStore.Macro;
import com.intellij.configurationStore.XmlElementStorage;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.StateStorageChooserEx;
import com.intellij.openapi.components.StateStorageOperation;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.xmlb.XmlSerializer;
import dev.doreview.core.EvidenceRef;
import dev.doreview.core.ContextMap;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;
import org.jdom.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Storage tests use temporary projects and backups; they never write the user's IDE config. */
public final class ReviewHistoryStoreTest extends BasePlatformTestCase {
    private ReviewHistoryStore store;
    private Path backupRoot;

    @Override protected CodeInsightTestFixture createMyFixture() {
        IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture projectFixture = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(projectFixture, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(projectFixture.getProject().getBasePath()); }
        });
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        backupRoot = Path.of(getProject().getBasePath(), "test-config", "legacy-backups");
        store = new ReviewHistoryStore(backupRoot);
    }

    public void testReviewJournalRoundTripsSnapshotsEvidenceAndExplicitRevocations() {
        String text = "one\ntwo\nthree\n";
        var evidence = store.mark(getProject(), "a.txt", text, 0, text.length());
        store.revoke(getProject(), "a.txt", List.of(new EvidenceRef(evidence.id(), 4, 8)));
        ReviewHistory expected = store.history(getProject(), "a.txt");

        ReviewHistoryStore reopened = new ReviewHistoryStore(backupRoot);
        reopened.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()),
                ReviewHistoryStore.StoreState.class));

        assertEquals(expected, reopened.history(getProject(), "a.txt"));
        assertEquals(text, expected.snapshots().get(evidence.snapshotId()).text());
        assertEquals(List.of(new ReviewedRange(4, 8)), expected.revocations().getFirst().ranges());
        assertEquals(List.of(new ReviewedRange(0, text.length())), expected.evidence().getFirst().ranges());
    }

    public void testReadingDifferentTextCannotEraseHistoricalVersions() {
        store.mark(getProject(), "a.txt", "version A\n", 0, 10);
        store.mark(getProject(), "a.txt", "version B\n", 0, 10);

        ReviewHistory history = store.history(getProject(), "a.txt");
        assertEquals(2, history.evidence().size());
        assertEquals(2, history.snapshots().size());
        assertEquals(history, store.history(getProject(), "a.txt"));
        assertEmpty(store.history(getProject(), "identical-copy.txt").evidence());
    }

    public void testBlankReviewNoOp() {
        long modificationCount = store.getStateModificationCount();
        assertNull(store.mark(getProject(), "blank.txt", " \t\n\n", 0, 4));
        assertEquals(ReviewHistory.empty(), store.history(getProject(), "blank.txt"));
        assertEquals(modificationCount, store.getStateModificationCount());
        assertTrue("A blank-only review must not create project records or aliases", store.getState().projects.isEmpty());

        String text = "reviewed\n \t\n";
        store.mark(getProject(), "mixed.txt", text, 0, text.length());
        ReviewHistory before = store.history(getProject(), "mixed.txt");
        modificationCount = store.getStateModificationCount();
        assertEquals(List.of(new ReviewedRange(0, 9)), before.evidence().getFirst().ranges());
        assertNull(store.mark(getProject(), "mixed.txt", text, 9, text.length()));
        assertEquals(before, store.history(getProject(), "mixed.txt"));
        assertEquals(modificationCount, store.getStateModificationCount());

        String indented = " \tcode\n";
        var partial = store.mark(getProject(), "indented.txt", indented, 0, 2);
        assertNotNull("Whitespace selected within a nonblank row still reviews that row", partial);
        assertEquals(List.of(new ReviewedRange(0, indented.length())), partial.ranges());
    }

    public void testClearFileRevokesEverySavedVariantAndNewReviewCanBeExplicitlyAdded() {
        var first = store.mark(getProject(), "a.txt", "version A\n", 0, 10);
        var second = store.mark(getProject(), "a.txt", "version B\n", 0, 10);

        store.clearFile(getProject(), "a.txt");

        ReviewHistory cleared = store.history(getProject(), "a.txt");
        assertEquals(2, cleared.evidence().size());
        assertTrue(cleared.revocations().stream().anyMatch(revoke -> revoke.evidenceId().equals(first.id())));
        assertTrue(cleared.revocations().stream().anyMatch(revoke -> revoke.evidenceId().equals(second.id())));
        var fresh = store.mark(getProject(), "a.txt", "version A\n", 0, 10);
        assertTrue(fresh.sequence() > cleared.revocations().stream().mapToLong(revoke -> revoke.sequence()).max().orElseThrow());
        assertFalse(cleared.revocations().stream().anyMatch(revoke -> revoke.evidenceId().equals(fresh.id())));
    }

    public void testMigratedPartialRangesRemainPartialAndOldSnapshotIsPreserved() {
        var result = store.importLegacy(getProject(), Map.of("a.txt",
                new ReviewHistoryStore.LegacyFile("reviewed text\n", List.of(new ReviewedRange(2, 8)))));

        assertEquals(1, result.importedFiles());
        var evidence = store.history(getProject(), "a.txt").evidence().getFirst();
        assertEquals(List.of(new ReviewedRange(2, 8)), evidence.ranges());
        assertEquals(0, evidence.createdAt());
        assertEquals("reviewed text\n", store.history(getProject(), "a.txt").snapshots().get(evidence.snapshotId()).text());
    }

    public void testLegacyImportIsNotReplayedAfterManualRevokeOrRestart() {
        Map<String, ReviewHistoryStore.LegacyFile> old = Map.of("a.txt",
                new ReviewHistoryStore.LegacyFile("reviewed\n", List.of(new ReviewedRange(0, 9))));
        store.importLegacy(getProject(), old);
        store.clearFile(getProject(), "a.txt");
        ReviewHistory revoked = store.history(getProject(), "a.txt");
        ReviewHistoryStore reopened = new ReviewHistoryStore(backupRoot);
        reopened.loadState(store.getState());

        assertEquals(0, reopened.importLegacy(getProject(), old).importedFiles());
        assertEquals(revoked, reopened.history(getProject(), "a.txt"));
        assertEquals(0, reopened.importLegacy(getProject(), Map.of("a.txt",
                new ReviewHistoryStore.LegacyFile("another version\n", List.of(new ReviewedRange(0, 16)))))
                .importedFiles());
        assertEquals(revoked, reopened.history(getProject(), "a.txt"));
    }

    public void testRawLegacyXmlIsBackedUpWithoutChangingSourceAndImportedBeforeCurrentFile() throws Exception {
        Path current = Path.of(getProject().getBasePath(), "a.txt");
        Files.writeString(current, "branch B\n");
        ReviewHistoryStore.LegacyState old = new ReviewHistoryStore.LegacyState();
        ReviewHistoryStore.LegacyFileState file = new ReviewHistoryStore.LegacyFileState();
        file.snapshot = "branch A\n";
        file.ranges.add(new ReviewHistoryStore.RangeState(0, 9));
        old.files.put("a.txt", file);
        Element component = XmlSerializer.serialize(old);
        component.setName("component");
        component.setAttribute("name", "ReviewMarks");
        Element root = new Element("project").setAttribute("version", "4").addContent(component);
        byte[] raw = JDOMUtil.writeElement(root).getBytes(StandardCharsets.UTF_8);
        Path source = legacyPath();
        Files.createDirectories(source.getParent());
        Files.write(source, raw);

        var result = store.importLegacyFromProject(getProject());

        assertEmpty(result.warnings());
        assertEquals(1, result.importedFiles());
        assertTrue(Arrays.equals(raw, Files.readAllBytes(source)));
        try (var backups = Files.list(backupRoot)) {
            List<Path> paths = backups.toList();
            assertEquals(1, paths.size());
            assertTrue(Arrays.equals(raw, Files.readAllBytes(paths.getFirst())));
        }
        var evidence = store.history(getProject(), "a.txt").evidence().getFirst();
        assertEquals("branch A\n", store.history(getProject(), "a.txt").snapshots().get(evidence.snapshotId()).text());
        assertEquals("branch B\n", Files.readString(current));
    }

    public void testInvalidLegacyEntryIsSkippedWithDiagnosticWhileValidEntriesImport() {
        var result = store.importLegacy(getProject(), Map.of(
                "valid.txt", new ReviewHistoryStore.LegacyFile("okay\n", List.of(new ReviewedRange(0, 5))),
                "invalid.txt", new ReviewHistoryStore.LegacyFile("short", List.of(new ReviewedRange(0, 40))),
                "../outside.txt", new ReviewHistoryStore.LegacyFile("outside", List.of(new ReviewedRange(0, 7)))));

        assertEquals(1, result.importedFiles());
        assertEquals(2, result.warnings().size());
        assertEquals(result.warnings(), store.diagnostics(getProject()));
        assertEquals(1, store.history(getProject(), "valid.txt").evidence().size());
        assertEmpty(store.history(getProject(), "invalid.txt").evidence());
    }

    public void testMalformedXmlIsBackedUpAndNotMarkedAsSuccessfullyImported() throws Exception {
        Path source = legacyPath();
        Files.createDirectories(source.getParent());
        Files.writeString(source, "<not-valid-xml");

        assertFalse(store.importLegacyFromProject(getProject()).warnings().isEmpty());
        assertFalse(store.getState().projects.values().iterator().next().legacyImported);
        assertTrue(Files.isDirectory(backupRoot));
    }

    public void testRenameAliasRestoresOldBranchButCoexistingCopyDoesNotInherit() throws Exception {
        Path base = Path.of(getProject().getBasePath());
        Path oldPath = base.resolve("old.txt");
        Path newPath = base.resolve("new.txt");
        Files.writeString(oldPath, "reviewed\n");
        store.mark(getProject(), "old.txt", "reviewed\n", 0, 9);
        ReviewHistory expected = store.history(getProject(), "old.txt");
        Files.move(oldPath, newPath);
        assertTrue(store.movePath(getProject(), "old.txt", "new.txt"));
        assertEquals(expected, store.history(getProject(), "new.txt"));
        Files.move(newPath, oldPath);
        assertEquals(expected, store.history(getProject(), "old.txt"));
        assertEquals(List.of("old.txt"), store.paths(getProject()));

        Files.copy(oldPath, newPath);

        assertEmpty(store.history(getProject(), "old.txt").evidence());
        assertEquals(expected, store.history(getProject(), "new.txt"));
        store.mark(getProject(), "old.txt", "reviewed\n", 0, 9);
        assertEquals(1, store.history(getProject(), "old.txt").evidence().size());
        assertEquals(expected, store.history(getProject(), "new.txt"));
    }

    public void testRenameCollisionDoesNotMergeIndependentHistory() {
        store.mark(getProject(), "old.txt", "old\n", 0, 4);
        store.mark(getProject(), "new.txt", "new\n", 0, 4);
        ReviewHistory old = store.history(getProject(), "old.txt");
        ReviewHistory target = store.history(getProject(), "new.txt");

        assertFalse(store.movePath(getProject(), "old.txt", "new.txt"));

        assertEquals(old, store.history(getProject(), "old.txt"));
        assertEquals(target, store.history(getProject(), "new.txt"));
        assertFalse(store.diagnostics(getProject()).isEmpty());
    }

    public void testReturnedAndLoadedStateDoNotExposeLiveMutableBeans() {
        store.mark(getProject(), "a.txt", "reviewed\n", 0, 9);
        ReviewHistory expected = store.history(getProject(), "a.txt");
        var saved = store.getState();
        store.loadState(saved);
        saved.projects.clear();
        assertEquals(expected, store.history(getProject(), "a.txt"));
        var returned = store.getState();
        returned.projects.clear();
        assertEquals(expected, store.history(getProject(), "a.txt"));
    }

    public void testDamagedHistoryIsQuarantinedWithoutErasingItOrBreakingOtherFiles() {
        store.mark(getProject(), "damaged.txt", "bad\n", 0, 4);
        store.mark(getProject(), "valid.txt", "good\n", 0, 5);
        var damaged = store.getState();
        var project = damaged.projects.values().iterator().next();
        String damagedId = project.aliases.get("damaged.txt");
        project.files.get(damagedId).snapshots.replaceAll((id, text) -> "tampered\n");

        store.loadState(damaged);

        assertEmpty(store.history(getProject(), "damaged.txt").evidence());
        assertEquals(1, store.history(getProject(), "valid.txt").evidence().size());
        assertFalse(store.diagnostics(getProject()).isEmpty());
        assertTrue(store.getState().projects.values().iterator().next().files.containsKey(damagedId));
        store.mark(getProject(), "damaged.txt", "fresh\n", 0, 6);
        assertEquals(1, store.history(getProject(), "damaged.txt").evidence().size());
        assertTrue("Explicit re-review may fork, but cannot erase quarantined evidence",
                store.getState().projects.values().iterator().next().files.containsKey(damagedId));
    }

    public void testFutureSchemaIsReadOnlyAndCannotBeOverwrittenByThisVersion() {
        var future = new ReviewHistoryStore.StoreState();
        future.schemaVersion = 999;
        store.loadState(future);

        assertEmpty(store.history(getProject(), "a.txt").evidence());
        assertNull("Unknown schema must not be exposed as this version's state bean", store.getState());
        var storage = ReviewHistoryStore.class.getAnnotation(State.class).storages()[0];
        assertEquals("Storage writes must be skipped; null getState alone could clear the component",
                StateStorageChooserEx.Resolution.SKIP, store.getResolution(storage, StateStorageOperation.WRITE));
        assertEquals(StateStorageChooserEx.Resolution.DO, store.getResolution(storage, StateStorageOperation.READ));
        assertFalse(store.diagnostics(getProject()).isEmpty());
        try {
            store.mark(getProject(), "a.txt", "reviewed\n", 0, 9);
            fail("Unsupported history must stay read-only");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("unsupported schema"));
        }
    }

    public void testLegacyFileAppearingAfterInitializationCannotBypassManualRevocation() throws Exception {
        assertEquals(0, store.importLegacyFromProject(getProject()).importedFiles());
        store.mark(getProject(), "a.txt", "reviewed\n", 0, 9);
        store.clearFile(getProject(), "a.txt");
        ReviewHistory revoked = store.history(getProject(), "a.txt");
        var legacy = new ReviewHistoryStore.LegacyState();
        var file = new ReviewHistoryStore.LegacyFileState();
        file.snapshot = "reviewed\n";
        file.ranges.add(new ReviewHistoryStore.RangeState(0, 9));
        legacy.files.put("a.txt", file);
        Element component = XmlSerializer.serialize(legacy);
        component.setName("component"); component.setAttribute("name", "ReviewMarks");
        Path source = legacyPath();
        Files.createDirectories(source.getParent());
        Files.writeString(source, JDOMUtil.writeElement(new Element("project").addContent(component)));
        store.loadState(store.getState());

        assertEquals(0, store.importLegacyFromProject(getProject()).importedFiles());
        assertEquals(revoked, store.history(getProject(), "a.txt"));
    }

    public void testCheckpointRoundTripsEveryIdentityCollectionWithoutChangingJournal() {
        String baseline = "a\nb\nc\nd\ne\n", current = "a\nchanged\ninserted\nd\n";
        var proof = store.mark(getProject(), "a.txt", baseline, 0, baseline.length());
        var a = new EvidenceRef(proof.id(), 0, 2); var b = new EvidenceRef(proof.id(), 2, 4);
        var c = new EvidenceRef(proof.id(), 4, 6); var d = new EvidenceRef(proof.id(), 6, 8); var e = new EvidenceRef(proof.id(), 8, 10);
        var hints = new ProjectionHints(Map.of(a, new ReviewedRange(0, 2), b, new ReviewedRange(2, 10)),
                Set.of(a, b, c, d, e), Set.of(c),
                Map.of(c, new ProjectionHints.DeletedAnchor(10, 0, 0, "c\n", "altered\n")),
                Map.of(a, "a\n", b, "b\n", c, "c\n", d, "d\n", e, "e\n"),
                Map.of(d, new ProjectionHints.UnresolvedPosition(new ReviewedRange(10, 21), FileProjection.Reason.AMBIGUOUS,
                        "d\ne\n", 0, 2, List.of(new ReviewedRange(10, 19)))));
        var history = store.history(getProject(), "a.txt");
        store.checkpoint(getProject(), "a.txt", current, hints);
        var reopened = new ReviewHistoryStore(backupRoot);
        reopened.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class));

        assertEquals(hints.compactForCheckpoint(current), reopened.checkpoint(getProject(), "a.txt", current));
        assertEquals(history, reopened.history(getProject(), "a.txt"));
        assertEquals(history.revision(), reopened.history(getProject(), "a.txt").revision());
        assertEquals("Only deleted, unresolved or otherwise unlocated origins restrict an unseen revision", Set.of(c, d, e),
                reopened.checkpoint(getProject(), "a.txt", current + "other\n").authoritativeRefs());
        assertEmpty(reopened.checkpoint(getProject(), "a.txt", current + "other\n").livePositions().keySet());
        assertEmpty(reopened.checkpoint(getProject(), "copy.txt", current).authoritativeRefs());
    }

    public void testIdleUniqueRowsPersistOnlyAnEmptyCleanMarker() {
        String text = "one\ntwo\n";
        var proof = store.mark(getProject(), "a.txt", text, 0, text.length());
        var one = new EvidenceRef(proof.id(), 0, 4); var two = new EvidenceRef(proof.id(), 4, 8);
        var hints = new ProjectionHints(Map.of(one, new ReviewedRange(0, 4), two, new ReviewedRange(4, 8)),
                Set.of(one, two), Set.of(), Map.of(), Map.of(one, "one\n", two, "two\n"), Map.of());

        store.checkpoint(getProject(), "a.txt", text, hints);

        assertEquals(1, fileState(store.getState(), "a.txt").checkpoints.size());
        assertEmpty(fileState(store.getState(), "a.txt").checkpoints.values().iterator().next().payload.refs);
        assertEmpty(store.checkpoint(getProject(), "a.txt", text).authoritativeRefs());
    }

    public void testUnseenOfflineContentCannotRecoverKnownLostOriginAndCleanMarkerCanReplaceIt() {
        String baseline = "same\n", copied = "prefix\nsame\n";
        var proof = store.mark(getProject(), "a.txt", baseline, 0, baseline.length());
        var ref = new EvidenceRef(proof.id(), 0, baseline.length());
        var lost = new ProjectionHints(Map.of(), Set.of(ref));
        var history = store.history(getProject(), "a.txt");
        store.checkpoint(getProject(), "a.txt", copied, lost);
        store.loadState(store.getState());

        var unseen = store.checkpoint(getProject(), "a.txt", copied + "offline\n");
        assertEquals(Set.of(ref), unseen.authoritativeRefs());
        assertEmpty(unseen.livePositions().keySet()); assertEmpty(unseen.deletedAnchors().keySet());
        store.checkpoint(getProject(), "a.txt", copied, ProjectionHints.empty());
        assertTrue("An explicit exact-hash clean marker replaces stale restrictions",
                store.checkpoint(getProject(), "a.txt", copied).authoritativeRefs().isEmpty());
        assertEquals(history, store.history(getProject(), "a.txt"));
    }

    public void testUnseenOfflineRevisionRematchesKnownChangedMessageRow() {
        assertKnownChangedMessageCanRematchUnseenRevision(false);
    }

    public void testUnseenOfflineRevisionRematchesPendingChangedMessageRow() {
        assertKnownChangedMessageCanRematchUnseenRevision(true);
    }

    public void testUnseenOfflineRevisionRematchesSameLengthChangedMessageRow() {
        assertKnownChangedMessageCanRematchUnseenRevision(false, "waiting");
    }

    private void assertKnownChangedMessageCanRematchUnseenRevision(boolean pending) {
        assertKnownChangedMessageCanRematchUnseenRevision(pending, "in progress");
    }

    private void assertKnownChangedMessageCanRematchUnseenRevision(boolean pending, String replacement) {
        String baseline = "fun check(flag: Bool) {\n"
                + "    require(\n"
                + "        flag,\n"
                + "        \"LP exchange request pending\"\n"
                + "    );\n"
                + "}\n"
                + "fun other(): Int { return 1; }\n";
        int start = baseline.indexOf("        \"LP exchange"), end = baseline.indexOf('\n', start) + 1;
        var proof = store.mark(getProject(), "message.tact", baseline, 0, baseline.length());
        var ref = new EvidenceRef(proof.id(), start, end);
        var history = store.history(getProject(), "message.tact");
        var initial = ProjectionHints.fromProjection(history,
                diagnosticProjection(history, baseline, ProjectionHints.empty()));
        String changed = baseline.replace("request pending", "request " + replacement);
        var changedHints = initial.applyEdit(baseline, changed, baseline.indexOf("pending"),
                "pending".length(), replacement.length());
        assertTrue("The edited message retains a known row identity", changedHints.livePositions().containsKey(ref));
        assertFalse(changedHints.deletedRefs().contains(ref));
        assertFalse(changedHints.unresolvedPositions().containsKey(ref));
        assertEquals(FileProjection.Status.CHANGED, diagnosticProjection(history, changed, changedHints).rows().get(3).status());
        if (pending) store.checkpointPending(getProject(), "message.tact", changed, changedHints);
        else store.checkpoint(getProject(), "message.tact", changed, changedHints);

        String offline = baseline.replace("return 1;", "return 2;");
        assertUnseenMessageRemainsReviewed(store, history, offline, ref);
        var reopened = new ReviewHistoryStore(backupRoot);
        reopened.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class));
        assertTrue("The exact older checkpoint still retains its changed-row locator",
                reopened.checkpoint(getProject(), "message.tact", changed).livePositions().containsKey(ref));
        assertUnseenMessageRemainsReviewed(reopened, history, offline, ref);
    }

    private void assertUnseenMessageRemainsReviewed(ReviewHistoryStore source, ReviewHistory history,
                                                   String offline, EvidenceRef ref) {
        var cold = source.checkpoint(getProject(), "message.tact", offline);
        assertFalse("A known live locator must not become a lost-origin denial", cold.authoritativeRefs().contains(ref));
        assertEmpty(cold.livePositions().keySet());
        var projection = diagnosticProjection(history, offline, cold);
        assertEquals(FileProjection.Status.REVIEWED, projection.rows().get(3).status());
        assertEquals(FileProjection.Reason.EXACT, projection.rows().get(3).reason());
        assertTrue(projection.rows().get(3).revocableRefs().contains(ref));
        assertEquals("The offline version has a real unrelated edit", FileProjection.Status.CHANGED,
                projection.rows().get(6).status());
        assertEquals(history, source.history(getProject(), "message.tact"));
    }

    public void testUnseenOfflineRevisionCannotReviewAnInsertedCopyAfterOriginalDisappears() {
        String baseline = "head\nsame\ntail\n";
        int start = baseline.indexOf("same"), end = baseline.indexOf("tail");
        var proof = store.mark(getProject(), "copy.txt", baseline, start, end);
        var ref = new EvidenceRef(proof.id(), start, end);
        var history = store.history(getProject(), "copy.txt");
        String copied = baseline.substring(0, start) + "same\n" + baseline.substring(start);
        var hints = ProjectionHints.fromProjection(history,
                diagnosticProjection(history, baseline, ProjectionHints.empty()))
                .applyEdit(baseline, copied, start, 0, "same\n".length());
        var originalPosition = new ReviewedRange(start + "same\n".length(), end + "same\n".length());
        assertEquals(originalPosition, hints.livePositions().get(ref));
        var beforeReload = diagnosticProjection(history, copied, hints);
        assertEquals(FileProjection.Status.UNREVIEWED, beforeReload.rows().get(1).status());
        assertEquals(FileProjection.Status.REVIEWED, beforeReload.rows().get(2).status());
        store.checkpoint(getProject(), "copy.txt", copied, hints);
        // The external change removes the tracked original, leaving only its previously new copy.
        String offline = copied.substring(0, originalPosition.start()) + copied.substring(originalPosition.end()) + "offline\n";

        assertUnseenDuplicateCannotInheritReview(store, history, offline, ref);
        var reopened = new ReviewHistoryStore(backupRoot);
        reopened.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class));
        assertUnseenDuplicateCannotInheritReview(reopened, history, offline, ref);
    }

    public void testUnseenOfflineRevisionCannotReviewCopyOfAnEditedOriginal() {
        String baseline = "head\nsame\ntail\n";
        int start = baseline.indexOf("same"), end = baseline.indexOf("tail");
        var proof = store.mark(getProject(), "copy.txt", baseline, start, end);
        var ref = new EvidenceRef(proof.id(), start, end);
        var history = store.history(getProject(), "copy.txt");
        var initial = ProjectionHints.fromProjection(history,
                diagnosticProjection(history, baseline, ProjectionHints.empty()));
        String changed = baseline.replace("same", "changed original");
        var changedHints = initial.applyEdit(baseline, changed, start, "same".length(), "changed original".length());
        String copied = changed.substring(0, start) + "same\n" + changed.substring(start);
        var hints = changedHints.applyEdit(changed, copied, start, 0, "same\n".length());
        var originalPosition = hints.livePositions().get(ref);
        assertNotNull(originalPosition);
        assertEquals("The tracked original really changed", "changed original\n",
                copied.substring(originalPosition.start(), originalPosition.end()));
        var beforeReload = diagnosticProjection(history, copied, hints);
        assertEquals(FileProjection.Status.UNREVIEWED, beforeReload.rows().get(1).status());
        assertEquals(FileProjection.Status.CHANGED, beforeReload.rows().get(2).status());
        store.checkpoint(getProject(), "copy.txt", copied, hints);
        String offline = copied.substring(0, originalPosition.start()) + copied.substring(originalPosition.end()) + "offline\n";

        assertUnseenDuplicateCannotInheritReview(store, history, offline, ref);
        var reopened = new ReviewHistoryStore(backupRoot);
        reopened.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class));
        assertUnseenDuplicateCannotInheritReview(reopened, history, offline, ref);
    }

    private void assertUnseenDuplicateCannotInheritReview(ReviewHistoryStore source, ReviewHistory history,
                                                          String offline, EvidenceRef ref) {
        var cold = source.checkpoint(getProject(), "copy.txt", offline);
        assertTrue("A known original/copy distinction must survive an unseen revision", cold.authoritativeRefs().contains(ref));
        var projection = diagnosticProjection(history, offline, cold);
        assertEquals(FileProjection.Status.NEEDS_REVIEW, projection.rows().get(1).status());
        assertEquals(FileProjection.Reason.AMBIGUOUS, projection.rows().get(1).reason());
        assertEmpty(projection.rows().get(1).revocableRefs());
        assertEquals(history, source.history(getProject(), "copy.txt"));
    }

    public void testUnseenOfflineRevisionRetainsDeletedMessageOriginRestriction() {
        String baseline = "head\n\"LP exchange request pending\"\ntail\n";
        int start = baseline.indexOf('"'), end = baseline.indexOf("tail");
        var proof = store.mark(getProject(), "message.tact", baseline, start, end);
        var ref = new EvidenceRef(proof.id(), start, end);
        var history = store.history(getProject(), "message.tact");
        String deleted = baseline.substring(0, start) + baseline.substring(end);
        var hints = ProjectionHints.fromProjection(history,
                diagnosticProjection(history, baseline, ProjectionHints.empty()))
                .applyEdit(baseline, deleted, start, end - start, 0);
        assertTrue("The original reviewed message was actually deleted", hints.deletedRefs().contains(ref));
        store.checkpoint(getProject(), "message.tact", deleted, hints);
        String offline = "head\nnew\n\"LP exchange request pending\"\ntail\noffline\n";

        assertUnseenMessageOriginStaysRestricted(store, history, offline, ref);
        var reopened = new ReviewHistoryStore(backupRoot);
        reopened.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class));
        assertUnseenMessageOriginStaysRestricted(reopened, history, offline, ref);
    }

    public void testUnseenOfflineRevisionRetainsSplitMessageOriginRestriction() {
        String baseline = "head\n\"LP exchange request pending\"\ntail\n";
        int start = baseline.indexOf('"'), end = baseline.indexOf("tail"), splitAt = baseline.indexOf("request");
        var proof = store.mark(getProject(), "message.tact", baseline, start, end);
        var ref = new EvidenceRef(proof.id(), start, end);
        var history = store.history(getProject(), "message.tact");
        String split = baseline.substring(0, splitAt) + "\n" + baseline.substring(splitAt);
        var hints = ProjectionHints.fromProjection(history,
                diagnosticProjection(history, baseline, ProjectionHints.empty()))
                .applyEdit(baseline, split, splitAt, 0, 1);
        assertFalse(hints.livePositions().containsKey(ref));
        assertNotNull("The local split records the actual source hunk", hints.unresolvedPositions().get(ref).originalText());
        store.checkpoint(getProject(), "message.tact", split, hints);
        String offline = "head\nbroken prefix\n\"LP exchange request pending\"\nbroken suffix\ntail\noffline\n";

        assertUnseenMessageOriginStaysRestricted(store, history, offline, ref);
        var reopened = new ReviewHistoryStore(backupRoot);
        reopened.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class));
        assertUnseenMessageOriginStaysRestricted(reopened, history, offline, ref);
    }

    private void assertUnseenMessageOriginStaysRestricted(ReviewHistoryStore source, ReviewHistory history,
                                                         String offline, EvidenceRef ref) {
        var cold = source.checkpoint(getProject(), "message.tact", offline);
        assertTrue("A proven lost origin must still restrict an unseen revision", cold.authoritativeRefs().contains(ref));
        assertEmpty(cold.livePositions().keySet());
        var projection = diagnosticProjection(history, offline, cold);
        assertEquals(FileProjection.Status.NEEDS_REVIEW, projection.rows().get(2).status());
        assertEquals(FileProjection.Reason.AMBIGUOUS, projection.rows().get(2).reason());
        assertEmpty(projection.rows().get(2).revocableRefs());
        assertEquals("An unseen revision cannot invent restorable deletion coordinates", 0, projection.deletedCount());
        assertEquals(history, source.history(getProject(), "message.tact"));
    }

    public void testReReviewedRowKeepsChangedDiagnosticAfterColdUnknownHashReload() {
        String baseline = "head\nvalue = 0;\ntail\n";
        int start = baseline.indexOf("value"), end = baseline.indexOf("tail");
        var oldProof = store.mark(getProject(), "a.txt", baseline, 0, baseline.length());
        store.revoke(getProject(), "a.txt", List.of(new EvidenceRef(oldProof.id(), start, end)));
        var newProof = store.mark(getProject(), "a.txt", baseline, start, end);
        var active = new EvidenceRef(newProof.id(), start, end);
        var history = store.history(getProject(), "a.txt");
        String deleted = baseline.substring(0, start) + baseline.substring(end);
        var hints = ProjectionHints.fromProjection(history, diagnosticProjection(history, baseline, ProjectionHints.empty()))
                .applyEdit(baseline, deleted, start, end - start, 0).compactForCheckpoint(deleted, history);
        store.checkpoint(getProject(), "a.txt", deleted, hints);
        store.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class));

        String external = baseline.replace("value = 0;", "value = 7;");
        var cold = store.checkpoint(getProject(), "a.txt", external);
        var projected = diagnosticProjection(history, external, cold);

        assertEquals(FileProjection.Status.CHANGED, projected.rows().get(1).status());
        assertEquals(List.of(new ReviewedRange(start, end)), projected.changedRanges());
        assertEquals(List.of(active), projected.rows().get(1).evidenceRefs());
        assertTrue("Cold diagnostics cannot manufacture identity for clear or later refresh", projected.rows().get(1).revocableRefs().isEmpty());
        assertEquals(0, projected.deletedCount());
        assertEquals(FileProjection.Status.REVIEWED, projected.rows().get(0).status());
        assertEquals(FileProjection.Status.REVIEWED, projected.rows().get(2).status());
        var refreshed = cold.mergeResolved(ProjectionHints.fromProjection(history, projected));
        assertFalse(refreshed.livePositions().containsKey(active));
        store.checkpoint(getProject(), "a.txt", external, refreshed);
        store.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class));
        assertEquals(FileProjection.Status.CHANGED,
                diagnosticProjection(history, external, store.checkpoint(getProject(), "a.txt", external)).rows().get(1).status());
        assertEquals("Projection and checkpoint persistence cannot erase or rewrite review history", history, store.history(getProject(), "a.txt"));
    }

    public void testLostOriginCopyGetsOnlyYellowDiagnosticAfterUnknownHashAndRestart() {
        String baseline = "head\nchecked();\ntail\n";
        int start = baseline.indexOf("checked"), end = baseline.indexOf("tail");
        var proof = store.mark(getProject(), "a.txt", baseline, start, end);
        var ref = new EvidenceRef(proof.id(), start, end);
        var history = store.history(getProject(), "a.txt");
        String deleted = "head\ntail\n";
        var hints = ProjectionHints.fromProjection(history, diagnosticProjection(history, baseline, ProjectionHints.empty()))
                .applyEdit(baseline, deleted, start, end - start, 0);
        String copied = "head\nnew\nchecked();\ntail\n";
        hints = hints.applyEdit(deleted, copied, start, 0, "new\nchecked();\n".length());
        store.checkpoint(getProject(), "a.txt", copied, hints);
        store.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class));

        String external = copied + "offline\n";
        var cold = store.checkpoint(getProject(), "a.txt", external);
        var projection = diagnosticProjection(history, external, cold);
        var row = projection.rows().get(2);
        assertEquals(FileProjection.Status.NEEDS_REVIEW, row.status());
        assertEquals(FileProjection.Reason.AMBIGUOUS, row.reason());
        assertEquals(List.of(ref), row.evidenceRefs());
        assertEmpty(row.revocableRefs());
        assertEquals("Cold guessed deletion coordinates must never be persisted as restorable anchors", 0, projection.deletedCount());
        var refreshed = cold.mergeResolved(ProjectionHints.fromProjection(history, projection));
        assertFalse(refreshed.livePositions().containsKey(ref));
        assertNull(refreshed.unresolvedPositions().get(ref).originalText());
        store.checkpoint(getProject(), "a.txt", external, refreshed);
        store.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class));
        assertEquals(FileProjection.Status.NEEDS_REVIEW,
                diagnosticProjection(history, external, store.checkpoint(getProject(), "a.txt", external)).rows().get(2).status());
        store.revoke(getProject(), "a.txt", row.revocableRefs());
        assertEquals("A possible copied-row diagnostic cannot revoke the original historical proof", history, store.history(getProject(), "a.txt"));
    }

    private static FileProjection diagnosticProjection(ReviewHistory history, String text, ProjectionHints hints) {
        var contexts = new java.util.HashMap<String, ContextMap>();
        history.snapshots().forEach((id, snapshot) -> contexts.put(id, stableContext(snapshot.text())));
        return ReviewHistoryEngine.project(history, text, contexts, stableContext(text), hints);
    }

    private static ContextMap stableContext(String text) {
        return new ContextMap("test:stable", java.util.Collections.nCopies(text.split("\n", -1).length, "same"));
    }

    public void testCheckpointChecksumRejectsInBoundsTamperingAndPreservesHistory() {
        String baseline = "same\n", current = "same\nsame\n";
        var proof = store.mark(getProject(), "a.txt", baseline, 0, baseline.length());
        var ref = new EvidenceRef(proof.id(), 0, 5);
        var hints = new ProjectionHints(Map.of(ref, new ReviewedRange(5, 10)), Set.of(ref), Set.of(), Map.of(), Map.of(ref, baseline), Map.of());
        store.checkpoint(getProject(), "a.txt", current, hints);
        var history = store.history(getProject(), "a.txt");
        var damaged = store.getState();
        var checkpoint = fileState(damaged, "a.txt").checkpoints.values().iterator().next();
        checkpoint.payload.refs.getFirst().live = new ReviewHistoryStore.RangeState(0, 5);
        store.loadState(damaged);

        var recovered = store.checkpoint(getProject(), "a.txt", current);

        assertEquals(Set.of(ref), recovered.authoritativeRefs());
        assertEmpty(recovered.livePositions().keySet());
        assertEquals(history, store.history(getProject(), "a.txt"));
        assertFalse(store.diagnostics(getProject()).isEmpty());
        assertEquals(0, fileState(store.getState(), "a.txt").checkpoints.values().iterator().next().payload.refs.getFirst().live.start);
    }

    public void testCheckpointValidatesSignedBoundsBaselineAndEvidenceReferences() {
        String baseline = "same\n", current = "same\nsame\n";
        var proof = store.mark(getProject(), "a.txt", baseline, 0, baseline.length());
        var ref = new EvidenceRef(proof.id(), 0, 5);
        store.checkpoint(getProject(), "a.txt", current,
                new ProjectionHints(Map.of(ref, new ReviewedRange(5, 10)), Set.of(ref), Set.of(), Map.of(), Map.of(ref, baseline), Map.of()));
        var clean = store.getState();
        for (int variant = 0; variant < 4; variant++) {
            var damaged = XmlSerializer.deserialize(XmlSerializer.serialize(clean), ReviewHistoryStore.StoreState.class);
            var checkpoint = fileState(damaged, "a.txt").checkpoints.values().iterator().next();
            var stored = checkpoint.payload.refs.getFirst();
            switch (variant) {
                case 0 -> stored.evidenceId = "missing-proof";
                case 1 -> stored.baselineText = "forged\n";
                case 2 -> stored.live.end = current.length() + 1;
                case 3 -> stored.authoritative = false;
                default -> throw new AssertionError();
            }
            checkpoint.checksum = ProjectionCheckpointCodec.checksum(checkpoint.payload);
            store.loadState(damaged);

            var recovered = store.checkpoint(getProject(), "a.txt", current);
            assertEquals(Set.of(ref), recovered.authoritativeRefs());
            assertEmpty(recovered.livePositions().keySet());
            assertEquals(1, store.history(getProject(), "a.txt").evidence().size());
        }
    }

    public void testCheckpointsFollowRenameWithoutBeingAppliedToCoexistingCopy() throws Exception {
        String baseline = "same\n", current = "same\nsame\n";
        Path base = Path.of(getProject().getBasePath());
        Files.writeString(base.resolve("old.txt"), current);
        var proof = store.mark(getProject(), "old.txt", baseline, 0, baseline.length());
        var ref = new EvidenceRef(proof.id(), 0, 5);
        var hints = new ProjectionHints(Map.of(ref, new ReviewedRange(5, 10)), Set.of(ref), Set.of(), Map.of(), Map.of(ref, baseline), Map.of());
        store.checkpoint(getProject(), "old.txt", current, hints);
        Files.move(base.resolve("old.txt"), base.resolve("new.txt"));
        assertTrue(store.movePath(getProject(), "old.txt", "new.txt"));
        assertEquals(hints, store.checkpoint(getProject(), "new.txt", current));
        Files.copy(base.resolve("new.txt"), base.resolve("old.txt"));

        assertEmpty(store.checkpoint(getProject(), "old.txt", current).authoritativeRefs());
        assertEquals(hints, store.checkpoint(getProject(), "new.txt", current));
    }

    public void testUnsupportedSchemaSkipsCheckpointWrites() {
        var future = new ReviewHistoryStore.StoreState(); future.schemaVersion = 999;
        store.loadState(future);
        store.checkpoint(getProject(), "a.txt", "text\n", ProjectionHints.empty());
        assertNull(store.getState());
        assertEmpty(store.checkpoint(getProject(), "a.txt", "text\n").authoritativeRefs());
    }

    public void testPendingCheckpointReplacesEarlierEditAndSurvivesRestartWithoutGrowingStableMap() {
        String baseline = "same\n", first = "first\nsame\n", second = "second\nsame\n";
        var proof = store.mark(getProject(), "a.txt", baseline, 0, baseline.length());
        var ref = new EvidenceRef(proof.id(), 0, baseline.length());
        var lost = new ProjectionHints(Map.of(), Set.of(ref));
        var history = store.history(getProject(), "a.txt");
        store.checkpointPending(getProject(), "a.txt", first, lost);
        store.checkpointPending(getProject(), "a.txt", second, lost);
        var saved = store.getState();
        var stored = fileState(saved, "a.txt");
        assertEmpty(stored.checkpoints.keySet());
        assertEquals(dev.doreview.core.FileSnapshot.create(second).id(), stored.pendingCheckpoint.payload.textHash);
        store.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(saved), ReviewHistoryStore.StoreState.class));

        assertEquals(lost, store.checkpoint(getProject(), "a.txt", second));
        assertEquals(Set.of(ref), store.checkpoint(getProject(), "a.txt", second + "offline\n").authoritativeRefs());
        store.checkpoint(getProject(), "a.txt", first, lost);
        assertNotNull("An older settled result cannot discard a newer pending edit", fileState(store.getState(), "a.txt").pendingCheckpoint);
        store.checkpoint(getProject(), "a.txt", second, lost);
        assertNull(fileState(store.getState(), "a.txt").pendingCheckpoint);
        assertEquals(2, fileState(store.getState(), "a.txt").checkpoints.size());
        assertEquals(history, store.history(getProject(), "a.txt"));
    }

    private static ReviewHistoryStore.FileState fileState(ReviewHistoryStore.StoreState state, String path) {
        var project = state.projects.values().iterator().next();
        return project.files.get(project.aliases.get(path));
    }

    public void testPlatformStorageCannotRewriteRawSnapshotsOrCheckpointChecksums() throws Exception {
        String text = "let path = \"$USER_HOME$/literal\";\nlet raw = \""
                + System.getProperty("user.home").replace('\\', '/') + "/literal\";\n";
        var proof = store.mark(getProject(), "a.txt", text, 0, text.length());
        var ref = new EvidenceRef(proof.id(), 0, text.indexOf('\n') + 1);
        String current = text + "extra\n";
        store.checkpointPending(getProject(), "a.txt", current, new ProjectionHints(Map.of(), Set.of(ref)));
        Element component = XmlSerializer.serialize(store.getState());
        component.setName("component"); component.setAttribute("name", "ReviewMarksHistory");
        PathMacroManager manager = ApplicationManager.getApplication().getService(PathMacroManager.class);
        Path options = Path.of(getProject().getBasePath(), "test-config", "options");
        var storageManager = new ApplicationStateStorageManager(manager, null);
        storageManager.macros = List.of(new Macro("$APP_CONFIG$", options));
        var specification = ReviewHistoryStore.class.getAnnotation(State.class).storages()[0];
        var storage = (FileBasedStorage) storageManager.getStateStorage(specification);
        assertTrue("Storage regression must stay in its disposable fixture", storage.getFile().normalize().startsWith(Path.of(getProject().getBasePath()).normalize()));
        var substitutor = XmlElementStorage.class.getDeclaredField("pathMacroSubstitutor"); substitutor.setAccessible(true);
        assertNull("The actual storage factory must disable both macro expansion and collapse", substitutor.get(storage));
        Files.createDirectories(storage.getFile().getParent());
        Files.writeString(storage.getFile(), JDOMUtil.writeElement(new Element("application").addContent(component)));
        var loaded = (ReviewHistoryStore.StoreState) storage.getState(store, "ReviewMarksHistory", PluginId.getId("dev.reviewmarks"), ReviewHistoryStore.StoreState.class, null, false);
        assertNotNull(loaded);
        store.loadState(loaded);
        assertEquals(text, store.history(getProject(), "a.txt").snapshots().get(proof.snapshotId()).text());
        assertEquals(Set.of(ref), store.checkpoint(getProject(), "a.txt", current).authoritativeRefs());
        Element control = new Element("component").setAttribute("name", "UnrelatedSettings")
                .addContent(new Element("option").setAttribute("name", "path").setAttribute("value", "$USER_HOME$/control"));
        manager.expandPaths(control);
        assertFalse("The test must exercise real macro expansion outside this component",
                "$USER_HOME$/control".equals(control.getChild("option").getAttributeValue("value")));
    }

    public void testSchemaOneMacroRecoveryRequiresOriginalSnapshotAndCheckpointDigests() {
        String text = System.getProperty("user.home").replace('\\', '/') + "/reviewed\n";
        var proof = store.mark(getProject(), "a.txt", text, 0, text.length());
        var ref = new EvidenceRef(proof.id(), 0, text.length());
        String current = text + "new\n";
        var hints = new ProjectionHints(Map.of(), Set.of(ref), Set.of(), Map.of(), Map.of(ref, text), Map.of());
        store.checkpoint(getProject(), "a.txt", current, hints);
        var history = store.history(getProject(), "a.txt");
        var old = store.getState(); old.schemaVersion = 1;
        var source = fileState(old, "a.txt");
        source.snapshots.replaceAll((id, raw) -> "$USER_HOME$/reviewed\n");
        source.checkpoints.values().iterator().next().payload.refs.getFirst().baselineText = "$USER_HOME$/reviewed\n";
        String projectKey = old.projects.keySet().iterator().next();
        String user = System.getProperty("user.home").replace('\\', '/');
        if (System.getProperty("os.name", "").startsWith("Windows")) user = user.toLowerCase(java.util.Locale.ROOT);
        if (projectKey.startsWith(user + "/")) {
            var project = old.projects.remove(projectKey); old.projects.put("$USER_HOME$" + projectKey.substring(user.length()), project);
        }

        store.loadState(old);

        assertEquals(history, store.history(getProject(), "a.txt"));
        assertEquals(hints, store.checkpoint(getProject(), "a.txt", current));
        assertEquals(2, store.getState().schemaVersion);
        String literal = "$USER_HOME$/literal\n";
        store.mark(getProject(), "literal.txt", literal, 0, literal.length());
        var literalSaved = store.getState(); literalSaved.schemaVersion = 1; store.loadState(literalSaved);
        assertEquals(literal, store.history(getProject(), "literal.txt").snapshots().values().iterator().next().text());
    }

    public void testSchemaOneUnverifiableMacroPayloadStaysQuarantinedAndUnchanged() {
        store.mark(getProject(), "a.txt", "original\n", 0, 9);
        var old = store.getState(); old.schemaVersion = 1;
        fileState(old, "a.txt").snapshots.replaceAll((id, raw) -> "$USER_HOME$/unverified\n");
        store.loadState(old);

        assertEmpty(store.history(getProject(), "a.txt").evidence());
        assertEquals("$USER_HOME$/unverified\n", fileState(store.getState(), "a.txt").snapshots.values().iterator().next());
        assertFalse(store.diagnostics(getProject()).isEmpty());
    }

    public void testSchemaOneConflictingProjectIdentitiesPreserveBothWithoutAttachingReviews() {
        store.mark(getProject(), "a.txt", "same\n", 0, 5);
        var old = store.getState(); old.schemaVersion = 1;
        String key = old.projects.keySet().iterator().next();
        var duplicate = ReviewHistoryStateCopies.copy(old).projects.get(key);
        old.projects.put("$TEST_PROJECT$", duplicate);

        var warnings = LegacyMacroRecovery.recover(old, raw -> raw.equals("$TEST_PROJECT$") ? key : raw);
        old.schemaVersion = 2; store.loadState(old);

        assertEquals(2, store.getState().quarantinedProjects.size());
        assertEmpty(store.history(getProject(), "a.txt").evidence());
        assertTrue(store.getState().projects.get(key).legacyImported);
        assertFalse(warnings.isEmpty());
        store.mark(getProject(), "a.txt", "new review\n", 0, 11);
        assertEquals(1, store.history(getProject(), "a.txt").evidence().size());
        assertEquals(2, store.getState().quarantinedProjects.size());
    }

    public void testPreparedCheckpointCommitsOnlyItsExpectedHistoryAndReleasesPendingState() {
        String baseline = "same\n", current = "prefix\nsame\n";
        var proof = store.mark(getProject(), "a.txt", baseline, 0, baseline.length());
        var ref = new EvidenceRef(proof.id(), 0, baseline.length());
        var hints = new ProjectionHints(Map.of(), Set.of(ref));
        var history = store.history(getProject(), "a.txt");
        store.checkpointPending(getProject(), "a.txt", current, hints);
        long dirty = store.getStateModificationCount();
        var prepared = store.prepareCheckpoint(history, current, hints);
        assertEquals("Worker preparation must not mutate the store", dirty, store.getStateModificationCount());

        assertTrue(store.commitCheckpoint(getProject(), "a.txt", prepared));

        assertEquals(hints, store.checkpoint(getProject(), "a.txt", current));
        assertNull(fileState(store.getState(), "a.txt").pendingCheckpoint);
        assertEquals(history, store.history(getProject(), "a.txt"));
        assertTrue(store.getStateModificationCount() > dirty);
    }

    public void testPreparedCheckpointCannotOverwriteNewReviewOrReloadedHistory() {
        String baseline = "same\n", current = "prefix\nsame\n";
        var proof = store.mark(getProject(), "a.txt", baseline, 0, baseline.length());
        var ref = new EvidenceRef(proof.id(), 0, baseline.length());
        var hints = new ProjectionHints(Map.of(), Set.of(ref));
        var stale = store.prepareCheckpoint(store.history(getProject(), "a.txt"), current, hints);
        store.mark(getProject(), "a.txt", baseline, 0, baseline.length());
        long dirty = store.getStateModificationCount();
        assertFalse("New explicit evidence invalidates work computed from an older journal", store.commitCheckpoint(getProject(), "a.txt", stale));
        assertEquals(dirty, store.getStateModificationCount());
        var latest = store.prepareCheckpoint(store.history(getProject(), "a.txt"), current, hints);
        store.loadState(store.getState());
        var reloaded = store.history(getProject(), "a.txt");
        dirty = store.getStateModificationCount();
        assertFalse("Even value-equal reloaded history has a new identity", store.commitCheckpoint(getProject(), "a.txt", latest));
        assertEquals(dirty, store.getStateModificationCount());
        assertEquals(2, reloaded.evidence().size());
        assertEmpty(fileState(store.getState(), "a.txt").checkpoints.keySet());
    }

    public void testModificationTrackingIncludesInitialImportMarkerAndDoesNotCountReadsOrExports() {
        long initial = store.getStateModificationCount();
        store.importLegacyFromProject(getProject());
        long initialized = store.getStateModificationCount();
        assertTrue("Absent legacy source must persist its one-time initialization marker", initialized > initial);
        store.importLegacyFromProject(getProject()); store.getState(); store.history(getProject(), "missing.txt");
        assertEquals(initialized, store.getStateModificationCount());
        var proof = store.mark(getProject(), "a.txt", "same\n", 0, 5);
        long marked = store.getStateModificationCount(); assertTrue(marked > initialized);
        var ref = new EvidenceRef(proof.id(), 0, 5);
        store.checkpointPending(getProject(), "a.txt", "prefix\nsame\n", new ProjectionHints(Map.of(), Set.of(ref)));
        long pending = store.getStateModificationCount(); assertTrue(pending > marked);
        store.getState(); store.getState(); store.history(getProject(), "a.txt");
        store.checkpoint(getProject(), "a.txt", "prefix\nsame\n"); store.diagnostics(getProject());
        assertEquals("Export and cached lookups cannot mark state dirty again", pending, store.getStateModificationCount());
        store.revoke(getProject(), "a.txt", List.of(ref));
        assertTrue(store.getStateModificationCount() > pending);
    }

    public void testDetachedCheckpointBeansCannotMutateValidatedLiveState() {
        String baseline = "same\n", current = "same\nsame\n";
        var proof = store.mark(getProject(), "a.txt", baseline, 0, baseline.length());
        var ref = new EvidenceRef(proof.id(), 0, 5);
        var hints = new ProjectionHints(Map.of(ref, new ReviewedRange(5, 10)), Set.of(ref), Set.of(), Map.of(), Map.of(ref, baseline), Map.of());
        assertTrue(store.commitCheckpoint(getProject(), "a.txt", store.prepareCheckpoint(store.history(getProject(), "a.txt"), current, hints)));
        var detached = store.getState();
        fileState(detached, "a.txt").checkpoints.values().iterator().next().payload.refs.getFirst().live.start = 0;

        assertEquals(hints, store.checkpoint(getProject(), "a.txt", current));
        assertEquals(5, fileState(store.getState(), "a.txt").checkpoints.values().iterator().next().payload.refs.getFirst().live.start);
        store.loadState(detached);
        assertEmpty(store.checkpoint(getProject(), "a.txt", current).livePositions().keySet());
        long dirty = store.getStateModificationCount();
        assertFalse(store.diagnostics(getProject()).isEmpty());
        store.history(getProject(), "a.txt"); store.getState();
        assertEquals("Read diagnostics are transient and cannot cause autosave churn", dirty, store.getStateModificationCount());
    }

    public void testEvidenceRevocationFindsRenamedOwnerWithoutReadingUnrelatedDamagedHistory() {
        var proof = store.mark(getProject(), "old.txt", "same\n", 0, 5);
        store.mark(getProject(), "damaged.txt", "other\n", 0, 6);
        assertTrue(store.movePath(getProject(), "old.txt", "new.txt"));
        var saved = store.getState();
        fileState(saved, "damaged.txt").snapshots.replaceAll((id, text) -> "tampered\n");
        store.loadState(saved);

        store.revokeByEvidence(getProject(), List.of(new EvidenceRef(proof.id(), 0, 5)));

        assertEquals(1, store.history(getProject(), "new.txt").revocations().size());
        assertTrue("Unrelated snapshots must not be parsed while routing revocations", store.diagnostics(getProject()).isEmpty());
        assertEquals("tampered\n", fileState(store.getState(), "damaged.txt").snapshots.values().iterator().next());
    }

    public void testAmbiguousEvidenceOwnershipCannotRevokeIndependentHistory() {
        var first = store.mark(getProject(), "first.txt", "same\n", 0, 5);
        store.mark(getProject(), "second.txt", "same\n", 0, 5);
        var damaged = store.getState();
        fileState(damaged, "second.txt").evidence.getFirst().id = first.id();
        store.loadState(damaged);
        long dirty = store.getStateModificationCount();

        store.revokeByEvidence(getProject(), List.of(new EvidenceRef(first.id(), 0, 5)));

        assertEmpty(store.history(getProject(), "first.txt").revocations());
        assertEmpty(store.history(getProject(), "second.txt").revocations());
        assertFalse(store.diagnostics(getProject()).isEmpty());
        assertEquals(dirty, store.getStateModificationCount());
    }

    public void testNewPendingEditReplacesLoadedPendingWithoutResurfacingAfterCommit() throws Exception {
        String baseline = "same\n", first = "first\nsame\n", second = "second\nsame\n";
        var proof = store.mark(getProject(), "a.txt", baseline, 0, 5);
        var hints = new ProjectionHints(Map.of(), Set.of(new EvidenceRef(proof.id(), 0, 5)));
        store.checkpointPending(getProject(), "a.txt", first, hints);
        store.loadState(store.getState());
        store.checkpoint(getProject(), "a.txt", first);
        store.checkpointPending(getProject(), "a.txt", second, hints);
        assertTrue(store.commitCheckpoint(getProject(), "a.txt", store.prepareCheckpoint(store.history(getProject(), "a.txt"), second, hints)));

        assertNull("An older loaded pending DTO cannot reappear after the latest edit settles", fileState(store.getState(), "a.txt").pendingCheckpoint);
        assertEquals(1, validationCacheSize("a.txt"));
    }

    public void testRepeatedSameHashCommitsDoNotRetainSupersededValidationEntries() throws Exception {
        String baseline = "same\n", current = "prefix\nsame\n";
        var proof = store.mark(getProject(), "a.txt", baseline, 0, 5);
        var hints = new ProjectionHints(Map.of(), Set.of(new EvidenceRef(proof.id(), 0, 5)));
        var history = store.history(getProject(), "a.txt");
        for (int i = 0; i < 12; i++) assertTrue(store.commitCheckpoint(getProject(), "a.txt", store.prepareCheckpoint(history, current, hints)));

        assertEquals(1, fileState(store.getState(), "a.txt").checkpoints.size());
        assertEquals("Validation cache must follow live DTO ownership, not past worker results", 1, validationCacheSize("a.txt"));
    }

    private int validationCacheSize(String path) throws Exception {
        var stateField = ReviewHistoryStore.class.getDeclaredField("state"); stateField.setAccessible(true);
        var file = fileState((ReviewHistoryStore.StoreState) stateField.get(store), path);
        var cacheField = ReviewHistoryStore.FileState.class.getDeclaredField("validatedCheckpoints"); cacheField.setAccessible(true);
        return ((Map<?, ?>) cacheField.get(file)).size();
    }

    private Path legacyPath() { return Path.of(getProject().getBasePath(), ".idea", "review-marks.xml"); }
}
