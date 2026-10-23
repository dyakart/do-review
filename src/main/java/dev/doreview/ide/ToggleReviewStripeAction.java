package dev.doreview.ide;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Toggles the project-wide stripe without requiring an active editor. */
public final class ToggleReviewStripeAction extends ToggleAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }

    @Override public void update(@NotNull AnActionEvent event) {
        super.update(event);
        event.getPresentation().setEnabledAndVisible(validProject(event) != null);
    }

    @Override public boolean isSelected(@NotNull AnActionEvent event) {
        Project project = validProject(event);
        return project != null && ReviewStripeSettings.getInstance(project).isShown();
    }

    @Override public void setSelected(@NotNull AnActionEvent event, boolean state) {
        Project project = validProject(event);
        if (project == null) return;
        DoReviewService.getInstance(project);
        ReviewStripeSettings.getInstance(project).setShown(state);
    }

    private static Project validProject(AnActionEvent event) {
        Project project = event.getProject();
        return project == null || project.isDisposed() || project.isDefault() ? null : project;
    }
}
