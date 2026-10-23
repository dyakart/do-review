package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;

public final class MarkReviewedAction extends ReviewEditorAction {
    @Override protected void perform(DoReviewService service, Editor editor) {
        int[] lines = selectedLines(editor);
        service.toggle(editor.getDocument(), lines[0], lines[1]);
    }
}
