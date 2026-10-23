package dev.doreview.ide;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

abstract class ReviewEditorAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        event.getPresentation().setEnabledAndVisible(project != null && editor != null && !editor.isViewer()
                && !project.isDisposed() && !editor.isDisposed()
                && new ReviewFileAccess(project).relativePath(editor.getDocument()) != null);
    }

    @Override
    public final void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project != null && editor != null && !editor.isDisposed()) {
            DoReviewService service = DoReviewService.getInstance(project);
            if (service.supports(editor.getDocument())) perform(service, editor);
        }
    }

    protected abstract void perform(DoReviewService service, Editor editor);

    /** Whole selected lines; a selection ending at a line start excludes that line. */
    static int[] selectedLines(Editor editor) {
        Document document = editor.getDocument();
        int start = editor.getSelectionModel().hasSelection()
                ? editor.getSelectionModel().getSelectionStart() : editor.getCaretModel().getOffset();
        int end = editor.getSelectionModel().hasSelection() ? editor.getSelectionModel().getSelectionEnd() : start;
        int firstLine = document.getLineNumber(start);
        int lastLine = document.getLineNumber(end > start ? end - 1 : end);
        int rangeEnd = document.getLineEndOffset(lastLine);
        if (rangeEnd < document.getTextLength()) rangeEnd++;
        return new int[]{document.getLineStartOffset(firstLine), rangeEnd};
    }
}
