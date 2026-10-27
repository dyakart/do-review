package dev.doreview.ide.context;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.psi.PsiFile;
import dev.doreview.core.ContextMap;

/** Exercises actual installed parser PSI through optional extension registrations. */
public final class ContextAnalysisTest extends BasePlatformTestCase {
    public void testTactConditionAndFunctionHeadersChangeBodyContext() {
        String source = "fun sample(x: Int): Int {\n    if (x > 0) {\n        return x;\n    }\n    return 0;\n}\n";
        VirtualFile file = open("sample.tact", source);
        ContextMap baseline = analyze(file, source);
        assertEquals("tact:0.1.2:structure-v4", baseline.provider());
        assertNotNull(baseline.fingerprints().get(2));
        assertDiffers(baseline, analyze(file, source.replace("x > 0", "x > 1")), 2);
        assertDiffers(baseline, analyze(file, source.replace("x > 0", "x > 1")), 4);
        assertDiffers(baseline, analyze(file, source.replace("sample(", "renamed(")), 2);
        assertEquals(baseline.fingerprints().get(2), analyze(file, source.replace("return 0;", "return 1;")).fingerprints().get(2));
        assertEquals(source, myFixture.getEditor().getDocument().getText());
    }

    public void testTolkConditionAndFunctionHeadersChangeBodyContext() {
        String source = "fun sample(x: int): int {\n    if (x > 0) {\n        return x;\n    }\n    return 0;\n}\n";
        VirtualFile file = open("sample.tolk", source);
        ContextMap baseline = analyze(file, source);
        assertEquals("tolk:4.1.0:structure-v4", baseline.provider());
        assertNotNull(baseline.fingerprints().get(2));
        assertDiffers(baseline, analyze(file, source.replace("x > 0", "x > 1")), 2);
        assertDiffers(baseline, analyze(file, source.replace("x > 0", "x > 1")), 4);
        assertDiffers(baseline, analyze(file, source.replace("sample(", "renamed(")), 2);
        assertEquals(baseline.fingerprints().get(2), analyze(file, source.replace("return 0;", "return 1;")).fingerprints().get(2));
    }

    public void testPythonIfElifElseAndDecoratedFunction() {
        String source = "@decorator\ndef sample(x):\n    if x > 0:\n        return x\n    elif x < 0:\n        return -x\n    else:\n        return 0\n";
        VirtualFile file = open("sample.py", source);
        ContextMap baseline = analyze(file, source);
        assertTrue(baseline.provider(), baseline.provider().startsWith("python:262"));
        assertNotNull(baseline.fingerprints().get(3));
        ContextMap condition = analyze(file, source.replace("x > 0", "x > 1"));
        assertDiffers(baseline, condition, 3);
        assertDiffers(baseline, condition, 5);
        assertDiffers(baseline, condition, 7);
        assertDiffers(baseline, analyze(file, source.replace("@decorator", "@other")), 3);
        assertEquals(baseline.fingerprints().get(3), analyze(file, source.replace("return 0", "return 1")).fingerprints().get(3));
    }

    public void testPythonClassWithLoopAndExceptionHeaders() {
        String source = "class Example(Base):\n    def sample(self, values):\n        with resource() as handle:\n            for item in values:\n                try:\n                    consume(item)\n                except ValueError as failure:\n                    recover(failure)\n";
        VirtualFile file = open("sample.py", source);
        ContextMap baseline = analyze(file, source);
        assertNotNull(baseline.fingerprints().get(5));
        for (String[] change : new String[][]{{"Base", "Other"}, {"resource()", "other()"},
                {"in values", "in other"}, {"ValueError", "TypeError"}}) {
            assertDiffers(baseline, analyze(file, source.replace(change[0], change[1])), 5);
        }
    }

    public void testTactDoUntilFooterAndContractHeader() {
        String source = "contract Sample {\n    init() {}\n    fun sample(x: Int) {\n        do {\n            consume(x);\n        } until (x > 0);\n    }\n}\n";
        VirtualFile file = open("sample.tact", source);
        ContextMap baseline = analyze(file, source);
        assertNotNull(baseline.fingerprints().get(4));
        assertDiffers(baseline, analyze(file, source.replace("x > 0", "x > 1")), 4);
        assertDiffers(baseline, analyze(file, source.replace("contract Sample", "contract Other")), 4);
    }

    public void testTolkDoWhileFooter() {
        String source = "fun sample(x: int) {\n    do {\n        consume(x);\n    } while (x > 0);\n}\n";
        VirtualFile file = open("sample.tolk", source);
        ContextMap baseline = analyze(file, source);
        assertNotNull(baseline.fingerprints().get(2));
        assertDiffers(baseline, analyze(file, source.replace("x > 0", "x > 1")), 2);
    }

    public void testWhitespaceAndCommentsInHeadersDoNotChangeStructuralContext() {
        String source = "def sample(x):\n    if x > 0:\n        return x\n";
        VirtualFile file = open("sample.py", source);
        ContextMap baseline = analyze(file, source);
        assertEquals(baseline.fingerprints().get(2), analyze(file,
                source.replace("if x > 0:", "if  x > 0: # comment")).fingerprints().get(2));
    }

    public void testParseErrorIsTemporaryUnknownAndValidSnapshotCanBeReanalyzed() {
        for (String extension : new String[]{"py", "tact", "tolk"}) {
            String source = extension.equals("py") ? "def sample(x):\n    if x > 0:\n        return x\n"
                    : "fun sample(x: " + (extension.equals("tact") ? "Int" : "int")
                    + ") {\n    if (x > 0) {\n        consume(x);\n    }\n}\n";
            VirtualFile file = open("sample." + extension, source);
            ContextMap baseline = analyze(file, source);
            assertNotNull(extension, baseline.fingerprints().get(2));
            ContextMap broken = analyze(file, source.replace("x > 0", "x >"));
            assertNull(extension, broken.fingerprints().get(2));
            assertEquals(baseline, analyze(file, source));
        }
    }

    public void testUnsupportedTextUsesWholeFileFallback() {
        VirtualFile file = open("sample.txt", "first\nsecond\n");
        ContextMap before = analyze(file, "first\nsecond\n");
        ContextMap after = analyze(file, "changed\nsecond\n");
        assertEquals("fallback:whole-file-v1", before.provider());
        assertDiffers(before, after, 1);
    }

    public void testPythonMatchCasesAndComprehensionFilters() {
        String source = "def sample(value):\n    match value:\n        case 1 if allowed:\n            consume(value)\n        case _:\n            recover(value)\n";
        VirtualFile file = open("sample.py", source);
        ContextMap baseline = analyze(file, source);
        assertNotNull(baseline.fingerprints().get(3));
        assertDiffers(baseline, analyze(file, source.replace("if allowed", "if other")), 3);
        assertDiffers(baseline, analyze(file, source.replace("if allowed", "if other")), 5);

        String comprehension = "def sample(values):\n    return [\n        transform(item)\n        for item in values\n        if allowed(item)\n    ]\n";
        VirtualFile second = open("comprehension.py", comprehension);
        ContextMap original = analyze(second, comprehension);
        assertNotNull(original.fingerprints().get(2));
        assertDiffers(original, analyze(second, comprehension.replace("allowed(item)", "other(item)")), 2);
    }

    public void testIncompatibleProviderSelectionReturnsUnknownInsteadOfEscaping() {
        ContextProvider incompatible = new ContextProvider() {
            @Override public String id() { return "test:incompatible-provider"; }
            @Override public boolean supports(PsiFile file) { throw new NoSuchMethodError("Parser API changed"); }
            @Override public ContextMap analyze(PsiFile file, String text) { throw new AssertionError("Must not analyze"); }
        };
        ContextProvider.EP.getPoint().registerExtension(incompatible, getTestRootDisposable());
        VirtualFile file = open("unsupported.txt", "first\nsecond\n");
        ContextMap result = analyze(file, "first\nsecond\n");
        assertEquals("test:incompatible-provider", result.provider());
        assertTrue(result.fingerprints().stream().allMatch(value -> value == null));
    }

    private VirtualFile open(String name, String text) {
        return myFixture.configureByText(name, text).getVirtualFile();
    }

    private ContextMap analyze(VirtualFile file, String text) {
        return ContextAnalysisManager.analyze(getProject(), file, text);
    }

    private static void assertDiffers(ContextMap expected, ContextMap actual, int row) {
        assertNotNull(actual.fingerprints().get(row));
        assertFalse("Context must change at row " + row,
                expected.fingerprints().get(row).equals(actual.fingerprints().get(row)));
    }
}
