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
        return label(snapshotId, createdAt, authors, null);
    }

    static String label(String snapshotId, long createdAt, String authors, ReviewGitHistory.Revision revision) {
        return title(snapshotId, revision) + " · " + details(snapshotId, createdAt, authors, revision);
    }

    static String title(String snapshotId, ReviewGitHistory.Revision revision) {
        return revision == null ? "DoReview snapshot · " + shortId(snapshotId)
                : "Git " + shortId(revision.hash()) + " — " + singleLine(revision.subject());
    }

    static String details(String snapshotId, long createdAt, String authors, ReviewGitHistory.Revision revision) {
        String reviewDate = createdAt == 0 ? "Imported review · date unknown" : "Reviewed "
                + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(createdAt));
        return reviewDate + (authors == null || authors.isBlank() ? "" : " · by " + singleLine(authors))
                + (revision == null ? "" : " · snapshot " + shortId(snapshotId));
    }

    private static String shortId(String value) { return value.substring(0, Math.min(10, value.length())); }

    private static String singleLine(String value) {
        StringBuilder text = new StringBuilder();
        value.codePoints().forEach(character -> text.appendCodePoint(Character.isISOControl(character) ? ' ' : character));
        return text.toString().strip();
    }
}
