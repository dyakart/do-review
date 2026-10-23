package dev.doreview.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable LF-normalized file content, shared by review records. */
public record FileSnapshot(String id, String text) {
    public FileSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        if (!text.equals(normalize(text)) || !id.equals(hash(text))) {
            throw new IllegalArgumentException("Snapshot id must match its LF-normalized content");
        }
    }

    public static FileSnapshot create(String text) {
        String normalized = normalize(Objects.requireNonNull(text, "text"));
        return new FileSnapshot(hash(normalized), normalized);
    }

    public static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
