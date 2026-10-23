package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;

public final class ShowReviewChangesAction extends ReviewEditorAction {
    @Override protected void perform(DoReviewService service, Editor editor) {
        service.showChanges(editor.getDocument());
    }
}
