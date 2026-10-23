package dev.doreview.ide;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayModel;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.MouseEventAdapter;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;

/** Review marks in CodeGlance coordinates, or a whole-file overview without a visible minimap. */
final class ReviewStripePanel extends JPanel implements Disposable, UiDataProvider {
    static final int WIDTH = 14;
    private final Editor editor;
    private final ComponentAdapter geometryListener = new ComponentAdapter() {
        @Override public void componentResized(ComponentEvent event) { repaint(); }
        @Override public void componentMoved(ComponentEvent event) { repaint(); }
    };
    private ReviewStripeModel.Snapshot snapshot;
    private boolean disposed;
    private boolean hadMinimap;
    private final Timer minimapRepaint;
    private final HierarchyListener showingListener = event -> {
        if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) updateTimer();
    };
    private final ContainerAdapter minimapContainerListener = new ContainerAdapter() {
        @Override public void componentAdded(ContainerEvent event) { repaint(); }
        @Override public void componentRemoved(ContainerEvent event) { repaint(); }
    };

    ReviewStripePanel(Editor editor) {
        this.editor = editor;
        minimapRepaint = new Timer(100, event -> {
            if (disposed || !isShowing()) return;
            boolean mapped = ReviewStripeCodeGlanceBridge.mapping(editor, this) != null;
            // Hiding/removing CodeGlance also needs one paint to clear its old coordinates.
            if (mapped || hadMinimap) repaint();
            hadMinimap = mapped;
        });
        minimapRepaint.setRepeats(true);
        addHierarchyListener(showingListener);
        editor.getComponent().addContainerListener(minimapContainerListener);
        setName("DoReview Stripe");
        setOpaque(true);
        setPreferredSize(new Dimension(JBUI.scale(WIDTH), 0));
        setMinimumSize(new Dimension(JBUI.scale(WIDTH), 0));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("DoReview");
        var mouse = new MouseAdapter() {
            private boolean dragging;
            @Override public void mousePressed(MouseEvent event) {
                if (showPopup(event)) return;
                if (SwingUtilities.isLeftMouseButton(event) && trackBounds().contains(event.getPoint())) {
                    dragging = true;
                    navigate(event.getY());
                }
            }
            @Override public void mouseDragged(MouseEvent event) { if (dragging) navigate(event.getY()); }
            @Override public void mouseReleased(MouseEvent event) { dragging = false; showPopup(event); }
            @Override public void mouseWheelMoved(MouseWheelEvent event) {
                if (!editor.isDisposed()) MouseEventAdapter.redispatch(event, editor.getContentComponent());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        editor.getContentComponent().addComponentListener(geometryListener);
        editor.getScrollingModel().addVisibleAreaListener(event -> repaint(), this);
        if (editor instanceof EditorEx extended) extended.getFoldingModel().addListener(new com.intellij.openapi.editor.ex.FoldingListener() {
            @Override public void onFoldProcessingEnd() { repaint(); }
        }, this);
        editor.getInlayModel().addListener(new InlayModel.Listener() {
            @Override public void onAdded(@NotNull Inlay<?> inlay) { repaint(); }
            @Override public void onUpdated(@NotNull Inlay<?> inlay, int flags) { repaint(); }
            @Override public void onRemoved(@NotNull Inlay<?> inlay) { repaint(); }
            @Override public void onBatchModeFinish(@NotNull Editor editor) { repaint(); }
        }, this);
        ReviewStripeHost.listenForSettings(editor, this::repaint, this);
    }

    void setSnapshot(ReviewStripeModel.Snapshot value) { snapshot = value; repaint(); }

    ReviewStripeModel.Snapshot currentSnapshot() {
        return !disposed && !editor.isDisposed() && snapshot != null
                && snapshot.stamp() == editor.getDocument().getModificationStamp() ? snapshot : null;
    }

    Rectangle trackBounds() {
        var mapping = mapping();
        return new Rectangle(0, mapping.top(), getWidth(), mapping.height());
    }

    private ReviewStripeModel.Mapping mapping() {
        var minimap = ReviewStripeCodeGlanceBridge.mapping(editor, this);
        if (minimap != null) {
            hadMinimap = true;
            return minimap;
        }
        var bounds = overviewBounds();
        return ReviewStripeModel.overview(editor, bounds.y, bounds.height);
    }

    private Rectangle overviewBounds() {
        if (editor instanceof EditorEx extended && getParent() != null) {
            var viewport = extended.getScrollPane().getViewport();
            var bounds = SwingUtilities.convertRectangle(viewport, new Rectangle(viewport.getSize()), this);
            int top = Math.max(0, bounds.y), bottom = Math.min(getHeight(), bounds.y + bounds.height);
            // The IDE may overlay its horizontal scrollbar on top of the viewport.
            var horizontal = extended.getScrollPane().getHorizontalScrollBar();
            if (horizontal.isVisible() && horizontal.getHeight() > 0) {
                var scrollbar = SwingUtilities.convertRectangle(horizontal, new Rectangle(horizontal.getSize()), this);
                if (scrollbar.y >= top) bottom = Math.min(bottom, scrollbar.y);
            }
            return new Rectangle(0, top, getWidth(), Math.max(0, bottom - top));
        }
        return new Rectangle(0, 0, getWidth(), getHeight());
    }

    List<ReviewStripeModel.Band> bands() {
        var current = currentSnapshot();
        return current == null ? List.of() : ReviewStripeModel.bands(editor, current, mapping());
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (disposed || editor.isDisposed()) return;
        graphics.setColor(editor.getColorsScheme().getDefaultBackground());
        graphics.fillRect(0, 0, getWidth(), getHeight());
        var current = currentSnapshot();
        if (current == null) return;
        var mapping = mapping();
        var canvas = (Graphics2D) graphics.create();
        try {
            canvas.clipRect(0, mapping.top(), getWidth(), mapping.height());
            canvas.translate(0, mapping.top());
            // Match CodeGlance's integer rasterization BEFORE its additional graphics scale.
            canvas.scale(1, mapping.scale());
            for (var band : ReviewStripeModel.bands(editor, current, mapping)) {
                canvas.setColor(color(band.kind()));
                canvas.fillRect(1, band.y(), Math.max(0, getWidth() - 1), band.height());
            }
        } finally { canvas.dispose(); }
    }

    private Color color(ReviewStripeModel.Kind kind) {
        var scheme = editor.getColorsScheme();
        return switch (kind) {
            case REVIEWED -> ReviewHighlighting.attributesFor(scheme).getBackgroundColor();
            case NEEDS_REVIEW -> ReviewHighlighting.needsReviewAttributesFor(scheme).getBackgroundColor();
            case CHANGED -> fallback(scheme.getColor(EditorColors.MODIFIED_LINES_COLOR), JBColor.BLUE);
            case DELETED -> fallback(scheme.getColor(EditorColors.DELETED_LINES_COLOR), JBColor.RED);
        };
    }

    private static Color fallback(Color color, Color fallback) { return color == null ? fallback : color; }

    @Override public String getToolTipText(MouseEvent event) {
        var current = currentSnapshot();
        if (current == null) return "DoReview — waiting for current analysis";
        var mapping = mapping();
        int y = (int) Math.floor((event.getY() - mapping.top()) / mapping.scale());
        for (var band : ReviewStripeModel.bands(editor, current, mapping)) if (y >= band.y() && y < band.y() + band.height()) return switch (band.kind()) {
            case REVIEWED -> "DoReview — reviewed";
            case NEEDS_REVIEW -> "DoReview — needs review";
            case CHANGED -> "DoReview — changed since review";
            case DELETED -> "DoReview — deleted reviewed code";
        };
        return "DoReview — no review mark";
    }

    private void navigate(int y) {
        if (disposed || editor.isDisposed()) return;
        var mapping = mapping();
        if (mapping.height() == 0) return;
        IdeFocusManager.getInstance(editor.getProject()).requestFocus(editor.getContentComponent(), true);
        editor.getCaretModel().moveToVisualPosition(ReviewStripeModel.positionAt(editor, y - mapping.top(), mapping));
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
    }

    private boolean showPopup(MouseEvent event) {
        if (!event.isPopupTrigger() || disposed) return false;
        var actionManager = ActionManager.getInstance();
        var toggle = actionManager.getAction("ReviewMarks.ToggleStripe");
        if (toggle != null) actionManager.createActionPopupMenu("DoReview.Stripe", new DefaultActionGroup(toggle))
                .getComponent().show(this, event.getX(), event.getY());
        return true;
    }

    @Override public void uiDataSnapshot(@NotNull DataSink sink) {
        sink.set(CommonDataKeys.PROJECT, editor.getProject());
        sink.set(CommonDataKeys.EDITOR, editor);
    }

    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        snapshot = null;
        minimapRepaint.stop();
        removeHierarchyListener(showingListener);
        editor.getComponent().removeContainerListener(minimapContainerListener);
        editor.getContentComponent().removeComponentListener(geometryListener);
        ToolTipManager.sharedInstance().unregisterComponent(this);
    }

    private void updateTimer() {
        if (!disposed && isShowing()) minimapRepaint.start();
        else minimapRepaint.stop();
    }
}
