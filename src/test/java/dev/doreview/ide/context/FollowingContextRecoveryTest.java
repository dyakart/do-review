package dev.doreview.ide.context;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.doreview.core.ContextMap;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;
import org.tonstudio.tact.lang.TactTypes;
import org.tonstudio.tact.lang.psi.TactContractType;
import org.tonstudio.tact.lang.psi.TactMessageFunctionDeclaration;

import java.util.List;
import java.util.Map;

/** Recovery after malformed preceding syntax must preserve proof boundaries and actual changes. */
public final class FollowingContextRecoveryTest extends BasePlatformTestCase {
    private static final String SOURCE = "contract Sample {\n"
            + "    total: Int;\n"
            + "    init() { self.total = 0; }\n"
            + "    receive(\"upper\") {\n"
            + "        if (self.total > 0) {\n"
            + "            self.total = 1;\n"
            + "        }\n"
            + "    }\n"
            + "    receive(\"lower\") {\n"
            + "        require(self.total >= 0, \"guard\");\n"
            + "        if (self.total > 1) {\n"
            + "            self.total = 2;\n"
            + "        }\n"
            + "    }\n"
            + "}\n";

    public void testUnexpectedUpperHeaderTokenRecoversFollowingHandlerWithNestedBodyAndGuard() {
        VirtualFile file = open("following.tact", SOURCE);
        assertTrue("Baseline lower declaration must be a closed direct contract member", hasClosedLowerPeer(SOURCE));
        String current = typo();
        assertMalformed(current);
        assertFalse("This parser regression loses the lower declaration in the raw damaged PSI", hasClosedLowerPeer(current));
        ContextMap baseline = analyze(file, SOURCE), recovered = analyze(file, current);
        assertTrue(recovered.provider(), recovered.provider().startsWith("tact:"));
        FileProjection projection = project(file, SOURCE, current);
        for (int line = 8; line <= 13; line++) {
            assertKnownEqual(baseline, recovered, line);
            assertStatus(projection, line, FileProjection.Status.REVIEWED);
        }
        assertFalse("The damaged upper handler itself cannot inherit confirmed coverage",
                projection.rows().get(5).status() == FileProjection.Status.REVIEWED);
        assertEquals("Recovery analyzes copies and cannot repair the user's document", SOURCE,
                myFixture.getEditor().getDocument().getText());
    }

    public void testChangedLowerLiteralRemainsChangedAfterUpperHeaderRecovery() {
        VirtualFile file = open("lower-literal.tact", SOURCE);
        String current = typo().replace("self.total = 2;", "self.total = 7;");
        assertMalformed(current);
        FileProjection projection = project(file, SOURCE, current);
        assertStatus(projection, 11, FileProjection.Status.CHANGED);
        assertEquals(FileProjection.Reason.TEXT_CHANGED, projection.rows().get(11).reason());
        assertStatus(projection, 9, FileProjection.Status.REVIEWED);
    }

    public void testChangedLowerHeaderGuardAndConditionStillInvalidateBodyContext() {
        VirtualFile file = open("lower-context.tact", SOURCE);
        ContextMap baseline = analyze(file, SOURCE);
        for (String[] change : new String[][]{{"receive(\"lower\")", "receive(\"other\")"},
                {"self.total >= 0", "self.total >= 1"}, {"self.total > 1", "self.total > 2"}}) {
            String current = typo().replace(change[0], change[1]);
            assertMalformed(current);
            ContextMap changed = analyze(file, current);
            assertNotNull("A recovered valid lower header/guard remains explicit context", changed.fingerprint(11));
            assertFalse("Recovery cannot substitute an old context for a changed lower dependency",
                    baseline.fingerprint(11).equals(changed.fingerprint(11)));
            FileProjection projection = project(file, SOURCE, current);
            assertStatus(projection, 11, FileProjection.Status.NEEDS_REVIEW);
            assertEquals(FileProjection.Reason.CONTEXT_CHANGED, projection.rows().get(11).reason());
        }
    }

    public void testInsertedLowerRowCannotInheritProofDuringRecovery() {
        VirtualFile file = open("lower-insertion.tact", SOURCE);
        String current = typo().replace("            self.total = 2;\n",
                "            self.total = 2;\n            newOperation();\n");
        assertMalformed(current);
        FileProjection projection = project(file, SOURCE, current);
        assertStatus(projection, 11, FileProjection.Status.REVIEWED);
        assertStatus(projection, 12, FileProjection.Status.UNREVIEWED);
        assertTrue("New source must not receive an old proof while repairing unrelated PSI",
                projection.rows().get(12).evidenceRefs().isEmpty());
    }

    public void testUnclosedUpperDelimitersCannotCertifyFollowingRows() {
        VirtualFile file = open("unclosed-upper.tact", SOURCE);
        String missingParen = SOURCE.replace("receive(\"upper\") {", "receive(\"upper\" {");
        String typed = SOURCE.replace("receive(\"upper\")", "receive(message: Int)");
        String missingTypedParen = typed.replace("receive(message: Int) {", "receive(message: Int {");
        assertTrue("Raw parser recovery can expose a closed peer despite unclosed upper parameters",
                hasClosedLowerPeer(missingTypedParen));
        List<String> ambiguous = List.of(missingParen, missingTypedParen,
                SOURCE.replace("receive(\"upper\") {", "receive(\"upper\")"),
                typo().replace("    }\n    receive(\"lower\")", "    \n    receive(\"lower\")"));
        for (String current : ambiguous) {
            assertMalformed(current);
            FileProjection projection = project(file, SOURCE, current);
            assertStatus(projection, 2, FileProjection.Status.REVIEWED);
            assertNotGreen(projection, 11);
        }
    }

    public void testHeaderGapPunctuationAndKeywordsAreNeverMasked() {
        VirtualFile file = open("unsupported-gap.tact", SOURCE);
        for (String gap : List.of("+", "a + b", "return")) {
            String current = SOURCE.replace("receive(\"upper\") {", "receive(\"upper\") " + gap + " {");
            assertMalformed(current);
            PsiFile[] parsed = ReadAction.computeBlocking(() -> {
                PsiFile raw = copy(current);
                return new PsiFile[]{raw, TactSyntaxRecovery.prepare(raw, current).file()};
            });
            assertSame("Unsupported punctuation or keywords cannot be masked by a parser retry", parsed[0], parsed[1]);
            assertNotGreen(project(file, SOURCE, current), 5);
        }
    }

    public void testBracesAndParenthesesInCommentsAndStringsDoNotConfuseTheRecoveryFence() {
        String baseline = SOURCE.replace("receive(\"upper\") {", "receive(\"upper\") /* } ) { ( */ {")
                .replace("self.total = 1;", "require(true, \"} ) { (\"); // } ) { (");
        VirtualFile file = open("lexical-fence.tact", baseline);
        String current = baseline.replace("/* } ) { ( */ {", "/* } ) { ( */ a {");
        assertMalformed(current);
        ContextMap before = analyze(file, baseline), recovered = analyze(file, current);
        for (int line = 8; line <= 13; line++) assertKnownEqual(before, recovered, line);
        FileProjection projection = project(file, baseline, current);
        assertStatus(projection, 11, FileProjection.Status.REVIEWED);
        assertNotGreen(projection, 5);
    }

    public void testRecoveryWorkLimitsFailClosedInsteadOfCertifyingLaterRows() {
        VirtualFile file = open("bounded-recovery.tact", SOURCE);
        String distantBody = SOURCE.replace("receive(\"upper\") {", "receive(\"upper\")" + "a".repeat(65) + " {");
        assertMalformed(distantBody);
        assertNotGreen(project(file, SOURCE, distantBody), 11);

        // Each separate block comment and intervening whitespace is a lexer token.
        String manyTokens = typo().replace("    total: Int;", "    total: Int; " + "/*x*/ ".repeat(50_001));
        assertMalformed(manyTokens);
        assertNull("Exhausting the bounded lexer pass must keep later context unknown", analyze(file, manyTokens).fingerprint(11));
        assertStatus(project(file, SOURCE, manyTokens), 11, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testUnclosedUpperStringAndCommentCannotCertifyFollowingRows() {
        VirtualFile file = open("unclosed-lexeme.tact", SOURCE);
        for (String current : List.of(SOURCE.replace("receive(\"upper\") {", "receive(\"upper) {"),
                SOURCE.replace("receive(\"upper\") {", "receive(\"upper\") /* {"))) {
            assertMalformed(current);
            FileProjection projection = project(file, SOURCE, current);
            assertStatus(projection, 2, FileProjection.Status.REVIEWED);
            assertNotGreen(projection, 11);
        }
    }

    public void testMalformedOwnerHeaderCannotBeExemptedByFollowingRecovery() {
        VirtualFile file = open("owner-header.tact", SOURCE);
        String current = typo().replace("contract Sample {", "contract {");
        assertMalformed(current);
        assertNull(analyze(file, current).fingerprint(11));
        assertNotGreen(project(file, SOURCE, current), 11);
    }

    public void testPhysicalLineSharedWithUpperErrorIsNotWhollyConfirmed() {
        String baseline = SOURCE.replace("    receive(\"upper\") {\n        if (self.total > 0) {\n"
                        + "            self.total = 1;\n        }\n    }\n    receive(\"lower\") {",
                "    receive(\"upper\") { if (self.total > 0) { self.total = 1; } } receive(\"lower\") {");
        VirtualFile file = open("shared-line.tact", baseline);
        String current = baseline.replace("receive(\"upper\") {", "receive(\"upper\")a {");
        assertMalformed(current);
        ContextMap before = analyze(file, baseline), recovered = analyze(file, current);
        assertNull("The physical row contains damaged syntax outside the lower declaration", recovered.fingerprint(3));
        assertKnownEqual(before, recovered, 6);
        FileProjection projection = project(file, baseline, current);
        assertNotGreen(projection, 3);
        assertStatus(projection, 6, FileProjection.Status.REVIEWED);
    }

    public void testNestedPartiallyRecoveredHandlerCannotBecomeAContractPeer() {
        VirtualFile file = open("nested-recovery.tact", SOURCE);
        String current = typo().replace("    }\n    receive(\"lower\")", "    \n    receive(\"lower\")")
                .replace("    }\n}\n", "    }\n    }\n}\n");
        assertMalformed(current);
        assertNotGreen(project(file, SOURCE, current), 11);
    }

    public void testExactReturnAfterHeaderErrorRestoresContextWithoutChangingHistory() {
        VirtualFile file = open("return-to-valid.tact", SOURCE);
        ContextMap baseline = analyze(file, SOURCE);
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), SOURCE,
                List.of(new ReviewedRange(0, SOURCE.length())), 1);
        var contexts = Map.of(history.evidence().getFirst().snapshotId(), baseline);
        String current = typo();
        FileProjection whileBroken = ReviewHistoryEngine.project(history, current, contexts, analyze(file, current), null);
        assertStatus(whileBroken, 11, FileProjection.Status.REVIEWED);
        assertNotGreen(whileBroken, 5);

        ContextMap restored = analyze(file, SOURCE);
        assertEquals(baseline, restored);
        FileProjection confirmed = ReviewHistoryEngine.project(history, SOURCE, contexts, restored, null);
        assertEquals(List.of(new ReviewedRange(0, SOURCE.length())), confirmed.reviewedRanges());
        assertEquals(1, history.evidence().size());
        assertEquals(1, history.snapshots().size());
        assertEmpty(history.revocations());
        assertEquals(SOURCE, history.snapshots().values().iterator().next().text());
    }

    private static String typo() { return SOURCE.replace("receive(\"upper\") {", "receive(\"upper\")a {"); }

    private VirtualFile open(String name, String source) {
        var file = myFixture.configureByText(name, source);
        assertFalse("Baseline parser fixture must be valid", ReadAction.computeBlocking(() ->
                PsiTreeUtil.findChildOfType(file, PsiErrorElement.class) != null));
        return file.getVirtualFile();
    }

    private PsiFile copy(String source) {
        return PsiFileFactory.getInstance(getProject()).createFileFromText(source, myFixture.getFile());
    }

    private void assertMalformed(String source) {
        assertTrue("The fixture must contain a real parser error", ReadAction.computeBlocking(() ->
                PsiTreeUtil.findChildOfType(copy(source), PsiErrorElement.class) != null));
    }

    private boolean hasClosedLowerPeer(String source) {
        return ReadAction.computeBlocking(() -> {
            int start = source.indexOf("receive(\"lower\")");
            for (var handler : PsiTreeUtil.findChildrenOfType(copy(source), TactMessageFunctionDeclaration.class)) {
                if (handler.getTextRange().getStartOffset() != start || !(handler.getParent() instanceof TactContractType)) continue;
                var block = handler.getBlock();
                if (block == null || PsiTreeUtil.findChildOfType(handler, PsiErrorElement.class) != null) continue;
                var opening = block.getNode().findChildByType(TactTypes.LBRACE);
                var closing = block.getNode().findChildByType(TactTypes.RBRACE);
                if (opening != null && closing != null && opening.getTreeParent() == block.getNode()
                        && closing.getTreeParent() == block.getNode()
                        && closing.getTextRange().getEndOffset() == handler.getTextRange().getEndOffset()) return true;
            }
            return false;
        });
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
        assertNotNull(baseline.fingerprint(line));
        assertNotNull("The following closed declaration has independently confirmed context at row " + line, current.fingerprint(line));
        assertEquals(baseline.fingerprint(line), current.fingerprint(line));
    }

    private static void assertStatus(FileProjection projection, int line, FileProjection.Status expected) {
        assertEquals("Projection status at row " + line, expected, projection.rows().get(line).status());
    }

    private static void assertNotGreen(FileProjection projection, int line) {
        assertFalse("Ambiguous/damaged syntax must not certify row " + line,
                projection.rows().get(line).status() == FileProjection.Status.REVIEWED);
    }
}
