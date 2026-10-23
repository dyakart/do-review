package dev.doreview.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.impl.status.EditorBasedWidget;
import com.intellij.util.Alarm;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;

/** Live review coverage for the text editor belonging to this status bar. */
public final class ReviewCoverageWidget extends EditorBasedWidget
        implements StatusBarWidget.TextPresentation, StatusBarWidget.Multiframe {
    private static final int REFRESH_DELAY_MS = 150;

    private final Alarm refreshAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private volatile String text = "";
    private volatile String tooltip;

    public ReviewCoverageWidget(@NotNull Project project) {
        super(project);
        myConnection.subscribe(DoReviewService.COVERAGE_CHANGED, this::refresh);
        myConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override
            public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                refresh();
            }

            @Override
            public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                refresh();
            }

            @Override
            public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                refresh();
            }
        });
        var multicaster = EditorFactory.getInstance().getEventMulticaster();
        multicaster.addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                // A file without any marks still has a changing coverage denominator.
                refresh();
            }
        }, this);
        multicaster.addSelectionListener(new SelectionListener() {
            @Override
            public void selectionChanged(@NotNull SelectionEvent event) {
                if (event.getEditor().getProject() == project) refresh();
            }
        }, this);
        refresh();
    }

    @Override
    public @NotNull String ID() {
        return ReviewCoverageWidgetFactory.ID;
    }

    @Override
    public @NotNull StatusBarWidget.WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        super.install(statusBar);
        refresh();
    }

    @Override
    public @NotNull StatusBarWidget copy() {
        return new ReviewCoverageWidget(getProject());
    }

    /** Coalesces edits and service notifications; callable from any thread. */
    public void refresh() {
        if (isDisposed() || getProject().isDisposed() || refreshAlarm.isDisposed()) return;
        refreshAlarm.cancelAllRequests();
        refreshAlarm.addRequest(this::refreshNow, REFRESH_DELAY_MS);
    }

    /** Recalculates synchronously on the UI thread, also useful for deterministic tests. */
    public void refreshNow() {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (isDisposed() || getProject().isDisposed()) return;
        refreshAlarm.cancelAllRequests();
        Editor editor = getEditor();
        String nextText = "";
        String nextTooltip = null;
        if (editor != null && !editor.isDisposed() && !editor.isViewer()
                && editor.getProject() == getProject()) {
            DoReviewService service = DoReviewService.getInstance(getProject());
            if (service.supports(editor.getDocument())) {
                DoReviewService.Coverage coverage = service.coverage(editor.getDocument());
                VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
                nextText = "DoReview " + coverage.percent() + "%";
                if (coverage.needsReview() > 0) nextText += " · recheck " + coverage.needsReview();
                if (coverage.deleted() > 0) nextText += " · deleted " + coverage.deleted();
                nextTooltip = "DoReview: reviewed " + coverage.reviewed() + " / " + coverage.total()
                        + " non-blank lines in " + file.getName()
                        + "; needs review: " + coverage.needsReview()
                        + "; deleted reviewed lines: " + coverage.deleted()
                        + ". Click to compare with a reviewed version.";
            }
        }
        text = nextText;
        tooltip = nextTooltip;
        StatusBar statusBar = getStatusBar();
        if (statusBar != null) statusBar.updateWidget(ID());
    }

    @Override
    public @NotNull String getText() {
        return text;
    }

    @Override
    public float getAlignment() {
        return 0.5f;
    }

    @Override
    public @Nullable String getTooltipText() {
        return tooltip;
    }

    @Override
    public @NotNull Consumer<MouseEvent> getClickConsumer() {
        return event -> {
            refreshNow();
            Editor editor = getEditor();
            if (tooltip != null && editor != null) DoReviewService.getInstance(getProject()).showChanges(editor.getDocument());
        };
    }
}
