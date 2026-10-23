package dev.doreview.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** One pure operation's prepared current text and mappings. Never shared across edits or budgets. */
public final class ReviewProjectionWorkspace {
    private final FileSnapshot current;
    private final SnapshotIndex currentIndex;
    private final ProjectionWorkBudget budget;
    private final Runnable cancellationCheck;
    private final Map<String, SnapshotReconciler.Mapping> mappings = new HashMap<>();

    public ReviewProjectionWorkspace(String current, ProjectionWorkBudget budget, Runnable cancellationCheck) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.cancellationCheck = Objects.requireNonNull(cancellationCheck, "cancellationCheck");
        cancellationCheck.run();
        this.current = FileSnapshot.create(current);
        currentIndex = SnapshotIndex.prepare(this.current.text(), cancellationCheck);
    }

    FileSnapshot current() { return current; }
    SnapshotIndex currentIndex() { return currentIndex; }
    void checkCanceled() { cancellationCheck.run(); }
    Runnable cancellationCheck() { return cancellationCheck; }

    SnapshotReconciler.Mapping mapping(FileSnapshot baseline) {
        cancellationCheck.run();
        return mappings.computeIfAbsent(baseline.id(), ignored -> SnapshotReconciler.map(
                SnapshotIndex.of(baseline, cancellationCheck), currentIndex, budget, cancellationCheck));
    }
}
