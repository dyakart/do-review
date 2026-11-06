package dev.doreview.ide;

import junit.framework.TestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SharedHistoryFilesTest extends TestCase {
    private Path shared;

    @Override protected void setUp() throws Exception { shared = Files.createTempDirectory("doreview-immutable-"); }
    @Override protected void tearDown() throws Exception {
        try (var paths = Files.walk(shared)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public void testConcurrentIdenticalPublicationIsIdempotent() throws Exception {
        byte[] bytes = "same immutable contents\n".getBytes(StandardCharsets.UTF_8);
        var outcomes = publishConcurrently(bytes, bytes);
        assertNull(outcomes[0]); assertNull(outcomes[1]);
        assertTrue(Arrays.equals(bytes, Files.readAllBytes(shared.resolve("event.json"))));
    }

    public void testConcurrentConflictingPublicationNeverReplacesTheWinner() throws Exception {
        byte[] first = "first immutable contents\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second immutable contents\n".getBytes(StandardCharsets.UTF_8);
        var outcomes = publishConcurrently(first, second);
        assertTrue("Exactly one publisher must reject the collision", (outcomes[0] == null) != (outcomes[1] == null));
        IOException failure = outcomes[0] == null ? outcomes[1] : outcomes[0];
        assertTrue(failure.getMessage(), failure.getMessage().contains("Conflicting immutable review file"));
        assertTrue(Arrays.equals(outcomes[0] == null ? first : second, Files.readAllBytes(shared.resolve("event.json"))));
    }

    private IOException[] publishConcurrently(byte[] first, byte[] second) throws Exception {
        var barrier = new CyclicBarrier(2);
        var files = new SharedHistoryFiles(shared, () -> {
            try { barrier.await(10, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> publish(files, first));
            var right = executor.submit(() -> publish(files, second));
            return new IOException[] { left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS) };
        }
    }

    private IOException publish(SharedHistoryFiles files, byte[] bytes) {
        try { files.writeImmutable(shared.resolve("event.json"), bytes); return null; }
        catch (IOException conflict) { return conflict; }
    }
}
