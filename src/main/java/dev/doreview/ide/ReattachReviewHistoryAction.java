package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;

public final class ReattachReviewHistoryAction extends ReviewEditorAction {
    @Override protected void perform(DoReviewService service, Editor editor) {
        service.reattachHistory(editor.getDocument());
    }
}
