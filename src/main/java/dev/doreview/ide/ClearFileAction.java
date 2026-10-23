package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;

public final class ClearFileAction extends ReviewEditorAction {
    @Override protected void perform(DoReviewService service, Editor editor) {
        service.clearFile(editor.getDocument());
    }
}
