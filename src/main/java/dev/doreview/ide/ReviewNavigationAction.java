package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;

/** Shared caret placement for forward and backward review navigation. */
abstract class ReviewNavigationAction extends ReviewEditorAction {
    protected enum Direction { NEXT, PREVIOUS }

    private final Direction direction;

    protected ReviewNavigationAction(Direction direction) {
        this.direction = direction;
    }

    @Override protected final void perform(DoReviewService service, Editor editor) {
        var document = editor.getDocument();
        int currentLine = editor.getCaretModel().getLogicalPosition().line;
        int line = direction == Direction.NEXT ? service.nextUnreviewedLine(document, currentLine)
                : service.previousUnreviewedLine(document, currentLine);
        if (line < 0) {
            service.notifyInfo("All non-blank lines in this file are reviewed.");
            return;
        }
        editor.getSelectionModel().removeSelection();
        editor.getCaretModel().moveToOffset(document.getLineEndOffset(line));
        editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
    }
}
