package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;

/** Optional CodeGlance geometry boundary; never changes its components or markup. */
final class ReviewStripeCodeGlanceBridge {
    private static final String GLANCE_PANEL = "com.nasller.codeglance.panel.GlancePanel";

    private ReviewStripeCodeGlanceBridge() { }

    static ReviewStripeModel.Mapping mapping(Editor editor, JComponent stripe) {
        if (editor.isDisposed()) return null;
        try {
            var pending = new ArrayDeque<Component>();
            pending.add(editor.getComponent());
            while (!pending.isEmpty()) {
                var component = pending.removeFirst();
                if (GLANCE_PANEL.equals(component.getClass().getName()) && usable(component, editor)) {
                    var scroll = invoke(component, "getScrollState");
                    double scale = number(invoke(component, "getPixScale")).doubleValue();
                    double pixelsPerLine = number(invoke(scroll, "getPixelsPerLine")).doubleValue();
                    int visibleStart = number(invoke(scroll, "getVisibleStart")).intValue();
                    int renderHeight = number(invoke(scroll, "getRenderHeight")).intValue();
                    if (!Double.isFinite(scale) || scale <= 0 || !Double.isFinite(pixelsPerLine)
                            || pixelsPerLine <= 0 || visibleStart < 0 || renderHeight < 1) return null;
                    // Verify this version's forward/reverse API before selecting the bridge.
                    var minimap = minimap(component);
                    if (minimap == null) return null;
                    minimap.getClass().getMethod("getMyRenderLine", int.class, int.class);
                    component.getClass().getMethod("getMyRenderVisualLine", int.class);
                    int top = SwingUtilities.convertPoint(component, 0, 0, stripe).y;
                    return new GlanceMapping(component, top, component.getHeight(), scale,
                            pixelsPerLine, visibleStart, renderHeight);
                }
                if (component instanceof Container container) {
                    for (var child : container.getComponents()) pending.addLast(child);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Another version, a disappearing panel, or an inaccessible private field.
        }
        return null;
    }

    private static boolean usable(Component glance, Editor editor) throws ReflectiveOperationException {
        return glance.getParent() != null && glance.isVisible() && glance.getWidth() > 0 && glance.getHeight() > 0
                && invoke(glance, "getEditor") == editor && Boolean.FALSE.equals(invoke(glance, "isReleased"));
    }

    private static Object minimap(Component glance) throws ReflectiveOperationException {
        var field = glance.getClass().getDeclaredField("minimap");
        return field.trySetAccessible() ? field.get(glance) : null;
    }

    private static Object invoke(Object owner, String method) throws ReflectiveOperationException {
        return owner.getClass().getMethod(method).invoke(owner);
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalArgumentException("Unexpected CodeGlance geometry value");
    }

    /** No plugin objects or reflection metadata are retained strongly between calls. */
    private static final class GlanceMapping implements ReviewStripeModel.Mapping {
        private final WeakReference<Component> owner;
        private final int top;
        private final int height;
        private final double scale;
        private final double pixelsPerLine;
        private final int visibleStart;
        private final int renderHeight;

        GlanceMapping(Component owner, int top, int height, double scale, double pixelsPerLine,
                      int visibleStart, int renderHeight) {
            this.owner = new WeakReference<>(owner);
            this.top = top;
            this.height = height;
            this.scale = scale;
            this.pixelsPerLine = pixelsPerLine;
            this.visibleStart = visibleStart;
            this.renderHeight = renderHeight;
        }

        @Override public int top() { return top; }
        @Override public int height() { return height; }
        @Override public double scale() { return scale; }

        @Override public int[] rows(int firstVisual, int lastVisual) {
            try {
                var glance = owner.get();
                if (glance == null || glance.getParent() == null || !glance.isVisible()
                        || !Boolean.FALSE.equals(invoke(glance, "isReleased"))) return new int[]{0, 0};
                var minimap = minimap(glance);
                if (minimap == null) return new int[]{0, 0};
                var correction = minimap.getClass().getMethod("getMyRenderLine", int.class, int.class)
                        .invoke(minimap, firstVisual, lastVisual);
                double start = firstVisual * pixelsPerLine + number(invoke(correction, "getFirst")).doubleValue() - visibleStart;
                double end = lastVisual * pixelsPerLine + number(invoke(correction, "getSecond")).doubleValue() - visibleStart;
                if (!Double.isFinite(start) || !Double.isFinite(end)) return new int[]{0, 0};
                if (start < 0 && end < 0) return new int[]{0, 0};
                // CodeGlance truncates native coordinates before scaling its Graphics2D.
                int first = (int) start, last = (int) end;
                if (start == end) return new int[]{first, first + renderHeight};
                // Native Git markers rasterize two end caps and their middle independently.
                // Keep that truncation order in Fit mode and on fractional display scales.
                int middle = (int) (start + renderHeight);
                int middleHeight = end + renderHeight == start ? 0 : (int) (end - start - renderHeight);
                return new int[]{first, first + renderHeight, middle, middle + middleHeight, last, last + renderHeight};
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return new int[]{0, 0};
            }
        }

        @Override public int visualLine(int y) {
            try {
                var glance = owner.get();
                if (glance == null || glance.getParent() == null || !glance.isVisible()
                        || !Boolean.FALSE.equals(invoke(glance, "isReleased"))) return 0;
                int absoluteY = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) y + visibleStart));
                return number(glance.getClass().getMethod("getMyRenderVisualLine", int.class).invoke(glance, absoluteY)).intValue();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return 0;
            }
        }
    }
}
