package dev.doreview.ide;

import dev.doreview.core.FileSnapshot;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** One reviewed version offered by the history chooser. */
public record ReviewVersion(FileSnapshot snapshot, long createdAt, String authors) {
    public ReviewVersion {
        Objects.requireNonNull(snapshot, "snapshot");
        authors = authors == null ? "" : authors.strip();
    }
    public ReviewVersion(FileSnapshot snapshot, long createdAt) { this(snapshot, createdAt, ""); }

    @Override public String toString() {
        return label(snapshot.id(), createdAt, authors);
    }

    static String label(String snapshotId, long createdAt, String authors) {
        String date = createdAt == 0 ? "Imported review (date unknown)" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(createdAt));
        return date + (authors == null || authors.isBlank() ? "" : " · " + authors) + " · " + snapshotId.substring(0, 10);
    }
}
