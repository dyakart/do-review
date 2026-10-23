package dev.doreview.ide;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import dev.doreview.core.FileProjection;

import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shares logical snapshots by document and owns independent stripe UI for every editor. */
final class ReviewStripePresentation implements Disposable {
    private final Project project;
    private final Set<Editor> editors = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Document, Cached> snapshots = new IdentityHashMap<>();
    private final Map<Editor, Handle> handles = new IdentityHashMap<>();
    private volatile boolean disposed;

    ReviewStripePresentation(Project project, Disposable parent) {
        this.project = project;
        Disposer.register(parent, this);
        project.getMessageBus().connect(this).subscribe(ReviewStripeSettings.TOPIC, this::visibilityChanged);
    }

    void render(Editor editor, FileProjection projection) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (disposed) return;
        if (projection == null || editor.isDisposed() || editor.isViewer() || editor.getProject() != project
                || editor.getEditorKind() != EditorKind.MAIN_EDITOR) { release(editor); return; }
        editors.add(editor);
        var document = editor.getDocument();
        var cached = snapshots.get(document);
        if (cached == null || cached.projection != projection || cached.stamp != document.getModificationStamp()) {
            cached = new Cached(projection, document.getModificationStamp());
            snapshots.put(document, cached);
        }
        if (ReviewStripeSettings.getInstance(project).isShown()) show(editor, cached);
    }

    private void show(Editor editor, Cached cached) {
        if (editor.isDisposed() || cached.stamp != editor.getDocument().getModificationStamp()) return;
        if (cached.snapshot == null) cached.snapshot = ReviewStripeModel.capture(cached.projection, cached.stamp);
        var handle = handles.computeIfAbsent(editor, Handle::new);
        handle.panel.setSnapshot(cached.snapshot);
        handle.ensureAttached();
    }

    private void visibilityChanged(boolean shown) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (disposed) return;
        if (!shown) {
            for (var handle : List.copyOf(handles.values())) Disposer.dispose(handle);
            handles.clear();
        } else for (var editor : List.copyOf(editors)) {
            var cached = snapshots.get(editor.getDocument());
            if (cached != null) show(editor, cached);
        }
    }

    void invalidate(Document document) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        snapshots.remove(document);
        handles.forEach((editor, handle) -> {
            if (editor.getDocument() == document) handle.panel.setSnapshot(null);
        });
    }

    void release(Editor editor) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        var handle = handles.remove(editor);
        if (handle != null) Disposer.dispose(handle);
        editors.remove(editor);
        if (editors.stream().noneMatch(other -> other.getDocument() == editor.getDocument())) snapshots.remove(editor.getDocument());
    }

    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        Runnable cleanup = () -> {
            for (var handle : List.copyOf(handles.values())) Disposer.dispose(handle);
            handles.clear();
            snapshots.clear();
            editors.clear();
        };
        if (ApplicationManager.getApplication().isDispatchThread()) cleanup.run();
        else ApplicationManager.getApplication().invokeLater(cleanup);
    }

    private final class Handle implements Disposable {
        private final Editor editor;
        private final ReviewStripePanel panel;
        private final HierarchyListener hierarchyListener;
        private boolean released;

        Handle(Editor editor) {
            this.editor = editor;
            panel = new ReviewStripePanel(editor);
            Disposer.register(this, panel);
            hierarchyListener = event -> {
                if ((event.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0) ensureAttached();
            };
            editor.getComponent().addHierarchyListener(hierarchyListener);
        }

        void ensureAttached() {
            if (released || editor.isDisposed()) return;
            var host = ReviewStripeHost.find(editor);
            if (panel.getParent() != host) ReviewStripeHost.detach(panel);
            if (host != null && panel.getParent() == null) ReviewStripeHost.attach(host, panel);
        }

        @Override public void dispose() {
            if (released) return;
            released = true;
            editor.getComponent().removeHierarchyListener(hierarchyListener);
            ReviewStripeHost.detach(panel);
        }
    }

    private static final class Cached {
        final FileProjection projection;
        final long stamp;
        ReviewStripeModel.Snapshot snapshot;
        Cached(FileProjection projection, long stamp) { this.projection = projection; this.stamp = stamp; }
    }
}
