package dev.doreview.ide;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;

/** The supported file boundary; no review state or UI resources live here. */
final class ReviewFileAccess {
    private final Project project;

    ReviewFileAccess(Project project) { this.project = project; }

    String relativePath(Document document) {
        var file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !file.isValid() || !file.isInLocalFileSystem() || file.getFileType().isBinary()) return null;
        return relativePath(file.getPath());
    }

    String relativePath(String path) {
        String base = project.getBasePath();
        if (base == null || !FileUtil.isAncestor(base, path, true)) return null;
        String relative = FileUtil.getRelativePath(FileUtil.toSystemIndependentName(base), FileUtil.toSystemIndependentName(path), '/');
        if (relative == null) return null;
        for (String part : relative.split("/")) if (part.equalsIgnoreCase(".doreview") || part.equalsIgnoreCase(".git")) return null;
        return relative;
    }
}
