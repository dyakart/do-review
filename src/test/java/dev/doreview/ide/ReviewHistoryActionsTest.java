package dev.doreview.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
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

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        assertTrue(versions.getFirst().toString().startsWith("DoReview snapshot · "));
        assertTrue(versions.getFirst().toString().contains("Imported review · date unknown · by Alice"));
        assertEquals("", new ReviewVersion(versions.getFirst().snapshot(), 0).authors());
        actions.showChanges(document());
        assertEquals(List.of("older\n"), view.diffs);
        assertEmpty(queries.versionCallbacks);
        assertEmpty(queries.snapshotHashes);
    }

    public void testGitLabelsKeepReviewOrderingAndLoadOnlyTheSelectedSavedSnapshot() {
        open();
        var queries = new Queries(); var view = new View(); var git = new GitLabels();
        var actions = actions(queries, view, git);
        String older = FileSnapshot.create("older\n").id(), newer = FileSnapshot.create("newer\n").id();
        actions.showChanges(document());
        queries.versionCallbacks.getFirst().accept(List.of(version(older, 1, "Alice"), version(newer, 2, "Bob")));
        assertEquals(List.of(Set.of(older, newer)), git.snapshots);
        assertEmpty(view.choices); assertEmpty(queries.snapshotHashes);
        var revision = new ReviewGitHistory.Revision("1234567890".repeat(4), "fix: preserve reviewed code");
        git.callbacks.getFirst().accept(Map.of(newer, revision));
        assertEquals(2, view.choices.size());
        var first = (ReviewHistoryActions.VersionChoice) view.choices.getFirst();
        assertEquals("Git 1234567890 — fix: preserve reviewed code", first.title());
        assertTrue(first.details().startsWith("Reviewed "));
        assertTrue(first.details().contains("by Bob"));
        assertTrue(first.details().contains("snapshot " + newer.substring(0, 10)));
        assertTrue(((ReviewHistoryActions.VersionChoice) view.choices.get(1)).title().startsWith("DoReview snapshot · "));
        assertEquals(List.of(newer), queries.snapshotHashes);
        queries.snapshotCallbacks.getFirst().accept("newer\n");
        assertEquals(List.of("newer\n"), view.diffs);
        assertTrue(view.labels.getFirst().contains(revision.subject()));
        assertTrue(view.labels.getFirst().contains("by Bob"));
    }

    public void testSupersededGitLabelsCannotOpenAnOldChooserOrDiff() {
        open();
        var queries = new Queries(); var view = new View(); var git = new GitLabels();
        var actions = actions(queries, view, git);
        String older = FileSnapshot.create("older\n").id(), newer = FileSnapshot.create("newer\n").id();
        actions.showChanges(document());
        queries.versionCallbacks.getFirst().accept(List.of(version(older, 1, "Alice")));
        actions.showChanges(document());
        queries.versionCallbacks.get(1).accept(List.of(version(newer, 2, "Bob")));
        git.callbacks.getFirst().accept(Map.of());
        assertEmpty(queries.snapshotHashes); assertEmpty(view.diffs);
        git.callbacks.get(1).accept(Map.of());
        assertEquals(List.of(newer), queries.snapshotHashes);
        queries.snapshotCallbacks.getFirst().accept("newer\n");
        assertEquals(List.of("newer\n"), view.diffs);
    }

    public void testCheckoutChangeSuppressesGitLabelsAndSelectedSnapshotDelivery() {
        open();
        var queries = new Queries(); queries.checkout = "main:first";
        var view = new View(); var git = new GitLabels(); var actions = actions(queries, view, git);
        var reviewed = version(FileSnapshot.create("checked\n").id(), 1, "Alice");
        actions.showChanges(document());
        queries.versionCallbacks.getFirst().accept(List.of(reviewed));
        queries.checkout = "feature:second";
        git.callbacks.getFirst().accept(Map.of());
        assertEmpty(queries.snapshotHashes);
        actions.showChanges(document());
        queries.versionCallbacks.get(1).accept(List.of(reviewed));
        git.callbacks.get(1).accept(Map.of());
        assertEquals(1, queries.snapshotHashes.size());
        queries.checkout = "main:third";
        queries.snapshotCallbacks.getFirst().accept("checked\n");
        assertEmpty(view.diffs);
    }

    public void testRenamedFileSuppressesPendingGitLabelDelivery() {
        open();
        var queries = new Queries(); var view = new View(); var git = new GitLabels();
        var actions = actions(queries, view, git);
        actions.showChanges(document());
        queries.versionCallbacks.getFirst().accept(List.of(version(FileSnapshot.create("checked\n").id(), 1, "Alice")));
        var file = FileDocumentManager.getInstance().getFile(document());
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            try { file.rename(this, "renamed.txt"); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        git.callbacks.getFirst().accept(Map.of());
        assertEmpty(queries.snapshotHashes); assertEmpty(view.diffs);
    }

    public void testClosedFileSuppressesPendingGitLabelDelivery() {
        open();
        var manager = FileEditorManager.getInstance(getProject());
        var file = FileDocumentManager.getInstance().getFile(document());
        manager.openFile(file, true);
        assertTrue(manager.isFileOpen(file));
        var queries = new Queries(); var view = new View(); var git = new GitLabels();
        var actions = actions(queries, view, git);
        actions.showChanges(document());
        queries.versionCallbacks.getFirst().accept(List.of(version(FileSnapshot.create("checked\n").id(), 1, "Alice")));
        manager.closeFile(file);
        git.callbacks.getFirst().accept(Map.of());
        assertEmpty(queries.snapshotHashes); assertEmpty(view.diffs);
    }

    public void testNoGitMatchLabelsImportedSnapshotWithoutClaimingItWasUncommitted() {
        open();
        var queries = new Queries(); var view = new View(); var actions = actions(queries, view);
        actions.showChanges(document());
        queries.versionCallbacks.getFirst().accept(List.of(version(FileSnapshot.create("checked\n").id(), 0, "Imported history")));
        queries.snapshotCallbacks.getFirst().accept("checked\n");
        String label = view.labels.getFirst();
        assertTrue(label.startsWith("DoReview snapshot · "));
        assertTrue(label.contains("Imported review · date unknown"));
        assertTrue(label.contains("by Imported history"));
        assertFalse(label.toLowerCase(java.util.Locale.ROOT).contains("uncommitted"));
    }

    public void testLegacyChooserAlsoUsesGitLabelsWithoutReadingSharedSnapshots() {
        open();
        var queries = new Queries(); queries.shared = false;
        var view = new View(); var git = new GitLabels();
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), "older\n", List.of(new ReviewedRange(0, 6)), 1);
        String hash = history.snapshots().keySet().iterator().next();
        var actions = new ReviewHistoryActions(getProject(), queries, new ReviewFileAccess(getProject()),
                ignored -> history, () -> { }, ignored -> { }, view, git);
        actions.showChanges(document());
        assertEmpty(view.diffs);
        git.callbacks.getFirst().accept(Map.of(hash, new ReviewGitHistory.Revision("a".repeat(40), "feat: reviewed source")));
        assertEquals(List.of("older\n"), view.diffs);
        assertTrue(view.labels.getFirst().startsWith("Git aaaaaaaaaa — feat: reviewed source"));
        assertEmpty(queries.versionCallbacks); assertEmpty(queries.snapshotHashes);
    }

    public void testNoReviewedVersionsDoesNotStartGitLookup() {
        open();
        var queries = new Queries(); var view = new View(); var git = new GitLabels(); var notices = new ArrayList<String>();
        var actions = new ReviewHistoryActions(getProject(), queries, new ReviewFileAccess(getProject()),
                ignored -> ReviewHistory.empty(), () -> { }, notices::add, view, git);
        actions.showChanges(document());
        queries.versionCallbacks.getFirst().accept(List.of());
        assertEmpty(git.callbacks); assertEmpty(queries.snapshotHashes);
        assertEquals(List.of("This file has no saved review versions."), notices);
    }

    public void testRendererUsesPlainCommitTextAndSeparateReviewMetadata() {
        var renderer = new ReviewHistoryActions.VersionRenderer();
        var choice = new ReviewHistoryActions.VersionChoice("b".repeat(64), 42, "Reviewer\n<html>name</html>",
                new ReviewGitHistory.Revision("a".repeat(40), "<html><b>fix</b> & preserve context\nnext"));
        var panel = (JPanel) renderer.getListCellRendererComponent(new JList<>(), choice, 0, false, false);
        var labels = Arrays.stream(panel.getComponents()).filter(JLabel.class::isInstance).map(JLabel.class::cast).toList();
        assertEquals(2, labels.size());
        assertEquals("Git aaaaaaaaaa — <html><b>fix</b> & preserve context next", labels.getFirst().getText());
        assertTrue(labels.get(1).getText().startsWith("Reviewed "));
        assertTrue(labels.get(1).getText().contains("by Reviewer <html>name</html>"));
        for (var label : labels) {
            assertEquals(Boolean.TRUE, label.getClientProperty("html.disable"));
            assertNull("Commit and author text must not create HTML views", label.getClientProperty("html"));
        }
        assertTrue(panel.getToolTipText().contains("not the commit"));
    }

    public void testRendererPreview() throws Exception {
        var choices = new ReviewHistoryActions.VersionChoice[]{
                new ReviewHistoryActions.VersionChoice("b".repeat(64), 1_792_785_600_000L, "Alice",
                        new ReviewGitHistory.Revision("1234567890".repeat(4), "fix: require positive MM fee and preserve <review> context")),
                new ReviewHistoryActions.VersionChoice("c".repeat(64), 0, "Imported history", null)};
        var list = new JList<>(choices);
        var renderer = new ReviewHistoryActions.VersionRenderer();
        list.setCellRenderer(renderer);
        list.setSelectedIndex(0);
        int rowHeight = renderer.getListCellRendererComponent(list, choices[0], 0, true, false).getPreferredSize().height;
        list.setFixedCellHeight(rowHeight);
        list.setSize(880, rowHeight * choices.length);
        var image = new BufferedImage(list.getWidth(), list.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            for (int row = 0; row < choices.length; row++) {
                var cell = (JPanel) renderer.getListCellRendererComponent(list, choices[row], row, row == 0, false);
                cell.setBounds(0, 0, list.getWidth(), rowHeight);
                // These renderers have no native peer in the headless fixture: validate()
                // cannot lay out their children, so size the offscreen cell explicitly.
                cell.doLayout();
                var rowGraphics = graphics.create(0, row * rowHeight, list.getWidth(), rowHeight);
                try { cell.printAll(rowGraphics); } finally { rowGraphics.dispose(); }
                for (var component : cell.getComponents()) if (component instanceof JLabel label) {
                    assertTrue("Both text lines need real layout bounds", label.getWidth() > 0 && label.getHeight() > 0);
                    int textPixels = 0;
                    for (int y = label.getY(); y < label.getY() + label.getHeight(); y++) {
                        for (int x = label.getX(); x < label.getX() + label.getWidth(); x++) {
                            if (image.getRGB(x, row * rowHeight + y) != cell.getBackground().getRGB()) textPixels++;
                        }
                    }
                    assertTrue("The preview must paint glyphs for each title and review metadata line", textPixels > 20);
                }
            }
        } finally { graphics.dispose(); }
        var output = Path.of("build/preview/do-review-git-reviewed-versions.png");
        Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(image, "png", output.toFile()));
        assertTrue(image.getWidth() > 0 && image.getHeight() >= 60);
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
    private ReviewHistoryActions actions(Queries queries, View view, GitLabels git) {
        return new ReviewHistoryActions(getProject(), queries, new ReviewFileAccess(getProject()),
                ignored -> { throw new AssertionError("Shared chooser must not load complete history"); }, () -> { }, ignored -> { }, view, git);
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
        private String checkout;
        private final List<Consumer<List<SharedHistoryRepository.Version>>> versionCallbacks = new ArrayList<>();
        private final List<String> snapshotHashes = new ArrayList<>();
        private final List<Consumer<String>> snapshotCallbacks = new ArrayList<>();
        private final List<Consumer<List<String>>> pathCallbacks = new ArrayList<>();
        private final List<String> moves = new ArrayList<>();
        @Override public boolean shared() { return shared; }
        @Override public String checkoutToken(String path) { return checkout; }
        @Override public String authors(String path, List<EvidenceRef> refs) { return "Alice"; }
        @Override public void versions(String path, Consumer<List<SharedHistoryRepository.Version>> callback) { versionCallbacks.add(callback); }
        @Override public void snapshot(String path, String hash, Consumer<String> callback) { snapshotHashes.add(hash); snapshotCallbacks.add(callback); }
        @Override public void paths(Consumer<List<String>> callback) { pathCallbacks.add(callback); }
        @Override public void move(String oldPath, String newPath, Consumer<Boolean> callback) {
            assertTrue(ApplicationManager.getApplication().isDispatchThread());
            moves.add(oldPath + " -> " + newPath); callback.accept(true);
        }
    }

    private static final class GitLabels implements ReviewHistoryActions.GitLabels {
        private final List<Set<String>> snapshots = new ArrayList<>();
        private final List<Consumer<Map<String, ReviewGitHistory.Revision>>> callbacks = new ArrayList<>();
        @Override public void find(String path, Set<String> ids, Consumer<Map<String, ReviewGitHistory.Revision>> callback) {
            snapshots.add(ids); callbacks.add(callback);
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
