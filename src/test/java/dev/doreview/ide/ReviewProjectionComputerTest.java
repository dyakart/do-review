package dev.doreview.ide;

import com.intellij.openapi.application.ReadAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.FileSnapshot;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;

import java.util.List;
import java.util.HashMap;
import java.util.Set;

public final class ReviewProjectionComputerTest extends BasePlatformTestCase {
    public void testContextBudgetKeepsCurrentThenMostRecentReviewedSnapshots() {
        String older = "def sample():\n    return 1\n";
        String newer = older.replace("1", "2");
        String current = older.replace("1", "3");
        var file = myFixture.configureByText("budget.py", current).getVirtualFile();
        ReviewHistory history = ReviewHistoryEngine.review(ReviewHistory.empty(), older,
                List.of(new ReviewedRange(0, older.length())), 100);
        // A clock rollback cannot let an old proof displace the latest explicit review.
        history = ReviewHistoryEngine.review(history, newer, List.of(new ReviewedRange(0, newer.length())), 1);
        ReviewHistory capturedHistory = history;
        var computer = new ReviewProjectionComputer(getProject(), new ReviewProjectionComputer.Limits(1, 1_000, 1_000));
        var contexts = ReadAction.computeBlocking(() -> computer.capture(capturedHistory, file, current));
        assertTrue(contexts.current().provider().startsWith("python:"));
        assertTrue(contexts.baselines().get(history.evidence().getLast().snapshotId()).provider().startsWith("python:"));
        assertEquals("analysis-limit", contexts.baselines().get(history.evidence().getFirst().snapshotId()).provider());
        assertNull(contexts.baselines().get(history.evidence().getFirst().snapshotId()).fingerprint(0));
    }

    public void testOversizedCurrentContextIsExplicitlyUnknown() {
        String text = "def sample():\n    return 1\n";
        var file = myFixture.configureByText("oversized.py", text).getVirtualFile();
        var computer = new ReviewProjectionComputer(getProject(), new ReviewProjectionComputer.Limits(1, 10, 10));
        var contexts = ReadAction.computeBlocking(() -> computer.capture(ReviewHistory.empty(), file, text));
        assertEquals("analysis-limit", contexts.current().provider());
        assertNull(contexts.current().fingerprint(0));
    }

    public void testCheckpointOnlySnapshotsAreNotHistoricalContextInputs() {
        String reviewed = "def sample():\n    return 1\n";
        String current = reviewed.replace("1", "2");
        var file = myFixture.configureByText("checkpoint_target.py", current).getVirtualFile();
        var original = ReviewHistoryEngine.review(ReviewHistory.empty(), reviewed,
                List.of(new ReviewedRange(0, reviewed.length())), 1);
        var snapshots = new HashMap<>(original.snapshots());
        var checkpoint = FileSnapshot.create(reviewed.replace("1", "3"));
        snapshots.put(checkpoint.id(), checkpoint);
        var history = new ReviewHistory(snapshots, original.evidence(), original.revocations(), original.revision());
        var computer = new ReviewProjectionComputer(getProject());

        var contexts = ReadAction.computeBlocking(() -> computer.capture(history, file, current));

        assertEquals(Set.of(original.evidence().getFirst().snapshotId()), contexts.baselines().keySet());
        assertTrue(contexts.baselines().values().iterator().next().provider().startsWith("python:"));
        assertFalse(contexts.baselines().containsKey(checkpoint.id()));
        assertEquals(2, history.snapshots().size());
    }
}
