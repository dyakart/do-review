package dev.doreview.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** One pure operation's prepared current text and mappings. Never shared across edits or budgets. */
public final class ReviewProjectionWorkspace {
    private final FileSnapshot current;
    private final SnapshotIndex currentIndex;
    private final ProjectionWorkBudget budget;
    private final Runnable cancellationCheck;
    private final Function<FileSnapshot, SnapshotReconciler.Mapping> externalMapping;
    private final Map<String, SnapshotReconciler.Mapping> mappings = new HashMap<>();

    public ReviewProjectionWorkspace(String current, ProjectionWorkBudget budget, Runnable cancellationCheck) {
        this(current, budget, cancellationCheck, null);
    }

    /** An external text matcher may supply correspondence without introducing platform dependencies. */
    public ReviewProjectionWorkspace(String current, ProjectionWorkBudget budget, Runnable cancellationCheck,
            Function<FileSnapshot, SnapshotReconciler.Mapping> externalMapping) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.cancellationCheck = Objects.requireNonNull(cancellationCheck, "cancellationCheck");
        this.externalMapping = externalMapping;
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
        return mappings.computeIfAbsent(baseline.id(), ignored -> externalMapping == null
                ? SnapshotReconciler.map(SnapshotIndex.of(baseline, cancellationCheck), currentIndex, budget, cancellationCheck)
                : Objects.requireNonNull(externalMapping.apply(baseline), "external mapping"));
    }
}
