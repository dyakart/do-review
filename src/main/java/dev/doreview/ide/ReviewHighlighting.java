package dev.doreview.ide;

import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.ColorUtil;

import java.awt.Color;
import java.awt.Font;

/** Supplies a visible background even for third-party schemes without our color key. */
final class ReviewHighlighting {
    private static final Color DARK_GREEN = new Color(0x28543a);
    private static final Color LIGHT_GREEN = new Color(0xd5efdc);

    private ReviewHighlighting() { }

    static TextAttributes attributesFor(EditorColorsScheme scheme) {
        TextAttributes configured = scheme.getAttributes(ReviewMarkKeys.REVIEWED);
        Color background = configured == null ? null : configured.getBackgroundColor();
        if (background == null) {
            background = ColorUtil.isDark(scheme.getDefaultBackground()) ? DARK_GREEN : LIGHT_GREEN;
        }
        // Leave syntax foregrounds and font styles to the language's highlighters.
        return new TextAttributes(null, background, null, null, Font.PLAIN);
    }
    static TextAttributes needsReviewAttributesFor(EditorColorsScheme scheme) {
        TextAttributes configured = scheme.getAttributes(ReviewMarkKeys.NEEDS_REVIEW);
        Color background = configured == null ? null : configured.getBackgroundColor();
        if (background == null) background = ColorUtil.isDark(scheme.getDefaultBackground())
                ? new Color(0x66551e) : new Color(0xffefb5);
        return new TextAttributes(null, background, null, null, Font.PLAIN);
    }
}
