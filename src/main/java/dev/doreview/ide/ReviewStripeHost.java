package dev.doreview.ide;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.impl.SettingsImpl;
import com.intellij.openapi.fileEditor.impl.text.TextEditorComponent;
import com.intellij.openapi.Disposable;

import java.awt.Component;
import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

/** SDK 262 boundary: add a sibling outside the editor and its third-party minimap. */
final class ReviewStripeHost {
    private ReviewStripeHost() { }

    static TextEditorComponent find(Editor editor) {
        for (Container parent = editor.getComponent().getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof TextEditorComponent host) {
                return !host.isDisposed() && host.getEditor() == editor && host.getLayout() instanceof GridBagLayout ? host : null;
            }
        }
        return null;
    }

    static boolean attach(TextEditorComponent host, Component stripe) {
        if (host.isDisposed() || !(host.getLayout() instanceof GridBagLayout layout)) return false;
        if (stripe.getParent() == host) return true;
        // Preserve any other plugin's occupied column instead of replacing its component.
        for (var child : host.getComponents()) if (layout.getConstraints(child).gridx == 1) return false;
        var constraints = new GridBagConstraints();
        constraints.gridx = 1;
        constraints.gridy = 0;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.VERTICAL;
        constraints.anchor = GridBagConstraints.EAST;
        // Ordinary add overloads deliberately throw in TextEditorComponent on this platform.
        host.__add(stripe, constraints);
        host.revalidate();
        host.repaint();
        return true;
    }

    static void detach(Component stripe) {
        var parent = stripe.getParent();
        if (parent == null) return;
        parent.remove(stripe);
        parent.revalidate();
        parent.repaint();
    }

    static void listenForSettings(Editor editor, Runnable repaint, Disposable parent) {
        if (!(editor.getSettings() instanceof SettingsImpl settings)) return;
        // Observe wrap/layout preferences without sharing a parser-listener list with minimaps.
        settings.getState().addPropertyChangeListener(event -> repaint.run(), parent);
    }
}
