package dev.doreview.ide;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionWrapper;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import dev.doreview.core.FileProjection;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.KeyStroke;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Owns editor resources only. It consumes immutable projections and never queries storage or PSI. */
final class ReviewEditorPresentation implements Disposable {
    private static final CustomShortcutSet NEXT_REVIEW_SHORTCUT =
            new CustomShortcutSet(KeyStroke.getKeyStroke("alt M"));
    private static final CustomShortcutSet PREVIOUS_REVIEW_SHORTCUT =
            new CustomShortcutSet(KeyStroke.getKeyStroke("alt shift M"));
    private final Project project;
    private final Consumer<Document> showChanges;
    private final ReviewStripePresentation stripe;
    private final Map<Editor, List<RangeHighlighter>> highlights = new IdentityHashMap<>();
    private final Map<Editor, Disposable> shortcuts = new IdentityHashMap<>();
    private volatile boolean disposed;

    ReviewEditorPresentation(Project project, Disposable parent, Consumer<Document> showChanges) {
        this.project = project;
        this.showChanges = showChanges;
        Disposer.register(parent, this);
        stripe = new ReviewStripePresentation(project, this);
    }

    void render(Editor editor, FileProjection projection) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        removeHighlights(editor);
        if (disposed || editor.isDisposed() || editor.isViewer()) return;
        stripe.render(editor, projection);
        if (projection == null) { removeShortcut(editor); return; }
        installShortcut(editor);
        var runs = new ArrayList<HighlightRun>();
        for (var row : projection.rows()) {
            if (row.status() == FileProjection.Status.UNREVIEWED) continue;
            var current = new HighlightRun(row.start(), row.end(), row.status(), row.reason());
            if (!runs.isEmpty()) {
                var prior = runs.getLast();
                if (prior.end() == current.start() && prior.status() == current.status() && prior.reason() == current.reason()) {
                    runs.set(runs.size() - 1, new HighlightRun(prior.start(), current.end(), current.status(), current.reason()));
                    continue;
                }
            }
            runs.add(current);
        }
        var created = new ArrayList<RangeHighlighter>();
        for (var run : runs) {
            boolean green = run.status() == FileProjection.Status.REVIEWED;
            boolean yellow = run.status() == FileProjection.Status.NEEDS_REVIEW;
            int end = run.end();
            if (end > run.start() && editor.getDocument().getCharsSequence().charAt(end - 1) == '\n') end--;
            var attributes = green ? ReviewHighlighting.attributesFor(editor.getColorsScheme())
                    : yellow ? ReviewHighlighting.needsReviewAttributesFor(editor.getColorsScheme()) : null;
            var highlighter = editor.getMarkupModel().addRangeHighlighter(run.start(), end,
                    HighlighterLayer.SELECTION - 1, attributes, HighlighterTargetArea.LINES_IN_RANGE);
            highlighter.putUserData(green ? ReviewMarkKeys.REVIEWED_HIGHLIGHTER
                    : yellow ? ReviewMarkKeys.NEEDS_REVIEW_HIGHLIGHTER : ReviewMarkKeys.CHANGED_HIGHLIGHTER, true);
            String reason = green ? "Reviewed — exact text and context confirmed"
                    : yellow ? "Needs review: " + run.reason().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    : "Changed since review";
            highlighter.setGutterIconRenderer(new ReviewGutterIcon(green, reason, () -> showChanges.accept(editor.getDocument())));
            highlighter.setErrorStripeTooltip(reason);
            created.add(highlighter);
        }
        if (!created.isEmpty()) highlights.put(editor, created);
    }

    void clearConfirmed(Document document) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        stripe.invalidate(document);
        for (var editor : EditorFactory.getInstance().getEditors(document, project)) {
            var owned = highlights.get(editor);
            if (owned == null) continue;
            owned.removeIf(highlighter -> {
                if (!Boolean.TRUE.equals(highlighter.getUserData(ReviewMarkKeys.REVIEWED_HIGHLIGHTER))) return false;
                highlighter.dispose();
                return true;
            });
            if (owned.isEmpty()) highlights.remove(editor);
        }
    }

    void release(Editor editor) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        removeHighlights(editor);
        removeShortcut(editor);
        stripe.release(editor);
    }

    private void removeHighlights(Editor editor) {
        var old = highlights.remove(editor);
        if (old != null) old.forEach(RangeHighlighter::dispose);
    }

    private void installShortcut(Editor editor) {
        if (shortcuts.containsKey(editor)) return;
        var actions = ActionManager.getInstance();
        var mark = actions.getAction("ReviewMarks.Mark");
        var next = actions.getAction("ReviewMarks.Next");
        var previous = actions.getAction("ReviewMarks.Previous");
        if (mark == null && next == null && previous == null) return;
        var binding = Disposer.newDisposable("DoReview editor shortcut");
        Disposer.register(this, binding);
        if (mark != null) mark.registerCustomShortcutSet(editor.getContentComponent(), binding);
        registerNavigationShortcut(next, NEXT_REVIEW_SHORTCUT, editor, binding);
        registerNavigationShortcut(previous, PREVIOUS_REVIEW_SHORTCUT, editor, binding);
        shortcuts.put(editor, binding);
    }

    private static void registerNavigationShortcut(AnAction action, CustomShortcutSet shortcut,
            Editor editor, Disposable binding) {
        if (action == null) return;
        // Keep review navigation local and preserve each global action's keymap proxy.
        var navigation = new AnActionWrapper(action);
        navigation.registerCustomShortcutSet(shortcut, editor.getContentComponent(), binding);
    }

    private void removeShortcut(Editor editor) {
        var binding = shortcuts.remove(editor);
        if (binding != null) Disposer.dispose(binding);
    }

    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        stripe.dispose();
        Runnable cleanup = () -> {
            for (var editor : List.copyOf(highlights.keySet())) removeHighlights(editor);
            for (var editor : List.copyOf(shortcuts.keySet())) removeShortcut(editor);
        };
        if (ApplicationManager.getApplication().isDispatchThread()) cleanup.run();
        else ApplicationManager.getApplication().invokeLater(cleanup);
    }

    private record HighlightRun(int start, int end, FileProjection.Status status, FileProjection.Reason reason) { }

    private static final class ReviewGutterIcon extends GutterIconRenderer {
        private final boolean green;
        private final String tooltip;
        private final Runnable compare;

        ReviewGutterIcon(boolean green, String tooltip, Runnable compare) {
            this.green = green;
            this.tooltip = tooltip;
            this.compare = compare;
        }

        @Override public @NotNull Icon getIcon() { return green ? AllIcons.General.InspectionsOK : AllIcons.General.Warning; }
        @Override public String getTooltipText() { return tooltip + " — click to compare"; }
        @Override public boolean isNavigateAction() { return true; }
        @Override public @NotNull AnAction getClickAction() {
            return new AnAction() { @Override public void actionPerformed(@NotNull AnActionEvent event) { compare.run(); } };
        }
        @Override public boolean equals(Object other) {
            return other instanceof ReviewGutterIcon icon && green == icon.green && tooltip.equals(icon.tooltip);
        }
        @Override public int hashCode() { return Boolean.hashCode(green) * 31 + tooltip.hashCode(); }
    }
}
