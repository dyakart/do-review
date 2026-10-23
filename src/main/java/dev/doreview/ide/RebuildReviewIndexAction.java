package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;

/** Replays the portable journal only after pending durable data has been exported. */
public final class RebuildReviewIndexAction extends ReviewEditorAction {
    @Override protected void perform(DoReviewService service, Editor editor) {
        service.rebuildIndex(editor.getDocument());
    }
}
