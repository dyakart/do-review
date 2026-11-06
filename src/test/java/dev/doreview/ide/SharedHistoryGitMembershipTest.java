package dev.doreview.ide;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Only exact event blobs in the current commit permit cross-branch local publications. */
public final class SharedHistoryGitMembershipTest extends TestCase {
    private static final String FIRST = "11111111-1111-1111-1111-111111111111";
    private static final String SECOND = "22222222-2222-2222-2222-222222222222";
    private static final String PAYLOAD = "{\"author\":\"reviewer ü\"}\n";
    private Path root;

    @Override protected void setUp() throws Exception {
        root = Files.createTempDirectory("doreview-membership-");
        init();
    }

    @Override protected void tearDown() throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                var dos = Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (dos != null) dos.setReadOnly(false);
                Files.deleteIfExists(path);
            }
        }
    }

    public void testUnbornAndUntrackedEventsAreNotCommitted() throws Exception {
        assertEquals(Set.of(), SharedHistoryGitMembership.contains(root, Map.of(FIRST, PAYLOAD)));
        write(FIRST, PAYLOAD);
        assertEquals(Set.of(), SharedHistoryGitMembership.contains(root, Map.of(FIRST, PAYLOAD)));
    }

    public void testRequiresMatchingCommittedPathAndBlobBytes() throws Exception {
        write(FIRST, PAYLOAD);
        commit();
        write(SECOND, PAYLOAD);
        assertEquals(Set.of(FIRST), SharedHistoryGitMembership.contains(root, Map.of(FIRST, PAYLOAD, SECOND, PAYLOAD)));
        assertEquals(Set.of(), SharedHistoryGitMembership.contains(root, Map.of(FIRST, PAYLOAD.stripTrailing())));
        Files.writeString(root.resolve(path(FIRST)), "{\"dirty\":true}\n");
        assertEquals(Set.of(FIRST), SharedHistoryGitMembership.contains(root, Map.of(FIRST, PAYLOAD)));
        assertEquals(Set.of(), SharedHistoryGitMembership.contains(root, Map.of(FIRST, "{\"dirty\":true}\n")));
    }

    public void testForeignLocalFileIsAcceptedOnlyOncePresentInCurrentHead() throws Exception {
        Files.writeString(root.resolve("source.txt"), "source\n"); commit();
        git("checkout", "-qb", "feature");
        write(FIRST, PAYLOAD); commit();
        assertEquals(Set.of(FIRST), SharedHistoryGitMembership.contains(root, Map.of(FIRST, PAYLOAD)));
        git("checkout", "-q", "original");
        write(FIRST, PAYLOAD);
        assertEquals(Set.of(), SharedHistoryGitMembership.contains(root, Map.of(FIRST, PAYLOAD)));
        commit();
        assertEquals(Set.of(FIRST), SharedHistoryGitMembership.contains(root, Map.of(FIRST, PAYLOAD)));
    }

    public void testWrongShardCannotEstablishMembership() throws Exception {
        Path wrong = root.resolve(".doreview/v1/events/ff/" + FIRST + ".json");
        Files.createDirectories(wrong.getParent()); Files.writeString(wrong, PAYLOAD); commit();
        assertEquals(Set.of(), SharedHistoryGitMembership.contains(root, Map.of(FIRST, PAYLOAD)));
    }

    public void testSha256ObjectFormatUsesGitBlobIdentity() throws Exception {
        try (var paths = Files.walk(root.resolve(".git"))) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
        git("init", "-q", "-b", "original", "--object-format=sha256"); initConfig();
        write(FIRST, PAYLOAD); commit();
        assertEquals(Set.of(FIRST), SharedHistoryGitMembership.contains(root, Map.of(FIRST, PAYLOAD)));
    }

    private void init() throws Exception { git("init", "-q", "-b", "original", "--template="); initConfig(); }
    private void initConfig() throws Exception {
        git("config", "user.name", "Membership Test"); git("config", "user.email", "membership@example.invalid");
        git("config", "commit.gpgsign", "false");
    }
    private static String path(String id) { return ".doreview/v1/events/" + id.substring(0, 2) + "/" + id + ".json"; }
    private void write(String id, String payload) throws Exception {
        Path target = root.resolve(path(id)); Files.createDirectories(target.getParent());
        Files.writeString(target, payload, StandardCharsets.UTF_8);
    }
    private void commit() throws Exception { git("add", "."); git("commit", "-qm", "Record test fixture"); }
    private String git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", root.toString(),
                "-c", "core.hooksPath=" + root.resolve("disabled-hooks"))); command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        ReviewGitHistory.prepareEnvironment(builder.environment());
        var process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Git fixture command timed out"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.exitValue());
        return output;
    }
}
