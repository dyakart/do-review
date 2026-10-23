package dev.doreview.ide;

import dev.doreview.core.FileSnapshot;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** One reviewed version offered by the history chooser. */
public record ReviewVersion(FileSnapshot snapshot, long createdAt) {
    @Override public String toString() {
        String date = createdAt == 0 ? "Imported review (date unknown)" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(createdAt));
        return date + " · " + snapshot.id().substring(0, 10);
    }
}
