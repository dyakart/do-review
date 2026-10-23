package dev.doreview.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Source of truth. Projection and document edits never mutate or delete it. */
public record ReviewHistory(Map<String, FileSnapshot> snapshots, List<ReviewEvidence> evidence,
                            List<Revocation> revocations, long revision) {
    public ReviewHistory {
        snapshots = Map.copyOf(snapshots);
        evidence = List.copyOf(evidence);
        revocations = List.copyOf(revocations);
        if (revision < 0) throw new IllegalArgumentException("Negative history revision");
        for (var entry : snapshots.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())) {
                throw new IllegalArgumentException("Snapshot key/id mismatch");
            }
        }
        var byId = new HashMap<String, ReviewEvidence>();
        for (var proof : evidence) {
            var snapshot = snapshots.get(proof.snapshotId());
            if (byId.put(proof.id(), proof) != null || snapshot == null || proof.sequence() > revision
                    || proof.ranges().stream().anyMatch(range -> range.end() > snapshot.text().length())) {
                throw new IllegalArgumentException("Invalid evidence reference or range");
            }
        }
        for (var revoked : revocations) {
            var proof = byId.get(revoked.evidenceId());
            if (proof == null) throw new IllegalArgumentException("Unknown revocation evidence");
            int snapshotLength = snapshots.get(proof.snapshotId()).text().length();
            if (revoked.sequence() > revision || revoked.sequence() < proof.sequence()
                    || revoked.ranges().stream().anyMatch(range -> range.end() > snapshotLength)) {
                throw new IllegalArgumentException("Invalid revocation reference or range");
            }
        }
    }

    public static ReviewHistory empty() {
        return new ReviewHistory(Map.of(), List.of(), List.of(), 0);
    }
}
