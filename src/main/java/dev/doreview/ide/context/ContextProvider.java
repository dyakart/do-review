package dev.doreview.ide.context;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.psi.PsiFile;
import dev.doreview.core.ContextMap;

/** Optional language adapters use syntax only; they must never resolve symbols or infer types. */
public interface ContextProvider {
    ExtensionPointName<ContextProvider> EP = ExtensionPointName.create("dev.reviewmarks.contextProvider");
    String id();
    boolean supports(PsiFile file);
    ContextMap analyze(PsiFile file, String text);
}
