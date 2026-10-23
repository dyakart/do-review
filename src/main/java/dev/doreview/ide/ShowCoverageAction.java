package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;

public final class ShowCoverageAction extends ReviewEditorAction {
    @Override protected void perform(DoReviewService service, Editor editor) {
        var coverage = service.coverage(editor.getDocument());
        service.notifyInfo("DoReview: reviewed " + coverage.reviewed() + " / " + coverage.total()
                + " non-blank lines (" + coverage.percent() + "%). Needs review: " + coverage.needsReview()
                + "; deleted reviewed lines: " + coverage.deleted() + ".");
    }
}
