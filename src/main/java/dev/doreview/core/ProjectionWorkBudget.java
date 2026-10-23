package dev.doreview.core;

/**
 * Operation-local allowance for quadratic diff cells, shared across all historical
 * snapshots and an optional following checkpoint. Exact, anchor and subsequence
 * matching remain available after exhaustion. Linear scans of history content and
 * proof rows are not bounded by this allowance. Instances are not thread-safe.
 */
public final class ProjectionWorkBudget {
    private static final long DEFAULT_DIFF_CELLS = 4_000_000;
    private final long maxDiffCells;
    private long remainingDiffCells;

    public ProjectionWorkBudget(long maxDiffCells) {
        if (maxDiffCells < 0) throw new IllegalArgumentException("Negative diff work allowance");
        this.maxDiffCells = maxDiffCells;
        remainingDiffCells = maxDiffCells;
    }

    public static ProjectionWorkBudget defaults() {
        return new ProjectionWorkBudget(DEFAULT_DIFF_CELLS);
    }

    public long remainingDiffCells() {
        return remainingDiffCells;
    }

    public long consumedDiffCells() {
        return maxDiffCells - remainingDiffCells;
    }

    boolean tryReserveDiffCells(long cells) {
        if (cells < 0) throw new IllegalArgumentException("Negative diff work reservation");
        if (cells > remainingDiffCells) return false;
        remainingDiffCells -= cells;
        return true;
    }
}
