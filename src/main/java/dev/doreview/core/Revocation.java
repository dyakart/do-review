package dev.doreview.core;

import java.util.List;
import java.util.Objects;

/** Explicitly withdraws ranges of one past proof; it never blocks future proofs. */
public record Revocation(String evidenceId, List<ReviewedRange> ranges, long sequence) {
    public Revocation {
        Objects.requireNonNull(evidenceId, "evidenceId");
        ranges = ReviewRanges.normalized(ranges);
        if (evidenceId.isBlank() || sequence < 0 || ranges.isEmpty()) {
            throw new IllegalArgumentException("Invalid review revocation");
        }
    }
}
