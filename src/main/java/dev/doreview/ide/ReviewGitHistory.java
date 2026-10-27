package dev.doreview.ide;

import dev.doreview.core.FileSnapshot;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Optional, local-only source revision labels. Review snapshots remain the comparison authority. */
final class ReviewGitHistory {
    record Revision(String hash, String subject) { }
    record Limits(int commits, int logBytes, int blobBytes, int totalBlobBytes, long timeoutMillis) {
        static Limits defaults() { return new Limits(256, 4 * 1024 * 1024, 4 * 1024 * 1024, 16 * 1024 * 1024, 3000); }
    }
    private record CacheKey(Path root, String path, String head) { }
    private record Cached(long created, Map<String, Revision> revisions) { }
    private record Candidate(String blob, Revision revision) { }
    private static final String MARKER = "DOREVIEW-COMMIT";
    private static final long CACHE_NANOS = Duration.ofSeconds(30).toNanos();
    private final String executable;
    private final Limits limits;
    private final Map<CacheKey, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);

    ReviewGitHistory() { this("git", Limits.defaults()); }
    ReviewGitHistory(String executable, Limits limits) { this.executable = executable; this.limits = limits; }

    Map<String, Revision> find(Path file, Path projectRoot, Set<String> snapshotIds) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Git history must be read outside EDT");
        if (snapshotIds.isEmpty()) return Map.of();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
        try {
            Path root = ReviewRepositoryPaths.rootFor(file, projectRoot);
            String path = ReviewRepositoryPaths.relativePath(root, file);
            String head = head(root, deadline);
            var key = new CacheKey(root, path, head);
            Map<String, Revision> revisions;
            synchronized (cache) {
                var cached = cache.get(key);
                revisions = cached != null && System.nanoTime() - cached.created() < CACHE_NANOS ? cached.revisions() : null;
            }
            boolean loaded = revisions == null;
            if (revisions == null) {
                var log = run(root, List.of("log", "--topo-order", "--follow", "--diff-merges=first-parent",
                        "--raw", "-z", "--no-abbrev", "--no-ext-diff", "--no-textconv", "--no-show-signature", "--no-color", "--no-notes",
                        "--encoding=UTF-8", "--format=%x00" + MARKER + "%x00%H%x00%s%x00",
                        "--max-count=" + limits.commits(), head, "--", path), new byte[0], limits.logBytes(), deadline);
                revisions = revisions(root, candidates(log), deadline);
            }
            // A checkout while the worker runs must not relabel the new checkout with an old search.
            if (!head.equals(head(root, deadline))) return Map.of();
            if (loaded) synchronized (cache) {
                cache.put(key, new Cached(System.nanoTime(), Map.copyOf(revisions)));
                while (cache.size() > 16) cache.remove(cache.keySet().iterator().next());
            }
            var result = new LinkedHashMap<String, Revision>();
            for (String snapshot : snapshotIds) if (revisions.containsKey(snapshot)) result.put(snapshot, revisions.get(snapshot));
            return Map.copyOf(result);
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    private String head(Path root, long deadline) throws Exception {
        String head = utf8(run(root, List.of("rev-parse", "--verify", "HEAD"), new byte[0], 256, deadline)).strip();
        if (!objectId(head)) throw new IOException("Invalid Git revision");
        return head;
    }

    private List<Candidate> candidates(byte[] log) throws IOException {
        var tokens = new ArrayList<String>();
        int start = 0;
        for (int index = 0; index < log.length; index++) if (log[index] == 0) {
            tokens.add(utf8(Arrays.copyOfRange(log, start, index)));
            start = index + 1;
        }
        if (start != log.length) throw new IOException("Incomplete Git log");
        var result = new ArrayList<Candidate>();
        Revision revision = null;
        for (int index = 0; index < tokens.size(); ) {
            String token = tokens.get(index++);
            if (token.equals(MARKER)) {
                if (index + 1 >= tokens.size()) throw new IOException("Incomplete Git commit metadata");
                String hash = tokens.get(index++), subject = tokens.get(index++);
                if (!objectId(hash)) throw new IOException("Invalid Git commit metadata");
                subject = subject.codePoints().map(c -> Character.isISOControl(c) ? ' ' : c)
                        .limit(512).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
                revision = new Revision(hash, subject);
            } else if (!token.isBlank()) {
                String[] fields = token.stripLeading().split(" ");
                if (revision == null || fields.length != 5 || !fields[0].matches(":[0-7]{6}")
                        || !fields[1].matches("[0-7]{6}") || !objectId(fields[2]) || !objectId(fields[3])
                        || !fields[4].matches("[A-Z][0-9]*")) throw new IOException("Unsupported Git log record");
                int names = fields[4].startsWith("R") || fields[4].startsWith("C") ? 2 : 1;
                if (index + names > tokens.size()) throw new IOException("Incomplete Git path record");
                index += names; // NUL framing preserves spaces, tabs, newlines and non-ASCII path names.
                if (!fields[1].equals("000000") && !fields[3].chars().allMatch(c -> c == '0'))
                    result.add(new Candidate(fields[3], revision));
            }
        }
        return result;
    }

    private Map<String, Revision> revisions(Path root, List<Candidate> candidates, long deadline) throws Exception {
        var objects = new LinkedHashSet<String>();
        for (var candidate : candidates) objects.add(candidate.blob());
        if (objects.isEmpty()) return Map.of();
        byte[] checks = run(root, List.of("cat-file", "--batch-check"), requests(objects), 64 * 1024, deadline);
        var selected = new LinkedHashMap<String, Long>();
        long total = 0;
        for (String line : utf8(checks).split("\n")) {
            String[] parts = line.split(" ");
            if (parts.length == 2 && parts[1].equals("missing")) continue;
            if (parts.length != 3 || !objects.contains(parts[0]) || !parts[1].equals("blob")) continue;
            long size = Long.parseLong(parts[2]);
            if (size < 0 || size > limits.blobBytes() || total + size > limits.totalBlobBytes()) continue;
            selected.put(parts[0], size);
            total += size;
        }
        if (selected.isEmpty()) return Map.of();
        byte[] contents = run(root, List.of("cat-file", "--batch"), requests(selected.keySet()),
                Math.toIntExact(total + 256L * selected.size()), deadline);
        var hashes = new LinkedHashMap<String, String>();
        int offset = 0;
        for (var object : selected.entrySet()) {
            checkRemaining(deadline);
            int newline = indexOf(contents, offset, (byte) '\n');
            if (newline < 0) throw new IOException("Incomplete Git object header");
            String expected = object.getKey() + " blob " + object.getValue();
            if (!expected.equals(utf8(Arrays.copyOfRange(contents, offset, newline)))) throw new IOException("Unexpected Git object");
            offset = newline + 1;
            int end = Math.addExact(offset, Math.toIntExact(object.getValue()));
            if (end >= contents.length || contents[end] != '\n') throw new IOException("Incomplete Git object");
            byte[] blob = Arrays.copyOfRange(contents, offset, end);
            if (indexOf(blob, 0, (byte) 0) < 0) {
                try { hashes.put(object.getKey(), FileSnapshot.create(utf8(blob)).id()); }
                catch (IOException invalidText) { /* Binary or unsupported encoding cannot establish equality. */ }
            }
            offset = end + 1;
        }
        if (offset != contents.length) throw new IOException("Unexpected Git object data");
        var result = new LinkedHashMap<String, Revision>();
        for (var candidate : candidates) {
            String hash = hashes.get(candidate.blob());
            if (hash != null) result.putIfAbsent(hash, candidate.revision());
        }
        return Map.copyOf(result);
    }

    private byte[] run(Path root, List<String> arguments, byte[] input, int maxBytes, long deadline) throws Exception {
        checkRemaining(deadline);
        var command = new ArrayList<String>();
        command.add(executable);
        command.addAll(List.of("--literal-pathspecs", "--no-pager", "-C", root.toString(),
                "-c", "core.quotepath=false", "-c", "core.fsmonitor=false"));
        command.addAll(arguments);
        var builder = new ProcessBuilder(command);
        prepareEnvironment(builder.environment());
        Process process = builder.start();
        var output = new CompletableFuture<byte[]>();
        var errors = new CompletableFuture<Void>();
        var written = new CompletableFuture<Void>();
        Thread out = Thread.ofVirtual().start(() -> {
            try { output.complete(readBounded(process.getInputStream(), maxBytes)); }
            catch (Exception failure) { output.completeExceptionally(failure); process.destroyForcibly(); }
        });
        Thread err = Thread.ofVirtual().start(() -> {
            try (var stream = process.getErrorStream()) { stream.transferTo(java.io.OutputStream.nullOutputStream()); errors.complete(null); }
            catch (Exception failure) { errors.completeExceptionally(failure); }
        });
        Thread writer = Thread.ofVirtual().start(() -> {
            try (var stream = process.getOutputStream()) { stream.write(input); written.complete(null); }
            catch (Exception failure) { written.completeExceptionally(failure); }
        });
        try {
            if (!process.waitFor(checkRemaining(deadline), TimeUnit.NANOSECONDS)) throw new IOException("Git lookup timed out");
            if (process.exitValue() != 0) throw new IOException("Git history is unavailable");
            written.get(checkRemaining(deadline), TimeUnit.NANOSECONDS);
            errors.get(checkRemaining(deadline), TimeUnit.NANOSECONDS);
            return output.get(checkRemaining(deadline), TimeUnit.NANOSECONDS);
        } finally {
            process.destroyForcibly();
            close(process.getInputStream()); close(process.getErrorStream()); close(process.getOutputStream());
            out.interrupt(); err.interrupt(); writer.interrupt();
        }
    }

    static void prepareEnvironment(Map<String, String> environment) {
        // -C does not override GIT_DIR/GIT_WORK_TREE or object-store selectors. Inherited
        // Git config/trace variables can also change reads or create files outside this repo.
        environment.keySet().removeIf(name -> name.regionMatches(true, 0, "GIT_", 0, 4));
        environment.putAll(Map.of("GIT_OPTIONAL_LOCKS", "0", "GIT_NO_LAZY_FETCH", "1",
                "GIT_TERMINAL_PROMPT", "0", "GIT_NO_REPLACE_OBJECTS", "1", "LC_ALL", "C"));
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        try (input; var output = new ByteArrayOutputStream()) {
            var buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if ((long) output.size() + count > maxBytes) throw new IOException("Git lookup output exceeded its limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    private static long checkRemaining(long deadline) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Git lookup cancelled");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Git lookup timed out");
        return remaining;
    }
    private static byte[] requests(Set<String> objects) { return (String.join("\n", objects) + "\n").getBytes(StandardCharsets.US_ASCII); }
    private static boolean objectId(String value) { return value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"); }
    private static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static int indexOf(byte[] bytes, int start, byte value) {
        for (int index = start; index < bytes.length; index++) if (bytes[index] == value) return index;
        return -1;
    }
    private static void close(AutoCloseable stream) { try { stream.close(); } catch (Exception ignored) { } }
}
