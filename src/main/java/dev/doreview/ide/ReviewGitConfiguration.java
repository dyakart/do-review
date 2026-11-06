package dev.doreview.ide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Matches repository-local diff configuration, including a linked worktree's common Git directory. */
final class ReviewGitConfiguration {
    private ReviewGitConfiguration() { }

    static boolean affects(Path root, Path changed) {
        if (changed.getFileName() == null) return false;
        String name = changed.getFileName().toString();
        if (!name.equals("config") && !name.equals("config.worktree")) return false;
        changed = ReviewRepositoryPaths.canonicalRoot(changed);
        Path git = root.resolve(".git");
        try {
            if (Files.isRegularFile(git)) {
                String pointer = smallMetadata(git);
                if (!pointer.startsWith("gitdir:")) return false;
                git = root.resolve(pointer.substring("gitdir:".length()).strip()).normalize();
            }
            if (changed.equals(ReviewRepositoryPaths.canonicalRoot(git.resolve("config")))
                    || changed.equals(ReviewRepositoryPaths.canonicalRoot(git.resolve("config.worktree")))) return true;
            Path commonPointer = git.resolve("commondir");
            if (!Files.isRegularFile(commonPointer)) return false;
            Path common = git.resolve(smallMetadata(commonPointer)).normalize();
            return changed.equals(ReviewRepositoryPaths.canonicalRoot(common.resolve("config")));
        } catch (IOException | IllegalArgumentException unavailable) { return false; }
    }

    private static String smallMetadata(Path path) throws IOException {
        // These pointer files contain one path. A damaged large file must not block VFS handling.
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(16 * 1024 + 1);
            if (bytes.length > 16 * 1024) throw new IOException("Oversized Git metadata pointer");
            return new String(bytes, StandardCharsets.UTF_8).strip();
        }
    }
}
