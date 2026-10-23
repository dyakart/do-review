package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.impl.status.widget.StatusBarEditorBasedWidgetFactory;
import org.jetbrains.annotations.NotNull;

/** Registers an enabled-by-default current-file coverage indicator. */
public final class ReviewCoverageWidgetFactory extends StatusBarEditorBasedWidgetFactory {
    public static final String ID = "ReviewMarks.Coverage";

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "DoReview Coverage";
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new ReviewCoverageWidget(project);
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return !project.isDisposed() && !project.isDefault();
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        Project project = statusBar.getProject();
        Editor editor = getTextEditor(statusBar);
        return project != null && !project.isDisposed() && editor != null
                && !editor.isDisposed() && !editor.isViewer()
                && new ReviewFileAccess(project).relativePath(editor.getDocument()) != null;
    }
}
