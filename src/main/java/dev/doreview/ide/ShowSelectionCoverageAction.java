package dev.doreview.ide;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/** Project-view counterpart to the current-editor coverage action. */
public final class ShowSelectionCoverageAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        var project = event.getProject();
        event.getPresentation().setEnabledAndVisible(project != null && Arrays.stream(selected(event))
                .anyMatch(file -> ReviewSelectionCoverage.supports(project, file)));
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var files = selected(event).clone();
        if (project == null || project.isDisposed() || files.length == 0) return;
        String label = files.length == 1 ? files[0].getName() : files.length + " selected items";
        new Task.Backgroundable(project, "DoReview coverage: " + label, true) {
            private ReviewSelectionCoverage.Result result;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                result = new ReviewSelectionCoverage(project).calculate(files, indicator);
            }
            @Override public void onSuccess() {
                if (!project.isDisposed() && result != null) show(project, label, result.message());
            }
            @Override public void onThrowable(@NotNull Throwable failure) {
                if (!project.isDisposed()) show(project, label, "DoReview coverage could not be calculated. "
                        + (failure.getMessage() == null ? "Please try again." : failure.getMessage()));
            }
        }.queue();
    }

    private static VirtualFile[] selected(AnActionEvent event) {
        var files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null) return files;
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        return file == null ? VirtualFile.EMPTY_ARRAY : new VirtualFile[]{file};
    }

    private static void show(Project project, String label, String message) {
        NotificationGroupManager.getInstance().getNotificationGroup("Review Marks")
                .createNotification("DoReview coverage: " + label, message, NotificationType.INFORMATION).notify(project);
    }
}
