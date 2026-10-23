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

/** Actual Tact ASM PSI must preserve unchanged reviews without trusting changed stack programs. */
public final class TactAsmContextTest extends BasePlatformTestCase {
    private static final String INLINE = "asm fun clearNotificationActions() { NEWC ENDC c5 POP }\n";
    private static final String MULTILINE = "asm fun clearNotificationActions() {\n"
            + "    NEWC\n"
            + "    ENDC\n"
            + "    c5 POP\n"
            + "}\n";

    public void testUnchangedInlineAsmIsReviewed() {
        VirtualFile file = open(INLINE);
        assertNotNull(analyze(file, INLINE).fingerprint(0));
        assertStatus(project(file, INLINE, INLINE), 0, FileProjection.Status.REVIEWED);
        assertEquals(INLINE, myFixture.getEditor().getDocument().getText());
    }

    public void testUnrelatedFunctionEditKeepsMultilineAsmReviewed() {
        String baseline = MULTILINE + "fun other(): Int { return 1; }\n";
        VirtualFile file = open(baseline);
        FileProjection projection = project(file, baseline, baseline.replace("return 1;", "return 2;"));
        for (int row = 0; row < 5; row++) assertStatus(projection, row, FileProjection.Status.REVIEWED);
        assertStatus(projection, 5, FileProjection.Status.CHANGED);
    }

    public void testChangedInstructionRequiresRecheckingUnchangedAsmRows() {
        VirtualFile file = open(MULTILINE);
        FileProjection projection = project(file, MULTILINE, MULTILINE.replace("NEWC", "NOP"));
        assertStatus(projection, 1, FileProjection.Status.CHANGED);
        for (int row : new int[]{0, 2, 3, 4}) {
            assertStatus(projection, row, FileProjection.Status.NEEDS_REVIEW);
            assertEquals(FileProjection.Reason.CONTEXT_CHANGED, projection.rows().get(row).reason());
        }
    }

    public void testChangedAsmHeaderRequiresRecheckingBody() {
        VirtualFile file = open(MULTILINE);
        FileProjection projection = project(file, MULTILINE, MULTILINE.replace("clearNotificationActions", "clearActions"));
        assertStatus(projection, 0, FileProjection.Status.CHANGED);
        for (int row = 1; row < 5; row++) assertStatus(projection, row, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testRestoringAsmSnapshotRestoresUnrevokedReview() {
        VirtualFile file = open(MULTILINE);
        var history = reviewed(MULTILINE);
        Map<String, ContextMap> contexts = Map.of(history.evidence().getFirst().snapshotId(), analyze(file, MULTILINE));
        String changed = MULTILINE.replace("NEWC", "NOP");
        assertStatus(ReviewHistoryEngine.project(history, changed, contexts, analyze(file, changed), null),
                2, FileProjection.Status.NEEDS_REVIEW);
        FileProjection restored = ReviewHistoryEngine.project(history, MULTILINE, contexts, analyze(file, MULTILINE), null);
        for (int row = 0; row < 5; row++) assertStatus(restored, row, FileProjection.Status.REVIEWED);
        assertEquals(1, history.evidence().size());
        assertEmpty(history.revocations());
    }

    public void testMalformedFollowingFunctionDoesNotPoisonClosedAsm() {
        String baseline = MULTILINE + "fun other(x: Int) {\n    consume(x);\n}\n";
        VirtualFile file = open(baseline);
        String current = baseline.replace("other(x: Int)", "other(x: )");
        assertMalformed(current);
        FileProjection projection = project(file, baseline, current);
        for (int row = 0; row < 5; row++) assertStatus(projection, row, FileProjection.Status.REVIEWED);
        assertStatus(projection, 6, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testUnclosedAsmBodyRemainsUnknown() {
        VirtualFile file = open(MULTILINE);
        String current = MULTILINE.substring(0, MULTILINE.lastIndexOf('}'));
        assertMalformed(current);
        assertNull(analyze(file, current).fingerprint(2));
        assertStatus(project(file, MULTILINE, current), 2, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testRejectedClosedAsmBodyKeepsPrecedingFunctionReviewed() {
        String baseline = "fun before(): Int { return 0; }\n" + MULTILINE
                + "fun after(): Int { return 1; }\n";
        VirtualFile file = open(baseline);
        String current = baseline.replace("    NEWC", "    ;");
        assertMalformed(current);
        assertNull(analyze(file, current).fingerprint(3));
        FileProjection projection = project(file, baseline, current);
        assertStatus(projection, 0, FileProjection.Status.REVIEWED);
        assertStatus(projection, 3, FileProjection.Status.NEEDS_REVIEW);
        // A later declaration recovered after parser errors still requires confirmation.
        assertStatus(projection, 6, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testUnclosedAsmBodyDoesNotPoisonPrecedingClosedFunction() {
        String baseline = "fun before(): Int { return 0; }\n" + MULTILINE;
        VirtualFile file = open(baseline);
        String current = baseline.substring(0, baseline.lastIndexOf('}'));
        assertMalformed(current);
        FileProjection projection = project(file, baseline, current);
        assertStatus(projection, 0, FileProjection.Status.REVIEWED);
        assertNull(analyze(file, current).fingerprint(3));
        assertStatus(projection, 3, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testUnclosedPrecedingFunctionCannotConfirmAsmAsFilePeer() {
        String baseline = "fun before(x: Int) {\n    consume(x);\n}\n" + MULTILINE;
        VirtualFile file = open(baseline);
        String current = baseline.replace("consume(x);", "consume(x;");
        assertMalformed(current);
        assertNull(analyze(file, current).fingerprint(5));
        assertStatus(project(file, baseline, current), 5, FileProjection.Status.NEEDS_REVIEW);
    }

    private VirtualFile open(String text) {
        var file = myFixture.configureByText("asm-review.tact", text);
        assertFalse("The baseline must be valid installed-parser PSI", ReadAction.computeBlocking(() ->
                PsiTreeUtil.findChildOfType(file, PsiErrorElement.class) != null));
        return file.getVirtualFile();
    }

    private void assertMalformed(String text) {
        assertTrue("The negative fixture must contain a real parser error", ReadAction.computeBlocking(() -> {
            var copy = PsiFileFactory.getInstance(getProject()).createFileFromText(text, myFixture.getFile());
            return PsiTreeUtil.findChildOfType(copy, PsiErrorElement.class) != null;
        }));
    }

    private ContextMap analyze(VirtualFile file, String text) {
        return ContextAnalysisManager.analyze(getProject(), file, text);
    }

    private ReviewHistory reviewed(String text) {
        return ReviewHistoryEngine.review(ReviewHistory.empty(), text,
                List.of(new ReviewedRange(0, text.length())), 1);
    }

    private FileProjection project(VirtualFile file, String baseline, String current) {
        var history = reviewed(baseline);
        return ReviewHistoryEngine.project(history, current,
                Map.of(history.evidence().getFirst().snapshotId(), analyze(file, baseline)), analyze(file, current), null);
    }

    private static void assertStatus(FileProjection projection, int row, FileProjection.Status expected) {
        assertEquals("Projection status at row " + row, expected, projection.rows().get(row).status());
    }
}
