package dev.doreview.ide.context;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.doreview.core.ContextMap;

/** Small independent syntax regressions. Contains no application code or project identifiers. */
public final class StructuralGuardContextTest extends BasePlatformTestCase {
    public void testTactRequireChangeNegationInsertDeleteAreLimitedToFollowingRows() {
        String original = "fun sample(x: Int) {\n    prepare(x);\n    require(x > 0, \"valid\");\n    consume(x);\n}\n"
                + "fun unrelated(x: Int) {\n    inspect(x);\n}\n";
        VirtualFile file = open("guard.tact", original);
        ContextMap baseline = analyze(file, original);
        for (String current : new String[]{original.replace("x > 0", "x <= 0"),
                original.replace("    require(x > 0, \"valid\");\n", ""),
                original.replace("    consume(x);", "    require(x != 1, \"other\");\n    consume(x);")}) {
            ContextMap changed = analyze(file, current);
            different(baseline, original, changed, current, "    consume(x);");
            equal(baseline, original, changed, current, "    prepare(x);");
            equal(baseline, original, changed, current, "    inspect(x);");
        }
        String absent = original.replace("    require(x > 0, \"valid\");\n", "");
        different(analyze(file, absent), absent, baseline, original, "    consume(x);");
    }

    public void testTactEarlyReturnRemovalAndConditionChangeAffectFallthrough() {
        String original = "fun sample(x: Int) {\n    prepare(x);\n    if (x < 0) {\n        return;\n    }\n    consume(x);\n}\n";
        VirtualFile file = open("early.tact", original);
        ContextMap baseline = analyze(file, original);
        for (String current : new String[]{original.replace("x < 0", "x >= 0"),
                original.replace("return;", "inspect(x);"),
                original.replace("    if (x < 0) {\n        return;\n    }\n", "")}) {
            ContextMap changed = analyze(file, current);
            different(baseline, original, changed, current, "    consume(x);");
            equal(baseline, original, changed, current, "    prepare(x);");
        }
    }

    public void testTactMultilineGuardWhitespaceAndCommentDoNotAffectFollowingContext() {
        String original = "fun sample(x: Int) {\n    require(\n        x > 0,\n        \"valid\"\n    );\n    consume(x);\n}\n";
        VirtualFile file = open("multiline.tact", original);
        ContextMap baseline = analyze(file, original);
        String spacing = original.replace("x > 0,", "x  >  0, // explanation");
        equal(baseline, original, analyze(file, spacing), spacing, "    consume(x);");
        String negated = original.replace("x > 0", "x <= 0");
        different(baseline, original, analyze(file, negated), negated, "    consume(x);");
    }

    public void testTactElseIfChainTracksEarlierTestsAndPreservesEarlierBranches() {
        String original = "fun sample(x: Int) {\n    if (x == 0) {\n        first();\n    } else if (x == 1) {\n        second();\n    } else if (x == 2) {\n        third();\n    } else {\n        fallback();\n    }\n    after();\n}\n";
        VirtualFile file = open("branches.tact", original);
        ContextMap baseline = analyze(file, original);
        String later = original.replace("x == 1", "x == 10");
        ContextMap changed = analyze(file, later);
        equal(baseline, original, changed, later, "        first();");
        different(baseline, original, changed, later, "        second();");
        different(baseline, original, changed, later, "        third();");
        different(baseline, original, changed, later, "        fallback();");
        equal(baseline, original, changed, later, "    after();");
        String first = original.replace("x == 0", "x == 20");
        ContextMap firstChanged = analyze(file, first);
        for (String row : new String[]{"        first();", "        second();", "        third();", "        fallback();"}) {
            different(baseline, original, firstChanged, first, row);
        }
    }

    public void testTactAddingEnclosingGuardChangesOnlyEnclosedRows() {
        String original = "fun sample(x: Int) {\n    prepare();\n    consume();\n    after();\n}\n";
        VirtualFile file = open("enclosing.tact", original);
        String current = original.replace("    consume();", "    if (x > 0) {\n    consume();\n    }");
        ContextMap baseline = analyze(file, original), changed = analyze(file, current);
        different(baseline, original, changed, current, "    consume();");
        equal(baseline, original, changed, current, "    prepare();");
        equal(baseline, original, changed, current, "    after();");
    }

    public void testTactHandlerAndOwnerHeaders() {
        String original = "contract Sample {\n    init() {}\n    receive(\"alpha\") {\n        consume();\n    }\n}\n";
        VirtualFile file = open("handler.tact", original);
        ContextMap baseline = analyze(file, original);
        for (String current : new String[]{original.replace("\"alpha\"", "\"beta\""),
                original.replace("contract Sample", "contract Other")}) {
            different(baseline, original, analyze(file, current), current, "        consume();");
        }
    }

    public void testTactTryCatchHeaderAndNestedGate() {
        String original = "fun sample(x: Int) {\n    prepare();\n    try {\n        require(x > 0, \"valid\");\n        consume(x);\n    } catch (code) {\n        recover(code);\n    }\n    after();\n}\n";
        VirtualFile file = open("catch.tact", original);
        ContextMap baseline = analyze(file, original);
        String current = original.replace("catch (code)", "catch (error)");
        ContextMap changed = analyze(file, current);
        different(baseline, original, changed, current, "        consume(x);");
        different(baseline, original, changed, current, "    after();");
        equal(baseline, original, changed, current, "    prepare();");
    }

    public void testTactCustomCallNamesAreNotGuessedFromPrefix() {
        String original = "fun sample(x: Int) {\n    self.requireCustomGate(x);\n    consume(x);\n}\n";
        VirtualFile file = open("custom.tact", original);
        String current = original.replace("requireCustomGate(x)", "requireCustomGate(x + 1)");
        equal(analyze(file, original), original, analyze(file, current), current, "    consume(x);");
    }

    public void testTactNativeGuardCallAndThrowGate() {
        String original = "fun sample(x: Int) {\n    nativeThrowUnless(1, x > 0);\n    consume(x);\n}\n";
        VirtualFile file = open("native.tact", original);
        String current = original.replace("x > 0", "x <= 0");
        different(analyze(file, original), original, analyze(file, current), current, "    consume(x);");
    }

    public void testTactRemovingExplicitNotNullAssertionChangesFollowingContext() {
        String original = "fun sample(value: Int?) {\n    let checked = value!!;\n    consume(checked);\n}\n";
        VirtualFile file = open("nonnull.tact", original);
        String current = original.replace("value!!", "value");
        different(analyze(file, original), original, analyze(file, current), current, "    consume(checked);");
    }

    public void testPythonAssertAndEarlyReturnAffectFollowingRows() {
        String original = "def sample(x):\n    prepare()\n    assert x > 0\n    if x == 1:\n        return\n    consume(x)\n";
        VirtualFile file = open("guard.py", original);
        ContextMap baseline = analyze(file, original);
        for (String current : new String[]{original.replace("assert x > 0", "assert x >= 0"),
                original.replace("x == 1", "x == 2"), original.replace("        return", "        inspect()")}) {
            ContextMap changed = analyze(file, current);
            different(baseline, original, changed, current, "    consume(x)");
            equal(baseline, original, changed, current, "    prepare()");
        }
    }

    public void testPythonNestedFunctionReturnsDoNotBecomeOuterGuards() {
        String original = "def sample(x):\n    def inner():\n        return x\n    consume(x)\n";
        VirtualFile file = open("nested.py", original);
        String current = original.replace("return x", "return x + 1");
        equal(analyze(file, original), original, analyze(file, current), current, "    consume(x)");
    }

    public void testTolkAssertAndEarlyReturnAffectFollowingRows() {
        String original = "fun sample(x: int) {\n    prepare();\n    assert (x > 0) throw 1;\n    if (x == 1) {\n        return;\n    }\n    consume(x);\n}\n";
        VirtualFile file = open("guard.tolk", original);
        ContextMap baseline = analyze(file, original);
        for (String current : new String[]{original.replace("x > 0", "x >= 0"),
                original.replace("x == 1", "x == 2"), original.replace("return;", "inspect();")}) {
            ContextMap changed = analyze(file, current);
            different(baseline, original, changed, current, "    consume(x);");
            equal(baseline, original, changed, current, "    prepare();");
        }
    }

    public void testTactLiteralDiagnosticsKeepFollowingContextButConditionsDoNot() {
        String original = "fun sample(x: Int) {\n    prepare();\n    require(x > 0, \"valid\");\n    nativeThrowUnless(1, x != 2);\n    consume(x);\n}\n";
        VirtualFile file = open("diagnostic.tact", original);
        ContextMap baseline = analyze(file, original);
        String messages = original.replace("\"valid\"", "\"different diagnostic\"").replace("nativeThrowUnless(1,", "nativeThrowUnless(42,");
        equal(baseline, original, analyze(file, messages), messages, "    consume(x);");
        String condition = messages.replace("x > 0", "x >= 0");
        different(baseline, original, analyze(file, condition), condition, "    consume(x);");
        equal(baseline, original, analyze(file, condition), condition, "    prepare();");
    }

    public void testTactLiteralReturnPayloadKeepsExitShapeButReturnRemovalDoesNot() {
        String original = "fun sample(x: Int): Int {\n    prepare();\n    if (x < 0) {\n        return 1;\n    }\n    consume(x);\n    return 0;\n}\n";
        VirtualFile file = open("payload.tact", original);
        ContextMap baseline = analyze(file, original);
        String payload = original.replace("return 1;", "return 42;");
        equal(baseline, original, analyze(file, payload), payload, "    consume(x);");
        for (String current : new String[]{payload.replace("x < 0", "x >= 0"),
                payload.replace("return 42;", "inspect(x);")}) {
            different(baseline, original, analyze(file, current), current, "    consume(x);");
        }
    }

    public void testComputedAndQualifiedDiagnosticPayloadsRemainConservative() {
        for (String gate : new String[]{"require(x > 0, diagnostic(1));", "self.require(x > 0, \"valid\");"}) {
            String original = "fun sample(x: Int) {\n    " + gate + "\n    consume(x);\n}\n";
            VirtualFile file = open("computed.tact", original);
            String current = original.replace("diagnostic(1)", "diagnostic(2)").replace("\"valid\"", "\"changed\"");
            different(analyze(file, original), original, analyze(file, current), current, "    consume(x);");
        }
    }

    public void testMalformedDiagnosticIsStillAnUnknownDependencyAndRecovers() {
        String original = "fun sample(x: Int) {\n    require(x > 0, \"valid\");\n    consume(x);\n}\n";
        VirtualFile file = open("malformed.tact", original);
        String broken = original.replace("\"valid\"", "\"unterminated");
        ContextMap map = analyze(file, broken);
        String[] lines = broken.split("\n", -1);
        for (int row = 0; row < lines.length; row++) if (lines[row].equals("    consume(x);")) {
            assertNull("Parse errors in ignored diagnostic payloads must remain unknown", map.fingerprint(row));
        }
        String recovered = original.replace("\"valid\"", "\"recovered\"");
        equal(analyze(file, original), original, analyze(file, recovered), recovered, "    consume(x);");
    }

    public void testPythonLiteralAssertMessageAndReturnPayloadPreserveFollowingContext() {
        String original = "def sample(x):\n    prepare()\n    assert x > 0, 'valid'\n    if x == 1:\n        return 1\n    consume(x)\n";
        VirtualFile file = open("payload.py", original);
        ContextMap baseline = analyze(file, original);
        String payload = original.replace("'valid'", "'different diagnostic'").replace("return 1", "return 42");
        equal(baseline, original, analyze(file, payload), payload, "    consume(x)");
        String changed = payload.replace("x > 0", "x >= 0");
        different(baseline, original, analyze(file, changed), changed, "    consume(x)");
        String computed = original.replace("'valid'", "f'{diagnostic(1)}'");
        String computedChange = computed.replace("diagnostic(1)", "diagnostic(2)");
        different(analyze(file, computed), computed, analyze(file, computedChange), computedChange, "    consume(x)");
    }

    public void testTolkLiteralAssertCodeAndReturnPayloadPreserveFollowingContext() {
        String original = "fun sample(x: int): int {\n    prepare();\n    assert (x > 0) throw 1;\n    if (x == 1) {\n        return 1;\n    }\n    consume(x);\n    return 0;\n}\n";
        VirtualFile file = open("payload.tolk", original);
        ContextMap baseline = analyze(file, original);
        String payload = original.replace("throw 1", "throw 42").replace("return 1;", "return 42;");
        equal(baseline, original, analyze(file, payload), payload, "    consume(x);");
        String changed = payload.replace("x > 0", "x >= 0");
        different(baseline, original, analyze(file, changed), changed, "    consume(x);");
    }

    private VirtualFile open(String name, String source) { return myFixture.configureByText(name, source).getVirtualFile(); }
    private ContextMap analyze(VirtualFile file, String source) { return ContextAnalysisManager.analyze(getProject(), file, source); }
    private static String fingerprint(ContextMap map, String source, String row) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) if (lines[i].equals(row)) {
            assertNotNull("Valid tracked context required", map.fingerprint(i));
            return map.fingerprint(i);
        }
        throw new AssertionError("Anonymous probe row missing");
    }
    private static void different(ContextMap before, String oldText, ContextMap after, String newText, String row) {
        assertFalse("Tracked structural context must change", fingerprint(before, oldText, row).equals(fingerprint(after, newText, row)));
    }
    private static void equal(ContextMap before, String oldText, ContextMap after, String newText, String row) {
        assertEquals("Unrelated structural context must survive", fingerprint(before, oldText, row), fingerprint(after, newText, row));
    }
}
