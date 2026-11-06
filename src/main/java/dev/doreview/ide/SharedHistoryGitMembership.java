package dev.doreview.ide;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Distinguishes deliberately committed shared events from another branch's carried local files. */
final class SharedHistoryGitMembership {
    private static final int MAX_TREE_BYTES = 4 * 1024 * 1024;
    private static final long TIMEOUT_MILLIS = 3000;

    private SharedHistoryGitMembership() { }

    static Set<String> contains(Path root, Map<String, String> eventPayloads) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Git membership must be read outside EDT");
        if (eventPayloads.isEmpty()) return Set.of();
        var builder = new ProcessBuilder("git", "--literal-pathspecs", "--no-pager", "-C", root.toString(),
                "-c", "core.fsmonitor=false", "ls-tree", "-r", "-z", "HEAD", "--", ".doreview/v1/events");
        ReviewGitHistory.prepareEnvironment(builder.environment());
        Process process = builder.start();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        var output = new CompletableFuture<byte[]>();
        var errors = new CompletableFuture<byte[]>();
        Thread stdout = reader(process, process.getInputStream(), MAX_TREE_BYTES, output);
        Thread stderr = reader(process, process.getErrorStream(), 64 * 1024, errors);
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) remaining(deadline);
            byte[] tree = output.get(remaining(deadline), TimeUnit.NANOSECONDS);
            String error = new String(errors.get(remaining(deadline), TimeUnit.NANOSECONDS), StandardCharsets.UTF_8);
            if (process.exitValue() == 128 && error.contains("Not a valid object name HEAD")) return Set.of();
            if (process.exitValue() != 0) throw new IOException("Cannot inspect committed review history");
            return committed(tree, eventPayloads, deadline);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Git membership lookup cancelled");
        } catch (TimeoutException timeout) {
            throw new IOException("Git membership lookup timed out", timeout);
        } catch (ExecutionException failure) {
            throw new IOException("Cannot read committed review history", failure.getCause());
        } finally {
            process.destroyForcibly();
            close(process.getOutputStream()); close(process.getInputStream()); close(process.getErrorStream());
            stdout.interrupt(); stderr.interrupt();
        }
    }

    private static Set<String> committed(byte[] tree, Map<String, String> payloads, long deadline) throws IOException {
        var result = new HashSet<String>();
        int start = 0;
        for (int end = 0; end < tree.length; end++) {
            if (tree[end] != 0) continue;
            remaining(deadline);
            String entry = new String(tree, start, end - start, StandardCharsets.UTF_8);
            start = end + 1;
            int separator = entry.indexOf('\t');
            if (separator < 0) throw new IOException("Invalid Git tree entry");
            String[] metadata = entry.substring(0, separator).split(" ");
            if (metadata.length != 3) throw new IOException("Invalid Git tree metadata");
            if (!metadata[1].equals("blob") || !metadata[0].matches("100[0-7]{3}")) continue;
            String object = metadata[2];
            if (!object.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) throw new IOException("Invalid Git object identity");
            String path = entry.substring(separator + 1);
            int slash = path.lastIndexOf('/');
            if (slash < 0 || !path.endsWith(".json")) continue;
            String id = path.substring(slash + 1, path.length() - 5);
            String payload = payloads.get(id);
            if (payload == null || id.length() < 2 || !path.equals(".doreview/v1/events/" + id.substring(0, 2) + "/" + id + ".json")) continue;
            if (object.equals(blobId(payload, object.length()))) result.add(id);
            remaining(deadline);
        }
        if (start != tree.length) throw new IOException("Incomplete Git tree entry");
        return Set.copyOf(result);
    }

    private static String blobId(String payload, int hashLength) {
        byte[] content = payload.getBytes(StandardCharsets.UTF_8);
        try {
            var digest = MessageDigest.getInstance(hashLength == 64 ? "SHA-256" : "SHA-1");
            digest.update(("blob " + content.length + '\0').getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static Thread reader(Process process, InputStream stream, int limit, CompletableFuture<byte[]> target) {
        return Thread.ofVirtual().start(() -> {
            try (stream; var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int read; (read = stream.read(buffer)) != -1;) {
                    if ((long) output.size() + read > limit) throw new IOException("Git membership output exceeded its limit");
                    output.write(buffer, 0, read);
                }
                target.complete(output.toByteArray());
            } catch (IOException failure) { target.completeExceptionally(failure); process.destroyForcibly(); }
        });
    }

    private static long remaining(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Git membership lookup cancelled");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Git membership lookup timed out");
        return remaining;
    }

    private static void close(AutoCloseable stream) { try { stream.close(); } catch (Exception ignored) { } }
}
