package dev.doreview.ide.context;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.doreview.core.ContextMap;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;

import java.util.List;
import java.util.Map;

/** Parser recovery must isolate sibling errors without certifying damaged or changed declarations. */
public final class ContextRecoveryBoundaryTest extends BasePlatformTestCase {
    private static final String TACT = "contract Sample {\n"
            + "    total: Int;\n"
            + "    init() {\n"
            + "        require(1 > 0, \"guard\");\n"
            + "        self.total = 0;\n"
            + "    }\n"
            + "    receive(\"first\") {\n"
            + "        self.total = 1;\n"
            + "    }\n"
            + "    receive(\"second\") {\n"
            + "        self.total = 2;\n"
            + "    }\n"
            + "}\n";

    public void testMalformedFollowingReceiveDoesNotPoisonClosedInitOrPreviousHandler() {
        VirtualFile file = open("recovery.tact", TACT);
        ContextMap baseline = analyze(file, TACT);
        assertTrue(baseline.provider(), baseline.provider().startsWith("tact:"));
        for (String header : List.of("receive(\"second\" {", "receive(message: ) {", "receive(\"second\")f {")) {
            String current = TACT.replace("receive(\"second\") {", header);
            assertMalformed(current);
            ContextMap recovered = analyze(file, current);
            for (int line = 2; line <= 8; line++) {
                assertKnownEqual(baseline, recovered, line);
            }
            assertNull("The malformed handler's body must remain unknown", recovered.fingerprint(10));
            FileProjection projection = project(file, TACT, current);
            for (int line = 2; line <= 8; line++) assertStatus(projection, line, FileProjection.Status.REVIEWED);
            assertStatus(projection, 10, FileProjection.Status.NEEDS_REVIEW);
            assertEquals(FileProjection.Reason.UNKNOWN_CONTEXT, projection.rows().get(10).reason());
        }
        assertEquals("Analyzing historical/recovery PSI must not edit the original document",
                TACT, myFixture.getEditor().getDocument().getText());
    }

    public void testChangingEarlierBodyTextCannotReuseItsReviewDuringFollowingError() {
        VirtualFile file = open("changed-body.tact", TACT);
        String current = brokenFollowing().replace("self.total = 0;", "self.total = 7;");
        assertMalformed(current);
        FileProjection projection = project(file, TACT, current);

        assertStatus(projection, 4, FileProjection.Status.CHANGED);
        assertStatus(projection, 7, FileProjection.Status.REVIEWED);
        assertStatus(projection, 10, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testChangedInitAndPreviousHandlerHeadersStillChangeTheirOwnBodyContext() {
        VirtualFile file = open("changed-header.tact", TACT);
        ContextMap baseline = analyze(file, TACT);
        String initChanged = brokenFollowing().replace("init()", "init(value: Int)");
        ContextMap initContext = analyze(file, initChanged);
        assertKnownDifferent(baseline, initContext, 4);
        assertKnownEqual(baseline, initContext, 7);
        assertStatus(project(file, TACT, initChanged), 4, FileProjection.Status.NEEDS_REVIEW);

        String handlerChanged = brokenFollowing().replace("receive(\"first\")", "receive(\"other\")");
        ContextMap handlerContext = analyze(file, handlerChanged);
        assertKnownEqual(baseline, handlerContext, 4);
        assertKnownDifferent(baseline, handlerContext, 7);
        assertStatus(project(file, TACT, handlerChanged), 7, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testChangedContractHeaderCannotBeHiddenByFollowingHandlerError() {
        VirtualFile file = open("changed-contract.tact", TACT);
        String current = brokenFollowing().replace("contract Sample", "contract Other");
        ContextMap baseline = analyze(file, TACT), changed = analyze(file, current);
        for (int line : new int[]{4, 7}) assertKnownDifferent(baseline, changed, line);
        FileProjection projection = project(file, TACT, current);
        for (int line : new int[]{4, 7}) {
            assertStatus(projection, line, FileProjection.Status.NEEDS_REVIEW);
            assertEquals(FileProjection.Reason.CONTEXT_CHANGED, projection.rows().get(line).reason());
        }
    }

    public void testMalformedContractHeaderCannotExemptClosedChildDeclarations() {
        VirtualFile file = open("broken-contract-header.tact", TACT);
        for (String header : List.of("contract {", "contract Sample with {")) {
            String current = TACT.replace("contract Sample {", header);
            assertMalformed(current);
            ContextMap changed = analyze(file, current);
            for (int line : new int[]{4, 7}) {
                assertNull("A damaged enclosing contract header remains an unknown dependency", changed.fingerprint(line));
                assertStatus(project(file, TACT, current), line, FileProjection.Status.NEEDS_REVIEW);
            }
        }
    }

    public void testSharedClosingBraceAndMalformedFollowingHeaderRemainUnknownOnThatPhysicalLine() {
        String baseline = TACT.replace("    }\n    receive(\"second\") {", "    } receive(\"second\") {");
        VirtualFile file = open("shared-boundary.tact", baseline);
        String current = baseline.replace("receive(\"second\") {", "receive(\"second\" {");
        assertMalformed(current);
        ContextMap before = analyze(file, baseline), changed = analyze(file, current);
        assertKnownEqual(before, changed, 4);
        assertKnownEqual(before, changed, 7);
        assertNotNull(before.fingerprint(8));
        assertNull("A closing declaration cannot exempt an error elsewhere on its physical row", changed.fingerprint(8));
        assertNull(changed.fingerprint(9));
        FileProjection projection = project(file, baseline, current);
        assertStatus(projection, 7, FileProjection.Status.REVIEWED);
        assertFalse("The shared changed row must never retain confirmed coverage",
                projection.rows().get(8).status() == FileProjection.Status.REVIEWED);
        assertStatus(projection, 9, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testChangedGuardStillInvalidatesFollowingRowsOfTheClosedInit() {
        VirtualFile file = open("changed-guard.tact", TACT);
        String current = brokenFollowing().replace("1 > 0", "1 > 1");
        ContextMap baseline = analyze(file, TACT), changed = analyze(file, current);
        assertKnownDifferent(baseline, changed, 4);
        assertKnownEqual(baseline, changed, 7);
        FileProjection projection = project(file, TACT, current);
        assertStatus(projection, 3, FileProjection.Status.CHANGED);
        assertStatus(projection, 4, FileProjection.Status.NEEDS_REVIEW);
        assertStatus(projection, 7, FileProjection.Status.REVIEWED);
    }

    public void testUnclosedInitIsNotMistakenForAnUnchangedClosedDeclaration() {
        VirtualFile file = open("unclosed-init.tact", TACT);
        String current = brokenFollowing().replace("    }\n    receive(\"first\")", "    \n    receive(\"first\")");
        assertMalformed(current);
        ContextMap changed = analyze(file, current);
        assertNull("An unclosed init cannot be confirmed from a preceding valid prefix", changed.fingerprint(4));
        assertStatus(project(file, TACT, current), 4, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testErrorWithinInitKeepsItsBodyUnknownWithoutPoisoningClosedSibling() {
        VirtualFile file = open("broken-init.tact", TACT);
        String current = brokenFollowing().replace("1 > 0", "1 >");
        assertMalformed(current);
        ContextMap baseline = analyze(file, TACT), changed = analyze(file, current);
        assertNull("A parse error inside the reviewed init remains unknown", changed.fingerprint(4));
        assertKnownEqual(baseline, changed, 7);
        FileProjection projection = project(file, TACT, current);
        assertStatus(projection, 4, FileProjection.Status.NEEDS_REVIEW);
        assertStatus(projection, 7, FileProjection.Status.REVIEWED);
    }

    public void testRecoveryCannotOverrideAnExplicitUnknownNestedBlockInAClosedInit() {
        String baseline = TACT.replace("        self.total = 0;",
                "        {\n            self.total = 0;\n        }");
        VirtualFile file = open("explicit-unknown.tact", baseline);
        ContextMap before = analyze(file, baseline);
        assertNull("The adapter deliberately leaves standalone block context unknown even in valid PSI", before.fingerprint(5));
        String current = baseline.replace("receive(\"second\") {", "receive(\"second\" {");
        assertMalformed(current);
        ContextMap recovered = analyze(file, current);

        assertKnownEqual(before, recovered, 3);
        assertNull("Exempting a synthetic recovery expansion cannot clear an adapter's explicit unknown scope", recovered.fingerprint(5));
        assertKnownEqual(before, recovered, 9);
        FileProjection projection = project(file, baseline, current);
        assertStatus(projection, 3, FileProjection.Status.REVIEWED);
        assertStatus(projection, 5, FileProjection.Status.NEEDS_REVIEW);
        assertEquals(FileProjection.Reason.UNKNOWN_CONTEXT, projection.rows().get(5).reason());
        assertStatus(projection, 9, FileProjection.Status.REVIEWED);
    }

    public void testClosedPythonAndTolkFunctionSurviveAnErrorInTheNextDeclaration() {
        for (String extension : List.of("py", "tolk")) {
            String source = extension.equals("py")
                    ? "def first():\n    return 1\n\ndef second(value):\n    return value\n"
                    : "fun first(): int {\n    return 1;\n}\nfun second(value: int): int {\n    return value;\n}\n";
            VirtualFile file = open("following-error." + extension, source);
            String current = extension.equals("py") ? source.replace("def second(value):", "def second(value:")
                    : source.replace("fun second(value: int): int {", "fun second(value: int: int {");
            assertMalformed(current);
            ContextMap baseline = analyze(file, source), changed = analyze(file, current);
            assertTrue(changed.provider(), changed.provider().startsWith(extension.equals("py") ? "python:" : "tolk:"));
            assertKnownEqual(baseline, changed, 1);
            assertNull("The malformed following declaration must remain unknown", changed.fingerprint(4));
            FileProjection projection = project(file, source, current);
            assertStatus(projection, 1, FileProjection.Status.REVIEWED);
            assertStatus(projection, 4, FileProjection.Status.NEEDS_REVIEW);
        }
    }

    public void testTolkFunctionMissingItsOwnClosingBraceCannotBorrowTheNextFunctionBoundary() {
        String baseline = "fun first(): int {\n    return 1;\n}\nfun second(value: int): int {\n    return value;\n}\n";
        VirtualFile file = open("unclosed-function.tolk", baseline);
        String current = baseline.replace("}\nfun second", "\nfun second");
        assertMalformed(current);
        ContextMap before = analyze(file, baseline), changed = analyze(file, current);
        assertNotNull(before.fingerprint(1));
        assertNull("A later closing brace cannot certify an unclosed previous function", changed.fingerprint(1));
        assertStatus(project(file, baseline, current), 1, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testExactValidSnapshotReturnsAfterTemporaryFollowingErrorWithoutRewritingHistory() {
        VirtualFile file = open("restored.tact", TACT);
        ContextMap baseline = analyze(file, TACT);
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), TACT,
                List.of(new ReviewedRange(0, TACT.length())), 1);
        Map<String, ContextMap> contexts = Map.of(history.evidence().getFirst().snapshotId(), baseline);
        String broken = brokenFollowing();
        assertMalformed(broken);
        ContextMap temporary = analyze(file, broken);
        FileProjection whileBroken = ReviewHistoryEngine.project(history, broken, contexts, temporary, null);
        assertStatus(whileBroken, 4, FileProjection.Status.REVIEWED);
        assertStatus(whileBroken, 10, FileProjection.Status.NEEDS_REVIEW);

        ContextMap restored = analyze(file, TACT);
        assertEquals("Temporary recovery errors cannot poison a valid snapshot's cached context", baseline, restored);
        FileProjection confirmed = ReviewHistoryEngine.project(history, TACT, contexts, restored, null);
        assertEquals(List.of(new ReviewedRange(0, TACT.length())), confirmed.reviewedRanges());
        assertEquals(1, history.evidence().size());
        assertEquals(1, history.snapshots().size());
        assertEmpty(history.revocations());
        assertEquals(TACT, history.snapshots().values().iterator().next().text());
        assertEquals(TACT, myFixture.getEditor().getDocument().getText());
    }

    private static String brokenFollowing() {
        return TACT.replace("receive(\"second\") {", "receive(\"second\" {");
    }

    private VirtualFile open(String name, String source) {
        var file = myFixture.configureByText(name, source);
        assertFalse("The baseline must be a syntactically valid parser fixture", ReadAction.computeBlocking(() ->
                PsiTreeUtil.findChildOfType(file, PsiErrorElement.class) != null));
        return file.getVirtualFile();
    }

    private void assertMalformed(String source) {
        assertTrue("The negative fixture must contain a real parser error", ReadAction.computeBlocking(() -> {
            var copy = PsiFileFactory.getInstance(getProject()).createFileFromText(source, myFixture.getFile());
            return PsiTreeUtil.findChildOfType(copy, PsiErrorElement.class) != null;
        }));
    }

    private ContextMap analyze(VirtualFile file, String source) {
        return ContextAnalysisManager.analyze(getProject(), file, source);
    }

    private FileProjection project(VirtualFile file, String baseline, String current) {
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), baseline,
                List.of(new ReviewedRange(0, baseline.length())), 1);
        return ReviewHistoryEngine.project(history, current,
                Map.of(history.evidence().getFirst().snapshotId(), analyze(file, baseline)), analyze(file, current), null);
    }

    private static void assertKnownEqual(ContextMap baseline, ContextMap current, int line) {
        assertNotNull("The baseline row must have known context: " + line, baseline.fingerprint(line));
        assertNotNull("A separate syntax error cannot erase closed context: " + line, current.fingerprint(line));
        assertEquals("Unchanged closed declaration context: " + line, baseline.fingerprint(line), current.fingerprint(line));
    }

    private static void assertKnownDifferent(ContextMap baseline, ContextMap current, int line) {
        assertNotNull(baseline.fingerprint(line));
        assertNotNull("Changed valid context should be explicit, not a global parse-error fallback", current.fingerprint(line));
        assertFalse("Changed enclosing header/guard must invalidate row " + line,
                baseline.fingerprint(line).equals(current.fingerprint(line)));
    }

    private static void assertStatus(FileProjection projection, int line, FileProjection.Status expected) {
        assertEquals("Projection status at row " + line, expected, projection.rows().get(line).status());
    }
}
