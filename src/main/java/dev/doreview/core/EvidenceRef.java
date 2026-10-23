package dev.doreview.core;

import java.util.Objects;

/** A row (or legacy fragment) in an immutable evidence snapshot, in UTF-16 offsets. */
public record EvidenceRef(String evidenceId, int baselineStart, int baselineEnd) {
    public EvidenceRef {
        Objects.requireNonNull(evidenceId, "evidenceId");
        if (evidenceId.isBlank() || baselineStart < 0 || baselineEnd <= baselineStart) {
            throw new IllegalArgumentException("Invalid evidence row reference");
        }
    }

    public ReviewedRange range() {
        return new ReviewedRange(baselineStart, baselineEnd);
    }
}
