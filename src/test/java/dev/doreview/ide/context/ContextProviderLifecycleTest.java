package dev.doreview.ide.context;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.extensions.LoadingOrder;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import dev.doreview.core.ContextMap;
import dev.doreview.ide.ReviewHistoryStore;
import dev.doreview.ide.DoReviewService;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;

public final class ContextProviderLifecycleTest extends BasePlatformTestCase {
    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture project = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(project, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(project.getProject().getBasePath()); }
        });
    }

    public void testProviderChangeIncrementsGenerationClearsCachedMapsAndNotifies() {
        var file = myFixture.configureByText("extension.py", "value = 1\n").getVirtualFile();
        var manager = getProject().getService(ContextAnalysisManager.class);
        ContextMap initial = manager.analyze(file, "value = 1\n");
        long before = manager.generation();
        var changes = new AtomicInteger();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(ContextAnalysisManager.CHANGED, changes::incrementAndGet);
        Disposable extension = Disposer.newDisposable(getTestRootDisposable());
        ContextProvider.EP.getPoint().registerExtension(unknownProvider(), LoadingOrder.FIRST, extension);
        try {
            assertTrue(manager.generation() > before);
            assertTrue(changes.get() > 0);
            assertEquals("lifecycle-probe", manager.analyze(file, "value = 1\n").provider());
        } finally { Disposer.dispose(extension); }
        assertEquals(initial.provider(), manager.analyze(file, "value = 1\n").provider());
    }

    public void testReadyProjectionIsRecomputedWhenProviderChangesWithoutDocumentEdit() {
        var store = ReviewHistoryStore.getInstance();
        store.loadState(new ReviewHistoryStore.StoreState());
        var file = myFixture.addFileToProject("projection.py", "value = 1\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var document = myFixture.getEditor().getDocument();
        var service = DoReviewService.getInstance(getProject());
        assertTrue("Lifecycle projection needs a supported local project file", service.supports(document));
        service.mark(document, 0, document.getTextLength());
        awaitReady(service, document);
        assertEquals(1, service.coverage(document).reviewed());
        long stamp = document.getModificationStamp();
        Disposable extension = Disposer.newDisposable(getTestRootDisposable());
        ContextProvider.EP.getPoint().registerExtension(unknownProvider(), LoadingOrder.FIRST, extension);
        try {
            awaitReady(service, document);
            assertEquals(stamp, document.getModificationStamp());
            assertEquals(0, service.coverage(document).reviewed());
            assertEquals(1, service.coverage(document).needsReview());
        } finally { Disposer.dispose(extension); store.loadState(new ReviewHistoryStore.StoreState()); }
    }

    public void testCompletingOldProviderCannotRepopulateCacheAfterSameIdReplacement() {
        String source = "value = 1\n";
        var file = myFixture.configureByText("replacement.py", source).getVirtualFile();
        var manager = getProject().getService(ContextAnalysisManager.class);
        Disposable originalLifetime = Disposer.newDisposable(getTestRootDisposable());
        Disposable replacementLifetime = Disposer.newDisposable(getTestRootDisposable());
        var replacementCalls = new AtomicInteger();
        ContextProvider replacement = new ContextProvider() {
            @Override public String id() { return "same-id-probe"; }
            @Override public boolean supports(PsiFile ignored) { return true; }
            @Override public ContextMap analyze(PsiFile ignored, String text) {
                replacementCalls.incrementAndGet();
                return new ContextMap(id(), Collections.nCopies(ContextAnalysisManager.lineStarts(text).length, "new"));
            }
        };
        ContextProvider original = new ContextProvider() {
            @Override public String id() { return replacement.id(); }
            @Override public boolean supports(PsiFile ignored) { return true; }
            @Override public ContextMap analyze(PsiFile ignored, String text) {
                long generation = manager.generation();
                // Deterministically replace the provider after selection but before its result
                // completes. There is no blocked reader or competing write action in this probe.
                Disposer.dispose(originalLifetime);
                ContextProvider.EP.getPoint().registerExtension(replacement, LoadingOrder.FIRST, replacementLifetime);
                assertTrue(manager.generation() > generation);
                return new ContextMap(id(), Collections.nCopies(ContextAnalysisManager.lineStarts(text).length, "old"));
            }
        };
        ContextProvider.EP.getPoint().registerExtension(original, LoadingOrder.FIRST, originalLifetime);
        try {
            assertEquals("old", manager.analyze(file, source).fingerprint(0));
            assertEquals("new", manager.analyze(file, source).fingerprint(0));
            assertEquals(1, replacementCalls.get());
            assertEquals("new", manager.analyze(file, source).fingerprint(0));
            assertEquals(1, replacementCalls.get());
        } finally {
            Disposer.dispose(replacementLifetime);
            Disposer.dispose(originalLifetime);
        }
    }

    private static ContextProvider unknownProvider() {
        return new ContextProvider() {
            @Override public String id() { return "lifecycle-probe"; }
            @Override public boolean supports(PsiFile file) { return file.getName().endsWith(".py"); }
            @Override public ContextMap analyze(PsiFile file, String text) {
                return new ContextMap(id(), Collections.nCopies(ContextAnalysisManager.lineStarts(text).length, null));
            }
        };
    }
    private static void awaitReady(DoReviewService service, com.intellij.openapi.editor.Document document) {
        PlatformTestUtil.waitWithEventsDispatching("Provider change did not recompute review projection",
                () -> service.isProjectionReady(document), 10);
    }
}
