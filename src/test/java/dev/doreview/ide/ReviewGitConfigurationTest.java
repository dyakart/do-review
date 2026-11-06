package dev.doreview.ide;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Git's pointer layout is sufficient to identify config hints without launching a process on EDT. */
public final class ReviewGitConfigurationTest extends TestCase {
    public void testCommonAndWorktreeConfigurationAreScopedToTheRepository() throws Exception {
        Path temporary = Files.createTempDirectory("doreview-git-configuration-");
        try {
            Path common = Files.createDirectories(temporary.resolve("repo/.git"));
            Path root = Files.createDirectories(temporary.resolve("linked"));
            Path privateGit = Files.createDirectories(common.resolve("worktrees/linked"));
            Files.writeString(root.resolve(".git"), "gitdir: ../repo/.git/worktrees/linked\n");
            Files.writeString(privateGit.resolve("commondir"), "../..\n");
            assertTrue(ReviewGitConfiguration.affects(root, common.resolve("config")));
            assertTrue(ReviewGitConfiguration.affects(root, privateGit.resolve("config.worktree")));
            assertFalse(ReviewGitConfiguration.affects(root, temporary.resolve("unrelated/.git/config")));
            assertFalse(ReviewGitConfiguration.affects(root, common.resolve("HEAD")));
            assertFalse(ReviewGitConfiguration.affects(root, common.resolve("config.worktree")));
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
