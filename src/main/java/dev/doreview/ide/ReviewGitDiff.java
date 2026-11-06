package dev.doreview.ide;

import dev.doreview.core.FileSnapshot;
import dev.doreview.core.SnapshotReconciler;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Maps supplied snapshots with the working tree's Git diff engine, including unsaved text. */
final class ReviewGitDiff {
    record Limits(int textBytes, int outputBytes, long timeoutMillis) {
        Limits {
            if (textBytes < 0 || outputBytes < 0 || timeoutMillis < 0) {
                throw new IllegalArgumentException("Negative Git diff limit");
            }
        }

        static Limits defaults() { return new Limits(8 * 1024 * 1024, 32 * 1024 * 1024, 3000); }
    }

    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    private final String executable;
    private final Limits limits;

    ReviewGitDiff() { this("git", Limits.defaults()); }

    ReviewGitDiff(String executable, Limits limits) {
        this.executable = Objects.requireNonNull(executable, "executable");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** One deadline is shared by every historical snapshot in the caller's workspace. */
    MappingSession mapper(Path root, String current, Runnable cancellationCheck) {
        requireBackground();
        return new MappingSession(root, current, cancellationCheck);
    }

    SnapshotReconciler.Mapping map(Path root, String baseline, String current, Runnable cancellationCheck) throws IOException {
        try (var session = mapper(root, current, cancellationCheck)) {
            return session.mapText(baseline);
        }
    }

    /** A projection owns one current file; Git receives only a replaced baseline for each snapshot. */
    final class MappingSession implements Function<FileSnapshot, SnapshotReconciler.Mapping>, AutoCloseable {
        private final Path root;
        private final String current;
        private final Runnable cancellationCheck;
        private final long deadline = deadline();
        private String next;
        private byte[] newBytes;
        private Path temporary;
        private Path oldFile;
        private Path newFile;
        private boolean closed;

        private MappingSession(Path root, String current, Runnable cancellationCheck) {
            this.root = Objects.requireNonNull(root, "root");
            this.current = Objects.requireNonNull(current, "current");
            this.cancellationCheck = Objects.requireNonNull(cancellationCheck, "cancellationCheck");
        }

        @Override public SnapshotReconciler.Mapping apply(FileSnapshot baseline) {
            try { return mapText(Objects.requireNonNull(baseline, "baseline").text()); }
            catch (IOException unavailable) {
                closeAfterFailure(unavailable);
                throw new UncheckedIOException(unavailable);
            } catch (RuntimeException | Error cancelled) {
                closeAfterFailure(cancelled);
                throw cancelled;
            }
        }

        private SnapshotReconciler.Mapping mapText(String baseline) throws IOException {
            requireBackground();
            if (closed) throw new IllegalStateException("Git diff operation is closed");
            checkRemaining(deadline, cancellationCheck);
            Objects.requireNonNull(baseline, "baseline");
            // CRLF normalization can halve the character count. Reject larger inputs
            // before allocating normalization copies or UTF-8 arrays, then check exact bytes.
            long rawLimit = 2L * limits.textBytes();
            if (baseline.length() > rawLimit || newBytes == null && current.length() > rawLimit) {
                throw new IOException("Git diff input exceeded its limit");
            }
            String previous = FileSnapshot.normalize(baseline);
            byte[] oldBytes = previous.getBytes(StandardCharsets.UTF_8);
            if (newBytes == null) {
                next = FileSnapshot.normalize(current);
                newBytes = next.getBytes(StandardCharsets.UTF_8);
            }
            if (oldBytes.length > limits.textBytes() || newBytes.length > limits.textBytes()) {
                throw new IOException("Git diff input exceeded its limit");
            }
            checkRemaining(deadline, cancellationCheck);
            if (temporary == null) {
                temporary = Files.createTempDirectory("doreview-git-diff-");
                oldFile = temporary.resolve("reviewed.txt");
                newFile = temporary.resolve("current.txt");
                Files.write(newFile, newBytes);
            }
            Files.write(oldFile, oldBytes);
            var command = new ArrayList<>(List.of(executable, "--literal-pathspecs", "--no-pager", "-C", root.toString(),
                    "-c", "core.fsmonitor=false", "diff", "--no-index", "--no-ext-diff", "--no-textconv",
                    "--no-color", "--text", "--no-renames", "--unified=0", "--inter-hunk-context=0", "--",
                    oldFile.toString(), newFile.toString()));
            byte[] output = run(command, deadline, cancellationCheck);
            return parse(previous, next, output, cancellationCheck, deadline);
        }

        @Override public void close() throws IOException {
            if (closed && temporary == null) return;
            closed = true;
            IOException failure = null;
            // Attempt every removal even if a terminated Git process still holds one file open.
            for (Path path : new Path[]{oldFile, newFile, temporary}) {
                if (path == null) continue;
                try { Files.deleteIfExists(path); }
                catch (IOException unavailable) {
                    if (failure == null) failure = unavailable;
                    else failure.addSuppressed(unavailable);
                }
            }
            if (failure != null) throw failure;
            oldFile = null;
            newFile = null;
            temporary = null;
        }

        private void closeAfterFailure(Throwable failure) {
            try { close(); }
            catch (IOException unavailable) { failure.addSuppressed(unavailable); }
        }
    }

    /** Hunk intervals are authoritative; only exact complete rows outside them retain marks. */
    private static SnapshotReconciler.Mapping parse(String previous, String current, byte[] output,
                                                    Runnable cancellationCheck, long deadline) throws IOException {
        var oldRows = rows(previous, cancellationCheck, deadline);
        var newRows = rows(current, cancellationCheck, deadline);
        int[] mapping = new int[oldRows.size()];
        Arrays.fill(mapping, -1);
        var changes = new ArrayList<SnapshotReconciler.Change>();
        int oldCursor = 0, newCursor = 0;
        int hunks = 0;
        for (String line : new String(output, StandardCharsets.UTF_8).split("\n", -1)) {
            checkRemaining(deadline, cancellationCheck);
            if (!line.startsWith("@@ ")) continue;
            var header = HUNK.matcher(line);
            if (!header.matches()) throw new IOException("Invalid Git diff hunk");
            int oldCount = number(header.group(2), 1), newCount = number(header.group(4), 1);
            int oldStart = number(header.group(1), 0) - (oldCount == 0 ? 0 : 1);
            int newStart = number(header.group(3), 0) - (newCount == 0 ? 0 : 1);
            long oldEnd = (long) oldStart + oldCount, newEnd = (long) newStart + newCount;
            if (oldStart < oldCursor || newStart < newCursor || oldEnd > oldRows.size() || newEnd > newRows.size()
                    || oldStart < 0 || newStart < 0 || oldCount == 0 && newCount == 0) {
                throw new IOException("Git diff hunk exceeds its snapshots");
            }
            mapUnchanged(oldRows, newRows, oldCursor, oldStart, newCursor, newStart, mapping, cancellationCheck, deadline);
            if (oldCount > 0) {
                changes.add(new SnapshotReconciler.Change(oldStart, (int) oldEnd, newStart, (int) newEnd,
                        newCount == 0 ? SnapshotReconciler.ChangeReason.DELETED : SnapshotReconciler.ChangeReason.TEXT_CHANGED));
            }
            oldCursor = (int) oldEnd;
            newCursor = (int) newEnd;
            hunks++;
        }
        if (hunks == 0 && !previous.equals(current)) throw new IOException("Git diff did not describe changed text");
        mapUnchanged(oldRows, newRows, oldCursor, oldRows.size(), newCursor, newRows.size(), mapping, cancellationCheck, deadline);
        return new SnapshotReconciler.Mapping(Arrays.stream(mapping).boxed().toList(), changes);
    }

    private static void mapUnchanged(List<String> previous, List<String> current, int oldStart, int oldEnd,
                                     int newStart, int newEnd, int[] mapping,
                                     Runnable cancellationCheck, long deadline) throws IOException {
        if (oldEnd - oldStart != newEnd - newStart) throw new IOException("Inconsistent Git diff line counts");
        for (int offset = 0; offset < oldEnd - oldStart; offset++) {
            if ((offset & 255) == 0) checkRemaining(deadline, cancellationCheck);
            if (!previous.get(oldStart + offset).equals(current.get(newStart + offset))) {
                throw new IOException("Git diff matched different complete rows");
            }
            mapping[oldStart + offset] = newStart + offset;
        }
    }

    private static List<String> rows(String text, Runnable cancellationCheck, long deadline) throws IOException {
        var result = new ArrayList<String>();
        for (int start = 0; start < text.length(); ) {
            if ((result.size() & 255) == 0) checkRemaining(deadline, cancellationCheck);
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            result.add(text.substring(start, end));
            start = end;
        }
        return result;
    }

    private static int number(String value, int fallback) throws IOException {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid Git diff line number", invalid); }
    }

    private byte[] run(List<String> command, long deadline, Runnable cancellationCheck) throws IOException {
        checkRemaining(deadline, cancellationCheck);
        var builder = new ProcessBuilder(command);
        ReviewGitHistory.prepareEnvironment(builder.environment());
        Process process = builder.start();
        var output = new CompletableFuture<byte[]>();
        var errors = new CompletableFuture<byte[]>();
        Thread out = read(process, process.getInputStream(), limits.outputBytes(), output);
        Thread err = read(process, process.getErrorStream(), Math.min(limits.outputBytes(), 64 * 1024), errors);
        try {
            process.getOutputStream().close();
            while (!process.waitFor(Math.min(checkRemaining(deadline, cancellationCheck), TimeUnit.MILLISECONDS.toNanos(25)),
                    TimeUnit.NANOSECONDS)) { /* Poll cancellation while Git owns the worker. */ }
            if (process.exitValue() != 0 && process.exitValue() != 1) throw new IOException("Git diff is unavailable");
            errors.get(checkRemaining(deadline, cancellationCheck), TimeUnit.NANOSECONDS);
            return output.get(checkRemaining(deadline, cancellationCheck), TimeUnit.NANOSECONDS);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Git diff cancelled");
        } catch (ExecutionException | TimeoutException unavailable) {
            throw new IOException("Git diff output is unavailable", unavailable);
        } finally {
            process.destroyForcibly();
            close(process.getInputStream()); close(process.getErrorStream()); close(process.getOutputStream());
            out.interrupt(); err.interrupt();
            // Windows keeps input files open until the terminated process has actually exited.
            boolean interrupted = Thread.interrupted();
            try { process.waitFor(100, TimeUnit.MILLISECONDS); }
            catch (InterruptedException cancelled) { interrupted = true; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static Thread read(Process process, InputStream stream, int limit, CompletableFuture<byte[]> result) {
        return Thread.ofVirtual().start(() -> {
            try (stream; var bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int count; (count = stream.read(buffer)) != -1; ) {
                    if ((long) bytes.size() + count > limit) throw new IOException("Git diff output exceeded its limit");
                    bytes.write(buffer, 0, count);
                }
                result.complete(bytes.toByteArray());
            } catch (Exception unavailable) {
                result.completeExceptionally(unavailable);
                process.destroyForcibly();
            }
        });
    }

    private long deadline() { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis()); }

    private static long checkRemaining(long deadline, Runnable cancellationCheck) throws IOException {
        cancellationCheck.run();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Git diff cancelled");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Git diff timed out");
        return remaining;
    }

    private static void requireBackground() {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Git diff must run outside EDT");
    }

    private static void close(AutoCloseable stream) { try { stream.close(); } catch (Exception ignored) { } }
}
