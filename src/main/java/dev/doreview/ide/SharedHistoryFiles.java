package dev.doreview.ide;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;

import static dev.doreview.ide.SharedHistoryCodec.validateHash;

/** Immutable portable-file I/O; transaction and publication fencing remain in the repository. */
final class SharedHistoryFiles {
    record Fingerprint(long size, long modified, String digest) { }
    private final Path shared;
    private final Runnable publicationBoundary;

    SharedHistoryFiles(Path shared) { this(shared, null); }
    SharedHistoryFiles(Path shared, Runnable publicationBoundary) {
        this.shared = shared;
        this.publicationBoundary = publicationBoundary;
    }

    void ensureAttributes() throws IOException {
        Path attributes = shared.resolve(".gitattributes");
        if (!Files.exists(attributes)) writeImmutable(attributes, "v1/events/** text eol=lf\nv1/snapshots/** -text\n".getBytes(StandardCharsets.UTF_8));
    }
    Path eventPath(String id) { UUID.fromString(id); return shared.resolve("v1/events").resolve(id.substring(0, 2)).resolve(id + ".json"); }
    Path snapshotPath(String hash) { validateHash(hash); return shared.resolve("v1/snapshots").resolve(hash.substring(0, 2)).resolve(hash + ".txt"); }

    void safeSharedPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(shared)) throw new IllegalArgumentException("Review path escapes shared directory");
        for (Path candidate = absolute; candidate != null && candidate.startsWith(shared); candidate = candidate.getParent())
            if (Files.isSymbolicLink(candidate)) throw new IOException("Symbolic links are not supported inside shared history");
    }

    void safeSharedPath(Path path, Set<Path> checkedDirectories) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(shared)) throw new IllegalArgumentException("Review path escapes shared directory");
        // fingerprint() rejects symlink files. Directory checks are shared within this scan;
        // checking every ancestor again for every event is expensive on Windows filesystems.
        for (Path candidate = absolute.getParent(); candidate != null && candidate.startsWith(shared); candidate = candidate.getParent()) {
            if (!checkedDirectories.add(candidate)) break;
            if (Files.isSymbolicLink(candidate)) throw new IOException("Symbolic links are not supported inside shared history");
        }
    }

    void writeImmutable(Path target, byte[] bytes) throws IOException {
        safeSharedPath(target); Files.createDirectories(target.getParent()); safeSharedPath(target);
        if (Files.exists(target)) {
            if (!java.util.Arrays.equals(bytes, Files.readAllBytes(target))) throw new IOException("Conflicting immutable review file " + target.getFileName());
            return;
        }
        Path temporary = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
        try {
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            if (publicationBoundary != null) publicationBoundary.run();
            // Creating a hard link is atomic and never replaces a concurrently created target.
            // ATOMIC_MOVE permits target replacement on POSIX even without REPLACE_EXISTING.
            try { Files.createLink(target, temporary); }
            catch (java.nio.file.FileAlreadyExistsException raced) {
                if (!java.util.Arrays.equals(bytes, Files.readAllBytes(target)))
                    throw new IOException("Conflicting immutable review file " + target.getFileName(), raced);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("Filesystem does not support atomic hard-link publication; shared history was preserved", unsupported);
            } catch (IOException unavailable) {
                throw new IOException("Cannot publish shared history using an atomic hard link; existing files were preserved", unavailable);
            }
            // POSIX filesystems require the newly linked directory entry to be synchronized.
            // Windows does not expose directory fsync through FileChannel.
            try (var directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            catch (UnsupportedOperationException unavailable) { }
            catch (IOException unavailable) {
                if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) throw unavailable;
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    static Fingerprint fingerprint(Path path, int limit, String digest) throws IOException {
        var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > limit) throw new IOException("Missing, oversized or invalid review file " + path.getFileName());
        return new Fingerprint(attributes.size(), attributes.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS), digest);
    }
}
