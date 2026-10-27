package dev.doreview.ide.context;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.doreview.core.ContextMap;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;

import java.util.List;
import java.util.Map;

/** Independent syntax fixtures for the physical row at a following-guard boundary. */
public final class FollowingGuardBoundaryTest extends BasePlatformTestCase {
    public void testRemovingLaterGuardPreservesTheEarlierMultilineGuardClosingRow() {
        assertLaterGuardRemoval("tact", "");
    }

    public void testTolkRemovingLaterGuardPreservesTheEarlierMultilineGuardClosingRow() {
        assertLaterGuardRemoval("tolk", "");
    }

    public void testPythonRemovingLaterGuardPreservesTheEarlierMultilineGuardClosingRow() {
        assertLaterGuardRemoval("py", "");
    }

    public void testTactTrailingCommentDoesNotMakeTheClosingRowDependOnItsOwnGuard() {
        assertLaterGuardRemoval("tact", " // explanation");
    }

    public void testTolkTrailingCommentDoesNotMakeTheClosingRowDependOnItsOwnGuard() {
        assertLaterGuardRemoval("tolk", " // explanation");
    }

    public void testPythonTrailingCommentDoesNotMakeTheClosingRowDependOnItsOwnGuard() {
        assertLaterGuardRemoval("py", " # explanation");
    }

    public void testARealFollowingStatementOnTheSamePhysicalRowStillDependsOnTheGuard() {
        assertSameRowDependency("tact");
    }

    public void testTolkFollowingStatementOnTheSamePhysicalRowStillDependsOnTheGuard() {
        assertSameRowDependency("tolk");
    }

    public void testPythonFollowingStatementOnTheSamePhysicalRowStillDependsOnTheGuard() {
        assertSameRowDependency("py");
    }

    public void testTactChangedPrecedingGuardAndEarlyReturnStillInvalidateFollowingCode() {
        assertChangedPrecedingDependencies("tact");
    }

    public void testTolkChangedPrecedingGuardAndEarlyReturnStillInvalidateFollowingCode() {
        assertChangedPrecedingDependencies("tolk");
    }

    public void testPythonChangedPrecedingGuardAndEarlyReturnStillInvalidateFollowingCode() {
        assertChangedPrecedingDependencies("py");
    }

    private void assertLaterGuardRemoval(String extension, String trailingComment) {
        String separator = extension.equals("py") ? "" : ";";
        String later = "    require(x < 10, \"limit\")" + separator + "\n";
        String original = header(extension) + "    require(\n        x > 0,\n        \"allowed\"\n    )"
                + separator + trailingComment + "\n" + later + footer(extension);
        String current = original.replace(later, "");
        VirtualFile file = myFixture.configureByText("following_boundary." + extension, original).getVirtualFile();
        ContextMap before = analyze(file, original), after = analyze(file, current);
        assertProvider(extension, before);
        for (int row = 0; row <= 4; row++) {
            assertNotNull("The unchanged guard has valid context at row " + row, before.fingerprint(row));
            assertEquals("A later statement cannot change an earlier guard's context at row " + row,
                    before.fingerprint(row), after.fingerprint(row));
        }
        if (!extension.equals("py")) assertEquals(before.fingerprint(6), after.fingerprint(5));

        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), original,
                List.of(new ReviewedRange(0, original.length())), 1);
        var projection = ReviewHistoryEngine.project(history, current,
                Map.of(history.evidence().getFirst().snapshotId(), before), after, null);
        assertEquals(FileProjection.Status.REVIEWED, projection.rows().get(4).status());
        assertEquals(FileProjection.Reason.EXACT, projection.rows().get(4).reason());
    }

    private void assertSameRowDependency(String extension) {
        String separator = extension.equals("py") ? "" : ";";
        String original = header(extension) + "    require(x > 0, \"allowed\"); consume(x)" + separator
                + "\n" + footer(extension);
        String current = original.replace("x > 0", "x <= 0");
        VirtualFile file = myFixture.configureByText("same_row_boundary." + extension, original).getVirtualFile();
        ContextMap before = analyze(file, original), after = analyze(file, current);
        assertProvider(extension, before);
        assertNotNull(before.fingerprint(1));
        assertNotNull(after.fingerprint(1));
        assertFalse("A following statement on the same physical row still needs its guard",
                before.fingerprint(1).equals(after.fingerprint(1)));
    }

    private void assertChangedPrecedingDependencies(String extension) {
        boolean python = extension.equals("py");
        String separator = python ? "" : ";";
        String prepare = "    prepare()" + separator;
        String consume = "    consume(x)" + separator;
        String original = header(extension) + prepare + "\n    require(x > 0, \"allowed\")" + separator
                + (python ? " # gate\n    if x == 1:\n        return\n"
                          : " // gate\n    if (x == 1) {\n        return;\n    }\n")
                + consume + "\n" + footer(extension);
        VirtualFile file = myFixture.configureByText("preceding_boundary." + extension, original).getVirtualFile();
        ContextMap before = analyze(file, original);
        assertProvider(extension, before);
        for (String current : List.of(original.replace("x > 0", "x <= 0"),
                original.replace("x == 1", "x == 2"),
                original.replace(python ? "        return\n" : "        return;\n",
                        python ? "        inspect()\n" : "        inspect();\n"))) {
            ContextMap after = analyze(file, current);
            assertEquals("Code preceding all changed gates keeps its context",
                    fingerprint(before, original, prepare), fingerprint(after, current, prepare));
            assertFalse("Following code still depends on changed guards and early exits",
                    fingerprint(before, original, consume).equals(fingerprint(after, current, consume)));
        }
    }

    private static String header(String extension) {
        return extension.equals("py") ? "def sample(x):\n"
                : "fun sample(x: " + (extension.equals("tact") ? "Int" : "int") + ") {\n";
    }

    private static String footer(String extension) { return extension.equals("py") ? "" : "}\n"; }

    private static void assertProvider(String extension, ContextMap context) {
        assertTrue(context.provider(), context.provider().startsWith((extension.equals("py") ? "python" : extension) + ":"));
        assertTrue(context.provider(), context.provider().endsWith(":structure-v4"));
    }

    private static String fingerprint(ContextMap context, String text, String row) {
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) if (lines[index].equals(row)) {
            assertNotNull("Expected known structural context for " + row, context.fingerprint(index));
            return context.fingerprint(index);
        }
        throw new AssertionError("Fixture row is missing: " + row);
    }

    private ContextMap analyze(VirtualFile file, String text) {
        return ContextAnalysisManager.analyze(getProject(), file, text);
    }
}
