package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;

public final class ClearReviewedAction extends ReviewEditorAction {
    @Override protected void perform(DoReviewService service, Editor editor) {
        int[] lines = selectedLines(editor);
        service.clear(editor.getDocument(), lines[0], lines[1]);
    }
}
