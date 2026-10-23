package dev.doreview.ide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Filesystem identity shared by IDE products and isolated between Git worktrees. */
public final class ReviewRepositoryPaths {
    private ReviewRepositoryPaths() { }

    public static Path canonicalRoot(Path path) {
        try { return path.toRealPath(); }
        catch (IOException missing) { return path.toAbsolutePath().normalize(); }
    }

    /** A nested .git directory or worktree/submodule .git file establishes a new boundary. */
    public static Path rootFor(Path file, Path projectRoot) {
        Path root = canonicalRoot(projectRoot);
        Path current = canonicalRoot(Files.isDirectory(file) ? file : file.getParent());
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(".git"))) return candidate;
        }
        return root;
    }

    public static String relativePath(Path root, Path file) {
        Path canonicalRoot = canonicalRoot(root);
        Path target = canonicalRoot(file);
        if (!target.startsWith(canonicalRoot)) throw new IllegalArgumentException("File is outside repository");
        return validateRelativePath(canonicalRoot.relativize(target).toString().replace('\\', '/'));
    }

    public static String validateRelativePath(String path) {
        if (path == null || path.isBlank() || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
                || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Invalid repository-relative path");
        }
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")
                    || part.equalsIgnoreCase(".doreview")) throw new IllegalArgumentException("Unsafe repository-relative path");
        }
        return path;
    }

    public static Path databasePath(Path root) {
        String override = System.getProperty("doreview.data.path");
        Path directory;
        if (override != null && !override.isBlank()) directory = Path.of(override);
        else {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                String local = System.getenv("LOCALAPPDATA");
                directory = local == null || local.isBlank() ? Path.of(System.getProperty("user.home"), "AppData", "Local", "DoReview")
                        : Path.of(local, "DoReview");
            } else if (os.contains("mac")) directory = Path.of(System.getProperty("user.home"), "Library", "Application Support", "DoReview");
            else {
                String xdg = System.getenv("XDG_DATA_HOME");
                directory = xdg == null || xdg.isBlank() ? Path.of(System.getProperty("user.home"), ".local", "share", "DoReview") : Path.of(xdg, "DoReview");
            }
        }
        String identity = canonicalRoot(root).toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) identity = identity.toLowerCase(Locale.ROOT);
        return directory.resolve("worktrees").resolve(sha256(identity)).resolve("history.sqlite3").toAbsolutePath().normalize();
    }

    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
