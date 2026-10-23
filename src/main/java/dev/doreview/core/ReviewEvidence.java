package dev.doreview.core;

import java.util.List;
import java.util.Objects;

/** An explicit immutable review of ranges in one exact snapshot. */
public record ReviewEvidence(String id, String snapshotId, List<ReviewedRange> ranges,
                             long sequence, long createdAt) {
    public ReviewEvidence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(snapshotId, "snapshotId");
        ranges = ReviewRanges.normalized(ranges);
        if (id.isBlank() || snapshotId.isBlank() || sequence < 0 || createdAt < 0 || ranges.isEmpty()) {
            throw new IllegalArgumentException("Invalid review evidence");
        }
    }
}
