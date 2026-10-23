package dev.doreview.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import dev.doreview.core.EvidenceRef;
import dev.doreview.core.FileSnapshot;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class ReviewHistoryActionsTest extends BasePlatformTestCase {
    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture project = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(project, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(project.getProject().getBasePath()); }
        });
    }

    public void testSharedChooserLoadsOnlyMetadataThenSelectedSnapshot() {
        open();
        var queries = new Queries();
        var view = new View(); view.selection = 1;
        var actions = actions(queries, view);
        actions.showChanges(document());
        assertEquals(1, queries.versionCallbacks.size());
        assertEmpty(queries.snapshotHashes);
        String older = FileSnapshot.create("older\n").id(), newer = FileSnapshot.create("newer\n").id();
        queries.versionCallbacks.getFirst().accept(List.of(version(older, 1, "Alice"), version(newer, 2, "Bob")));
        assertEquals(List.of(older), queries.snapshotHashes);
        assertEquals(2, view.choices.size());
        assertTrue(view.choices.getFirst().toString().contains("Bob"));
        assertEmpty(view.diffs);
        queries.snapshotCallbacks.getFirst().accept("older\n");
        assertEquals(List.of("older\n"), view.diffs);
        assertTrue(view.labels.getFirst().contains("Alice"));
    }

    public void testSupersededMetadataAndSnapshotCallbacksCannotOpenStaleDiff() {
        open();
        var queries = new Queries(); var view = new View(); var actions = actions(queries, view);
        var older = version(FileSnapshot.create("older\n").id(), 1, "Alice");
        var newer = version(FileSnapshot.create("newer\n").id(), 2, "Bob");
        actions.showChanges(document());
        actions.showChanges(document());
        queries.versionCallbacks.get(0).accept(List.of(older));
        assertEmpty(queries.snapshotHashes);
        queries.versionCallbacks.get(1).accept(List.of(older));
        assertEquals(1, queries.snapshotHashes.size());
        actions.showChanges(document());
        queries.snapshotCallbacks.get(0).accept("older\n");
        assertEmpty(view.diffs);
        queries.versionCallbacks.get(2).accept(List.of(newer));
        queries.snapshotCallbacks.get(1).accept("newer\n");
        assertEquals(List.of("newer\n"), view.diffs);
    }

    public void testMissingSelectedSnapshotReportsUnavailableWithoutOpeningDiff() {
        open();
        var queries = new Queries(); var view = new View(); var notices = new ArrayList<String>();
        var actions = new ReviewHistoryActions(getProject(), queries, new ReviewFileAccess(getProject()),
                ignored -> { throw new AssertionError("Shared chooser must not load the complete history"); }, () -> { }, notices::add, view);
        actions.showChanges(document());
        queries.versionCallbacks.getFirst().accept(List.of(version(FileSnapshot.create("older\n").id(), 1, "Alice")));
        queries.snapshotCallbacks.getFirst().accept(null);
        assertEmpty(view.diffs);
        assertEquals(List.of("The saved review version is unavailable."), notices);
    }

    public void testLegacyVersionsKeepCompatibilityAndIncludeCachedAuthors() {
        open();
        var queries = new Queries(); queries.shared = false;
        var view = new View();
        var original = ReviewHistoryEngine.review(ReviewHistory.empty(), "older\n", List.of(new ReviewedRange(0, 6)), 0);
        var actions = new ReviewHistoryActions(getProject(), queries, new ReviewFileAccess(getProject()),
                ignored -> original, () -> { }, ignored -> { }, view);
        var versions = actions.versions(document());
        assertEquals(1, versions.size());
        assertEquals("Alice", versions.getFirst().authors());
        assertTrue(versions.getFirst().toString().startsWith("Imported review (date unknown) · Alice"));
        assertEquals("", new ReviewVersion(versions.getFirst().snapshot(), 0).authors());
        actions.showChanges(document());
        assertEquals(List.of("older\n"), view.diffs);
        assertEmpty(queries.versionCallbacks);
        assertEmpty(queries.snapshotHashes);
    }

    public void testReattachFiltersMissingPathsOnWorkerAndMovesThroughAsyncApi() {
        open();
        var existing = myFixture.addFileToProject("existing.txt", "present\n");
        var access = new ReviewFileAccess(getProject());
        String target = access.relativePath(document());
        String missing = target.substring(0, target.lastIndexOf('/') + 1) + "missing.txt";
        String present = access.relativePath(existing.getVirtualFile().getPath());
        var queries = new Queries(); var view = new View(); var changed = new AtomicInteger();
        var actions = new ReviewHistoryActions(getProject(), queries, new ReviewFileAccess(getProject()),
                ignored -> ReviewHistory.empty(), changed::incrementAndGet, ignored -> fail("Unexpected warning"), view);
        actions.reattachHistory(document());
        assertEquals(1, queries.pathCallbacks.size());
        queries.pathCallbacks.getFirst().accept(List.of(present, missing, target, missing));
        PlatformTestUtil.waitWithEventsDispatching("Missing path chooser was not delivered", () -> !queries.moves.isEmpty(), 10);
        assertEquals(List.of(missing), view.choices);
        assertEquals(List.of(missing + " -> " + target), queries.moves);
        assertEquals(1, changed.get());
    }

    private ReviewHistoryActions actions(Queries queries, View view) {
        return new ReviewHistoryActions(getProject(), queries, new ReviewFileAccess(getProject()),
                ignored -> { throw new AssertionError("Shared chooser must not load complete history"); }, () -> { }, ignored -> { }, view);
    }
    private void open() {
        var file = myFixture.addFileToProject("current.txt", "current\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        assertNotNull(new ReviewFileAccess(getProject()).relativePath(document()));
    }
    private Document document() { return myFixture.getEditor().getDocument(); }
    private static SharedHistoryRepository.Version version(String hash, long time, String author) {
        return new SharedHistoryRepository.Version(hash, time, new SharedHistoryRepository.Author(author, ""));
    }

    private static final class Queries implements ReviewHistoryActions.Queries {
        private boolean shared = true;
        private final List<Consumer<List<SharedHistoryRepository.Version>>> versionCallbacks = new ArrayList<>();
        private final List<String> snapshotHashes = new ArrayList<>();
        private final List<Consumer<String>> snapshotCallbacks = new ArrayList<>();
        private final List<Consumer<List<String>>> pathCallbacks = new ArrayList<>();
        private final List<String> moves = new ArrayList<>();
        @Override public boolean shared() { return shared; }
        @Override public String authors(String path, List<EvidenceRef> refs) { return "Alice"; }
        @Override public void versions(String path, Consumer<List<SharedHistoryRepository.Version>> callback) { versionCallbacks.add(callback); }
        @Override public void snapshot(String path, String hash, Consumer<String> callback) { snapshotHashes.add(hash); snapshotCallbacks.add(callback); }
        @Override public void paths(Consumer<List<String>> callback) { pathCallbacks.add(callback); }
        @Override public void move(String oldPath, String newPath, Consumer<Boolean> callback) {
            assertTrue(ApplicationManager.getApplication().isDispatchThread());
            moves.add(oldPath + " -> " + newPath); callback.accept(true);
        }
    }

    private static final class View implements ReviewHistoryActions.View {
        private int selection;
        private List<?> choices = List.of();
        private final List<String> diffs = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();
        @Override public <T> void choose(List<T> choices, String title, Consumer<T> selected) {
            assertTrue(ApplicationManager.getApplication().isDispatchThread());
            this.choices = choices;
            selected.accept(choices.get(selection));
        }
        @Override public void diff(Document document, String snapshotText, String label) {
            assertTrue(ApplicationManager.getApplication().isDispatchThread());
            diffs.add(snapshotText); labels.add(label);
        }
    }
}
