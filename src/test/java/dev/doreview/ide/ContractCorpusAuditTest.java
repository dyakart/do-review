package dev.doreview.ide;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.xmlb.XmlSerializer;
import dev.doreview.core.ContextMap;
import dev.doreview.core.FileProjection;
import dev.doreview.core.ReviewHistory;
import dev.doreview.ide.context.ContextAnalysisManager;
import org.tonstudio.tact.lang.TactTypes;
import org.tonstudio.tact.lang.psi.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional audit of caller-supplied source copies. Never embeds corpus paths or source in reports. */
public final class ContractCorpusAuditTest extends BasePlatformTestCase {
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w\"'])\\b[0-9]+\\b(?![\\w\"'])");
    private static final Pattern FUNCTION = Pattern.compile("\\bfun\\s+([A-Za-z_]\\w*)");
    private static final Pattern PARAMETER = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*:");
    private static final Map<String, Integer> COMPLETED = new TreeMap<>();
    private DoReviewService service;
    private ReviewHistoryStore store;
    private Document doc;
    private PsiFile file;
    private ContextMap context;
    private String original;
    private int ordinal;
    private int retained;
    private int checked;
    private int falseGreen;
    private String category;

    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture project = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(project, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(project.getProject().getBasePath()); }
        });
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        store = ReviewHistoryStore.getInstance();
        store.loadState(new ReviewHistoryStore.StoreState());
        service = DoReviewService.getInstance(getProject());
        service.historyReloaded();
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (doc != null && context != null) report();
            if (myFixture != null) {
                Path base = Path.of(getProject().getBasePath()).toAbsolutePath().normalize();
                Path git = base.resolve(".git").normalize();
                assertTrue("Audit cleanup must stay inside its disposable fixture", git.startsWith(base) && !git.equals(base));
                if (Files.isDirectory(git)) try (var entries = Files.walk(git)) {
                    for (Path entry : entries.toList()) {
                        var attributes = Files.getFileAttributeView(entry, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                        if (attributes != null) attributes.setReadOnly(false);
                    }
                }
            }
        } finally { super.tearDown(); }
    }

    public void testExactReviewAndApplicationXmlReload() {
        open(1, "exact-restart");
        ReviewHistory history = service.history(doc);
        var before = statuses();
        reloadHistory();
        assertTrue("XML restart must preserve immutable evidence", history.equals(service.history(doc)));
        assertEquals(before, statuses());
        assertTrue("Corpus review must contain known reviewed rows", service.coverage(doc).reviewed() > 0);
    }

    public void testSingleLiteralMutationInvalidatesKnownRow() {
        open(2, "literal");
        Candidate target = numericCandidate(); Candidate control = independent(target);
        ReviewHistory history = service.history(doc);
        mutateNumber(target);
        hasStatus(target.line(), FileProjection.Status.CHANGED); keep(control.line());
        assertTrue("Source edits must not rewrite review history", history.equals(service.history(doc)));
    }

    public void testTrailingWhitespaceIsNotSilentlyReviewed() {
        open(1, "whitespace");
        int line = knownNonBlankLine();
        edit(() -> doc.insertString(doc.getLineEndOffset(line), "  "));
        hasStatus(line, FileProjection.Status.CHANGED);
    }

    public void testNewCommentRowHasNoHistoricalOrigin() {
        open(5, "comment-insertion");
        int line = knownNonBlankLine();
        edit(() -> doc.insertString(doc.getLineStartOffset(line), "// corpus audit probe\n"));
        isNew(line); keep(line + 1);
    }

    public void testNewExecutableRowHasNoHistoricalOrigin() {
        open(4, "code-insertion");
        Candidate target = candidate(true);
        String indent = indentation(target.line());
        edit(() -> doc.insertString(target.start(), indent + "let corpusAuditProbe: Int = 0;\n"));
        isNew(target.line()); keep(target.line() + 1);
    }

    public void testIdenticalInsertionPreservesOnlyOriginalIdentity() {
        open(2, "duplicate");
        duplicate(false);
    }

    public void testUnrelatedFileCreationDoesNotLoseDuplicateIdentity() {
        open(3, "duplicate-unrelated-vfs");
        duplicate(true);
    }

    public void testCopyInsertedAtOriginalLineEndingRetainsOriginalReview() {
        open(2, "split-copy-boundary");
        Candidate target = candidate(true);
        String copied = row(target.line());
        int boundary = doc.getLineEndOffset(target.line());
        edit(() -> doc.insertString(boundary, "\n" + copied + "\n"));
        keep(target.line()); isNew(target.line() + 1);
        FileDocumentManager.getInstance().saveDocument(doc);
        myFixture.addFileToProject("unrelated-boundary.txt", "unrelated\n"); fresh();
        keep(target.line()); isNew(target.line() + 1);
        FileEditorManager.getInstance(getProject()).closeFile(file.getVirtualFile());
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile()); fresh();
        notGreen(target.line() + 1);
        reloadHistory(); notGreen(target.line() + 1);
    }

    public void testDeletingWholeReviewedRowRetainsHistoricalDeletion() {
        open(0, "delete-row");
        Candidate target = candidate(true); Candidate control = independent(target);
        ReviewHistory history = service.history(doc);
        edit(() -> doc.deleteString(target.start(), target.end()));
        assertTrue("Deleted reviewed source must remain represented", service.projection(doc).deletedCount() > 0);
        int controlLine = control.line() > target.line() ? control.line() - 1 : control.line();
        keep(controlLine);
        assertTrue("Deletion must preserve immutable evidence", history.equals(service.history(doc)));
    }

    public void testMultilineReplacementRejectsEveryChangedRow() {
        open(3, "multiline");
        Candidate[] pair = adjacent();
        String first = row(pair[0].line()) + " // corpus audit first";
        String second = row(pair[1].line()) + " // corpus audit second";
        edit(() -> doc.replaceString(pair[0].start(), doc.getLineEndOffset(pair[1].line()), first + "\n" + second));
        notGreen(pair[0].line()); notGreen(pair[1].line());
    }

    public void testJoiningReviewedRowsCreatesUnreviewedSource() {
        open(4, "join-lines");
        Candidate[] pair = adjacent();
        int boundary = doc.getLineEndOffset(pair[0].line());
        edit(() -> doc.deleteString(boundary, boundary + 1));
        notGreen(pair[0].line());
    }

    public void testIfConditionMutationRequiresBodyRecheck() {
        open(0, "if-context");
        var target = conditionTarget(TactIfStatement.class);
        int bodyLine = target.bodyLine();
        edit(() -> doc.replaceString(target.start(), target.end(), "!(" + target.text() + ")"));
        hasStatus(bodyLine, FileProjection.Status.NEEDS_REVIEW);
    }

    public void testLoopConditionMutationRequiresBodyRecheck() {
        open(4, "loop-context");
        var target = conditionTarget(TactWhileStatement.class);
        edit(() -> doc.replaceString(target.start(), target.end(), "!(" + target.text() + ")"));
        hasStatus(target.bodyLine(), FileProjection.Status.NEEDS_REVIEW);
    }

    public void testFunctionHeaderRenameRequiresBodyRecheck() {
        open(0, "function-context");
        Header target = functionHeader();
        edit(() -> doc.replaceString(target.start(), target.end(), "corpusAuditRenamed"));
        hasStatus(target.bodyLine(), FileProjection.Status.NEEDS_REVIEW);
    }

    public void testMovingRowBetweenMessageHandlersCannotInheritReview() {
        open(2, "handler-move");
        List<TactMessageFunctionDeclaration> handlers = sorted(PsiTreeUtil.findChildrenOfType(file, TactMessageFunctionDeclaration.class));
        Candidate source = null; TactMessageFunctionDeclaration destination = null;
        for (var handler : handlers) {
            if (source == null) source = candidates(true).stream().filter(c -> PsiTreeUtil.isAncestor(handler, c.element(), false)).findFirst().orElse(null);
            else if (handler.getBlock() != null && handler.getTextRange().getStartOffset() > source.end()) { destination = handler; break; }
        }
        assertNotNull("Corpus requires a movable message body row", source);
        assertNotNull("Corpus requires a distinct later message handler", destination);
        Candidate chosen = source;
        int insertion = doc.getLineStartOffset(doc.getLineNumber(destination.getBlock().getTextRange().getStartOffset()) + 1);
        String moved = doc.getText().substring(chosen.start(), chosen.end());
        int newLine = doc.getLineNumber(insertion) - 1;
        edit(() -> { doc.insertString(insertion, moved); doc.deleteString(chosen.start(), chosen.end()); });
        notGreen(newLine);
    }

    public void testTemporaryParseFailureNeverKeepsBodyGreen() {
        open(3, "parse-failure");
        var target = conditionTarget(TactIfStatement.class);
        edit(() -> doc.replaceString(target.start(), target.end(), "corpusAuditValue >"));
        notGreen(target.bodyLine());
        edit(() -> doc.setText(original)); keep(target.bodyLine());
    }

    public void testMalformedFollowingHandlerKeepsCompletedConstructorReviewed() {
        open(2, "later-handler-after-init");
        RecoveryPair target = recoveryPair(TactContractInitDeclaration.class);
        malformFollowingHandler(target);
        keep(target.earlierBodyLine()); notGreen(target.followingBodyLine());
        edit(() -> doc.setText(original));
        keep(target.earlierBodyLine()); keep(target.followingBodyLine());
    }

    public void testMalformedFollowingHandlerKeepsCompletedHandlerReviewed() {
        open(3, "later-handler-after-handler");
        RecoveryPair target = recoveryPair(TactMessageFunctionDeclaration.class);
        malformFollowingHandler(target);
        keep(target.earlierBodyLine()); notGreen(target.followingBodyLine());
        edit(() -> doc.setText(original));
        keep(target.earlierBodyLine()); keep(target.followingBodyLine());
    }

    public void testEarlierLiteralStillChangesBeforeMalformedFollowingHandler() {
        open(2, "later-handler-literal-control");
        RecoveryPair target = recoveryPair(TactContractInitDeclaration.class);
        Candidate numeric = candidates(false).stream().filter(candidate -> candidate.owner() == target.earlier())
                .filter(candidate -> NUMBER.matcher(candidate.element().getText()).find()).findFirst()
                .orElseThrow(() -> new AssertionError("Corpus requires a numeric row in the earlier complete declaration"));
        Matcher match = NUMBER.matcher(numeric.element().getText());
        assertTrue("Earlier numeric row must contain a literal", match.find());
        int start = numeric.element().getTextOffset() + match.start();
        int end = numeric.element().getTextOffset() + match.end();
        String replacement = changedNumber(match.group());
        malformFollowingHandler(target);
        keep(numeric.line()); notGreen(target.followingBodyLine());
        edit(() -> doc.replaceString(start, end, replacement));
        hasStatus(numeric.line(), FileProjection.Status.CHANGED); notGreen(target.followingBodyLine());
        edit(() -> doc.setText(original));
        keep(numeric.line()); keep(target.followingBodyLine());
    }

    public void testEarlierHeaderStillRequiresRecheckBeforeMalformedFollowingHandler() {
        open(2, "later-handler-header-control");
        RecoveryPair target = recoveryPair(TactContractInitDeclaration.class);
        int start = target.earlier().getTextRange().getStartOffset();
        String prefix = original.substring(start, target.earlierBlock().getTextOffset());
        Matcher parameter = PARAMETER.matcher(prefix);
        assertTrue("Corpus requires a parameter in the earlier complete declaration header", parameter.find());
        int parameterStart = start + parameter.start(1), parameterEnd = start + parameter.end(1);
        malformFollowingHandler(target);
        keep(target.earlierBodyLine()); notGreen(target.followingBodyLine());
        edit(() -> doc.replaceString(parameterStart, parameterEnd, "corpusAuditArgument"));
        hasStatus(target.earlierBodyLine(), FileProjection.Status.NEEDS_REVIEW); notGreen(target.followingBodyLine());
        edit(() -> doc.setText(original));
        keep(target.earlierBodyLine()); keep(target.followingBodyLine());
    }

    public void testMalformedUpperHandlerHeaderPreservesAdjacentLowerHandler() {
        open(3, "upper-handler-typo-lower-isolation");
        HandlerBoundary target = adjacentHandlerBoundary(false, false);
        for (String token : List.of(" a", " f")) {
            malformUpperHandler(target, token);
            notGreen(target.upperBodyLine()); keep(target.lowerBodyLine());
            edit(() -> doc.setText(original));
            keep(target.upperBodyLine()); keep(target.lowerBodyLine());
        }
    }

    public void testLowerLiteralStillChangesAfterMalformedUpperHandler() {
        open(3, "upper-handler-lower-literal-control");
        HandlerBoundary target = adjacentHandlerBoundary(true, false);
        NumericEdit number = target.lowerNumber();
        malformUpperHandler(target, " a");
        keep(number.line()); notGreen(target.upperBodyLine());
        edit(() -> doc.replaceString(number.start() + 2, number.end() + 2, number.replacement()));
        hasStatus(number.line(), FileProjection.Status.CHANGED);
        edit(() -> doc.setText(original));
        keep(number.line()); keep(target.upperBodyLine());
    }

    public void testLowerHeaderStillRequiresRecheckAfterMalformedUpperHandler() {
        open(3, "upper-handler-lower-header-control");
        HandlerBoundary target = adjacentHandlerBoundary(false, false);
        Header header = target.lowerHeader();
        malformUpperHandler(target, " f");
        keep(target.lowerBodyLine());
        edit(() -> doc.replaceString(header.start() + 2, header.end() + 2, "corpusAuditLowerArgument"));
        hasStatus(target.lowerBodyLine(), FileProjection.Status.NEEDS_REVIEW);
        notGreen(target.upperBodyLine());
        edit(() -> doc.setText(original));
        keep(target.lowerBodyLine()); keep(target.upperBodyLine());
    }

    public void testNewLowerBodyRowDoesNotInheritReviewAfterMalformedUpperHandler() {
        open(3, "upper-handler-lower-new-row");
        HandlerBoundary target = adjacentHandlerBoundary(false, false);
        String inserted = indentation(target.lowerBodyLine()) + "let corpusAuditLowerRow: Int = 0;\n";
        malformUpperHandler(target, " a");
        edit(() -> doc.insertString(target.lowerBodyStart() + 2, inserted));
        isNew(target.lowerBodyLine()); keep(target.lowerBodyLine() + 1);
        notGreen(target.upperBodyLine());
        edit(() -> doc.setText(original));
        keep(target.lowerBodyLine()); keep(target.upperBodyLine());
    }

    public void testMissingUpperOwnClosingBracePreventsLowerHandlerRecovery() {
        open(3, "upper-handler-missing-own-brace");
        HandlerBoundary target = adjacentHandlerBoundary(false, false);
        malformUpperHandler(target, " a");
        keep(target.lowerBodyLine());
        edit(() -> doc.deleteString(target.upperClosingBrace() + 2, target.upperClosingBrace() + 3));
        assertRealParseFailure(); notGreen(target.lowerBodyLine());
        edit(() -> doc.setText(original));
        keep(target.lowerBodyLine()); keep(target.upperBodyLine());
    }

    public void testUnclosedUpperHeaderParenthesisPreventsLowerHandlerRecovery() {
        open(3, "upper-handler-unclosed-parenthesis");
        HandlerBoundary target = adjacentHandlerBoundary(false, false);
        edit(() -> doc.deleteString(target.upperClosingParenthesis(), target.upperClosingParenthesis() + 1));
        assertRealParseFailure(); notGreen(target.lowerBodyLine());
        edit(() -> doc.setText(original));
        keep(target.lowerBodyLine()); keep(target.upperBodyLine());
    }

    public void testUnclosedUpperStringPreventsLowerHandlerRecovery() {
        open(3, "upper-handler-unclosed-string");
        HandlerBoundary target = adjacentHandlerBoundary(false, true);
        malformUpperHandler(target, " f");
        keep(target.lowerBodyLine());
        edit(() -> doc.deleteString(target.upperStringClosingQuote() + 2, target.upperStringClosingQuote() + 3));
        assertRealParseFailure(); notGreen(target.lowerBodyLine());
        edit(() -> doc.setText(original));
        keep(target.lowerBodyLine()); keep(target.upperBodyLine());
    }

    public void testUnclosedUpperCommentPreventsLowerHandlerRecovery() {
        open(3, "upper-handler-unclosed-comment");
        HandlerBoundary target = adjacentHandlerBoundary(false, false);
        int commentEnd = original.indexOf("*/", target.upperClosingBrace());
        assertTrue("The inserted comment must remain open across the lower handler header",
                commentEnd < 0 || commentEnd > target.lowerHeader().end());
        malformUpperHandler(target, " a");
        keep(target.lowerBodyLine());
        edit(() -> doc.insertString(target.upperClosingBrace() + 2, "/*"));
        assertRealParseFailure(); notGreen(target.lowerBodyLine());
        edit(() -> doc.setText(original));
        keep(target.lowerBodyLine()); keep(target.upperBodyLine());
    }

    public void testActualEditorUndoRestoresOriginalEvidence() {
        open(2, "undo");
        Candidate target = numericCandidate();
        mutateNumber(target); notGreen(target.line());
        var editor = FileEditorManager.getInstance(getProject()).getSelectedEditor(file.getVirtualFile());
        var undo = UndoManager.getInstance(getProject());
        assertTrue("Controlled edit must be undoable", undo.isUndoAvailable(editor));
        undo.undo(editor); fresh();
        assertTrue("Undo must restore exact input", original.equals(doc.getText())); keep(target.line());
    }

    public void testActualEditorRedoInvalidatesChangedRowAgain() {
        open(4, "redo");
        Candidate target = numericCandidate(); mutateNumber(target);
        var editor = FileEditorManager.getInstance(getProject()).getSelectedEditor(file.getVirtualFile());
        var undo = UndoManager.getInstance(getProject());
        assertTrue("Controlled edit must be undoable", undo.isUndoAvailable(editor));
        undo.undo(editor); fresh(); keep(target.line());
        assertTrue("Controlled edit must be redoable", undo.isRedoAvailable(editor));
        undo.redo(editor); fresh(); notGreen(target.line());
    }

    public void testXmlReloadWhileChangedThenExactReturnRestoresReview() {
        open(0, "changed-restart");
        Candidate target = numericCandidate(); mutateNumber(target); notGreen(target.line());
        reloadHistory(); notGreen(target.line());
        edit(() -> doc.setText(original)); keep(target.line());
    }

    public void testManualRevokeSurvivesRestartAndExactSourceReturn() {
        open(6, "manual-revoke");
        Candidate target = candidate(false);
        service.clear(doc, target.start(), target.end()); fresh(); notGreen(target.line());
        edit(() -> doc.insertString(doc.getLineEndOffset(target.line()), " // corpus audit change"));
        reloadHistory(); edit(() -> doc.setText(original)); notGreen(target.line());
        assertTrue("Explicit revoke must be persisted", !service.history(doc).revocations().isEmpty());
    }

    public void testClearingChangedVariantAlsoRevokesOlderVariant() {
        open(3, "variant-revoke");
        Candidate target = numericCandidate(); mutateNumber(target);
        markLine(target.line()); keep(target.line());
        service.clear(doc, doc.getLineStartOffset(target.line()), endOfLine(target.line())); fresh();
        notGreen(target.line()); reloadHistory(); edit(() -> doc.setText(original)); notGreen(target.line());
    }

    public void testGitBranchRoundTripAndXmlRestartRestoreOriginalReview() throws Exception {
        open(2, "git-round-trip");
        Candidate target = numericCandidate();
        FileDocumentManager.getInstance().saveAllDocuments();
        Path template = Path.of(getProject().getBasePath(), ".git-empty-template"); Files.createDirectories(template);
        git("init", "-q", "-b", "audit-a", "--template=" + template);
        git("add", "--", relativeFile()); git("commit", "-q", "-m", "audit A");
        git("checkout", "-q", "-b", "audit-b");
        mutateNumber(target); notGreen(target.line()); FileDocumentManager.getInstance().saveDocument(doc);
        git("add", "--", relativeFile()); git("commit", "-q", "-m", "audit B");
        reloadHistory(); notGreen(target.line());
        git("checkout", "-q", "audit-a"); reloadFromDisk(); keep(target.line());
        assertTrue("Branch return must restore exact source", original.equals(doc.getText()));
        git("checkout", "-q", "audit-b"); reloadFromDisk(); notGreen(target.line());
        git("checkout", "-q", "audit-a"); reloadFromDisk(); keep(target.line());
    }

    public void testClearAllAlsoRevokesDeletedHistoricalRows() {
        open(6, "clear-deleted");
        Candidate target = candidate(false);
        edit(() -> doc.deleteString(target.start(), target.end()));
        assertTrue("Deleted evidence must remain addressable", service.projection(doc).deletedCount() > 0);
        service.clearFile(doc); fresh(); reloadHistory(); edit(() -> doc.setText(original));
        notGreen(target.line()); assertTrue("Clear file must persist revocations", !service.history(doc).revocations().isEmpty());
    }

    public void testPrecedingGuardMutationRequiresFollowingRowRecheck() {
        open(3, "guard-context");
        for (var block : sorted(PsiTreeUtil.findChildrenOfType(file, TactBlock.class))) {
            List<TactStatement> statements = block.getStatementList();
            for (int i = 0; i + 1 < statements.size(); i++) {
                TactStatement guard = statements.get(i);
                if (!guard.getText().stripLeading().startsWith("require(")) continue;
                var call = PsiTreeUtil.findChildOfType(guard, TactCallExpr.class);
                if (call == null || call.getArguments().isEmpty()) continue;
                var condition = call.getArguments().getFirst();
                int body = firstKnownLine(statements.get(i + 1));
                if (body < 0) continue;
                int offset = condition.getTextRange().getStartOffset();
                int end = condition.getTextRange().getEndOffset();
                String changed = "!(" + condition.getText() + ")";
                edit(() -> doc.replaceString(offset, end, changed)); hasStatus(body, FileProjection.Status.NEEDS_REVIEW); return;
            }
        }
        fail("Corpus requires a guard condition followed by a known body row");
    }

    private void open(int index, String auditCategory) {
        ordinal = index; category = auditCategory;
        String corpus = System.getProperty("reviewMarks.corpus");
        assertNotNull("Optional corpus property is required for this explicitly selected suite", corpus);
        try (var files = Files.walk(Path.of(corpus))) {
            List<Path> inputs = files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".tact")).sorted().toList();
            assertEquals("Audit requires the complete seven-file input copy", 7, inputs.size());
            original = Files.readString(inputs.get(index), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException | RuntimeException error) { throw new AssertionError("Cannot read the optional audit input copy"); }
        file = myFixture.addFileToProject("corpus-" + index + ".tact", original);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        doc = myFixture.getEditor().getDocument();
        assertTrue("Audit fixture must use a supported local project file", service.supports(doc));
        PsiDocumentManager.getInstance(getProject()).commitDocument(doc);
        context = ContextAnalysisManager.analyze(getProject(), file.getVirtualFile(), original);
        assertTrue("Actual Tact parser provider must be active", context.provider().startsWith("tact:"));
        service.mark(doc, 0, doc.getTextLength()); fresh();
    }

    private void duplicate(boolean unrelatedFile) {
        Candidate target = candidate(true);
        String copied = doc.getText().substring(target.start(), target.end());
        edit(() -> doc.insertString(target.start(), copied));
        isNew(target.line()); keep(target.line() + 1);
        if (unrelatedFile) {
            myFixture.addFileToProject("unrelated.txt", "unrelated\n"); fresh();
            isNew(target.line()); keep(target.line() + 1);
        }
    }

    private List<Candidate> candidates(boolean nonGate) {
        List<Candidate> result = new ArrayList<>();
        for (TactStatement statement : sorted(PsiTreeUtil.findChildrenOfType(file, TactStatement.class))) {
            String text = statement.getText();
            if (text.contains("\n") || !text.endsWith(";")) continue;
            if (nonGate && (text.matches("(?s).*\\b(return|require|assert|throw|throwIf|throwUnless|nativeThrow)\\b.*"))) continue;
            int line = doc.getLineNumber(statement.getTextOffset());
            if (context.fingerprint(line) == null || !green(line)) continue;
            int start = doc.getLineStartOffset(line), end = doc.getLineEndOffset(line);
            if (!doc.getText().substring(start, statement.getTextOffset()).isBlank()
                    || !doc.getText().substring(statement.getTextRange().getEndOffset(), end).isBlank()) continue;
            PsiElement owner = statement;
            while (owner != null && !(owner instanceof TactFunctionDeclaration)
                    && !(owner instanceof TactMessageFunctionDeclaration) && !(owner instanceof TactContractInitDeclaration)) owner = owner.getParent();
            result.add(new Candidate(line, start, end < doc.getTextLength() ? end + 1 : end, owner, statement));
        }
        return result;
    }

    private Candidate candidate(boolean nonGate) {
        List<Candidate> result = candidates(nonGate);
        assertTrue("Corpus requires a complete known statement row", !result.isEmpty());
        return result.getFirst();
    }

    private Candidate numericCandidate() {
        return candidates(false).stream().filter(c -> NUMBER.matcher(c.element().getText()).find()).findFirst()
                .orElseThrow(() -> new AssertionError("Corpus requires a known numeric statement row"));
    }

    private Candidate independent(Candidate target) {
        return candidates(false).stream().filter(c -> c.owner() != null && c.owner() != target.owner()).findFirst()
                .orElseThrow(() -> new AssertionError("Corpus requires an independent reviewed body control"));
    }

    private Candidate[] adjacent() {
        var list = candidates(true);
        for (int i = 0; i + 1 < list.size(); i++) if (list.get(i + 1).line() == list.get(i).line() + 1
                && list.get(i).owner() == list.get(i + 1).owner()) return new Candidate[]{list.get(i), list.get(i + 1)};
        throw new AssertionError("Corpus requires two adjacent complete known body rows");
    }

    private void mutateNumber(Candidate candidate) {
        Matcher match = NUMBER.matcher(candidate.element().getText()); assertTrue("Numeric target disappeared", match.find());
        int start = candidate.element().getTextOffset() + match.start(), end = candidate.element().getTextOffset() + match.end();
        String replacement = changedNumber(match.group()); edit(() -> doc.replaceString(start, end, replacement));
    }

    private static String changedNumber(String number) { return number.equals("0") ? "1" : "0"; }

    private <T extends PsiElement> Condition conditionTarget(Class<T> type) {
        for (T node : sorted(PsiTreeUtil.findChildrenOfType(file, type))) {
            PsiElement expression = node instanceof TactIfStatement value ? value.getExpression() : ((TactWhileStatement) node).getExpression();
            PsiElement block = node instanceof TactIfStatement value ? value.getBlock() : node;
            if (expression == null || expression.getText().contains("\n") || block == null) continue;
            int body = firstKnownLine(block, doc.getLineNumber(expression.getTextRange().getEndOffset()) + 1);
            if (body > doc.getLineNumber(expression.getTextOffset())) return new Condition(expression.getTextOffset(), expression.getTextRange().getEndOffset(), expression.getText(), body);
        }
        throw new AssertionError("Corpus requires a known controlled condition body");
    }

    private Header functionHeader() {
        for (var function : sorted(PsiTreeUtil.findChildrenOfType(file, TactFunctionDeclaration.class))) {
            if (function.getBlock() == null) continue;
            int body = firstKnownLine(function.getBlock(), doc.getLineNumber(function.getBlock().getTextOffset()) + 1);
            int declarationStart = function.getTextRange().getStartOffset();
            String prefix = doc.getText().substring(declarationStart, function.getBlock().getTextRange().getStartOffset());
            Matcher match = FUNCTION.matcher(prefix);
            if (body >= 0 && match.find()) return new Header(declarationStart + match.start(1), declarationStart + match.end(1), body);
        }
        throw new AssertionError("Corpus requires a named function with a known body row");
    }

    private <T extends PsiElement> RecoveryPair recoveryPair(Class<T> earlierType) {
        var handlers = sorted(PsiTreeUtil.findChildrenOfType(file, TactMessageFunctionDeclaration.class));
        for (var earlier : sorted(PsiTreeUtil.findChildrenOfType(file, earlierType))) {
            TactBlock earlierBlock = earlier instanceof TactContractInitDeclaration init ? init.getBlock()
                    : ((TactMessageFunctionDeclaration) earlier).getBlock();
            if (!closedDeclaration(earlier, earlierBlock)) continue;
            int earlierBody = firstKnownLine(earlierBlock, doc.getLineNumber(earlierBlock.getTextOffset()) + 1);
            if (earlierBody < 0) continue;
            for (var following : handlers) {
                if (following.getParent() != earlier.getParent()
                        || following.getTextOffset() < earlier.getTextRange().getEndOffset()) continue;
                TactBlock followingBlock = following.getBlock();
                if (!closedDeclaration(following, followingBlock)) continue;
                int followingBody = firstKnownLine(followingBlock, doc.getLineNumber(followingBlock.getTextOffset()) + 1);
                if (followingBody < 0) continue;
                int headerStart = following.getTextOffset(), blockStart = followingBlock.getTextOffset();
                int closingParenthesis = original.substring(headerStart, blockStart).lastIndexOf(')');
                if (closingParenthesis < 0) continue;
                return new RecoveryPair(earlier, earlierBlock, earlierBody, followingBody,
                        headerStart + closingParenthesis + 1, headerStart, blockStart);
            }
        }
        throw new AssertionError("Corpus requires complete earlier and following declarations with known body rows");
    }

    private static boolean closedDeclaration(PsiElement declaration, TactBlock block) {
        return block != null && block.getNode().findChildByType(TactTypes.LBRACE) != null
                && block.getNode().findChildByType(TactTypes.RBRACE) != null
                && PsiTreeUtil.findChildOfType(declaration, PsiErrorElement.class) == null;
    }

    private void malformFollowingHandler(RecoveryPair target) {
        edit(() -> doc.insertString(target.insertion(), " f"));
        PsiDocumentManager.getInstance(getProject()).commitDocument(doc);
        boolean headerError = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class).stream().anyMatch(error ->
                error.getTextRange().getStartOffset() <= target.blockStart() + 2
                        && error.getTextRange().getEndOffset() >= target.headerStart());
        assertTrue("Malformed following handler must create a real parser error in its header", headerError);
    }

    private HandlerBoundary adjacentHandlerBoundary(boolean requireNumber, boolean requireString) {
        var handlers = sorted(PsiTreeUtil.findChildrenOfType(file, TactMessageFunctionDeclaration.class));
        var statements = candidates(false);
        for (int i = 0; i + 1 < handlers.size(); i++) {
            var upper = handlers.get(i);
            var lower = handlers.get(i + 1);
            if (upper.getParent() != lower.getParent() || !closedDeclaration(upper, upper.getBlock())
                    || !closedDeclaration(lower, lower.getBlock())) continue;
            PsiElement next = upper.getNextSibling();
            while (next instanceof PsiWhiteSpace || next instanceof PsiComment) next = next.getNextSibling();
            if (next != lower) continue;
            int upperBody = firstKnownLine(upper.getBlock(), doc.getLineNumber(upper.getBlock().getTextOffset()) + 1);
            int lowerBody = firstKnownLine(lower.getBlock(), doc.getLineNumber(lower.getBlock().getTextOffset()) + 1);
            if (upperBody < 0 || lowerBody < 0) continue;
            int upperParen = original.substring(upper.getTextOffset(), upper.getBlock().getTextOffset()).lastIndexOf(')');
            Matcher parameter = PARAMETER.matcher(original.substring(lower.getTextOffset(), lower.getBlock().getTextOffset()));
            if (upperParen < 0 || !parameter.find()) continue;
            Header header = new Header(lower.getTextOffset() + parameter.start(1),
                    lower.getTextOffset() + parameter.end(1), lowerBody);
            NumericEdit number = null;
            for (var statement : statements) {
                if (statement.owner() != lower) continue;
                Matcher match = NUMBER.matcher(statement.element().getText());
                if (match.find()) {
                    number = new NumericEdit(statement.element().getTextOffset() + match.start(),
                            statement.element().getTextOffset() + match.end(), changedNumber(match.group()), statement.line());
                    break;
                }
            }
            int stringClosingQuote = -1;
            for (var literal : sorted(PsiTreeUtil.findChildrenOfType(upper.getBlock(), TactStringLiteral.class))) {
                String text = literal.getText();
                if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                    stringClosingQuote = literal.getTextRange().getEndOffset() - 1;
                    break;
                }
            }
            if ((requireNumber && number == null) || (requireString && stringClosingQuote < 0)) continue;
            int closingBrace = upper.getBlock().getNode().findChildByType(TactTypes.RBRACE).getStartOffset();
            assertEquals("Upper closure must be its own typed block delimiter", '}', original.charAt(closingBrace));
            return new HandlerBoundary(upperBody, lowerBody, doc.getLineStartOffset(lowerBody),
                    closingBrace, upper.getTextOffset() + upperParen, stringClosingQuote, header, number);
        }
        throw new AssertionError("Corpus requires adjacent complete nonempty handlers with known controlled rows");
    }

    private void malformUpperHandler(HandlerBoundary target, String token) {
        edit(() -> doc.insertString(target.upperClosingParenthesis() + 1, token));
        assertRealParseFailure();
    }

    private void assertRealParseFailure() {
        PsiDocumentManager.getInstance(getProject()).commitDocument(doc);
        assertFalse("The controlled boundary mutation must create a real parser error",
                PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class).isEmpty());
    }

    private int firstKnownLine(PsiElement scope) {
        return firstKnownLine(scope, doc.getLineNumber(scope.getTextRange().getStartOffset()));
    }
    private int firstKnownLine(PsiElement scope, int minimumLine) {
        int first = Math.max(minimumLine, doc.getLineNumber(scope.getTextRange().getStartOffset()));
        int last = doc.getLineNumber(scope.getTextRange().getEndOffset());
        for (int line = first; line <= last; line++) if (context.fingerprint(line) != null && green(line)
                && !row(line).isBlank() && !row(line).strip().equals("{") && !row(line).strip().equals("}")) return line;
        return -1;
    }

    private int knownNonBlankLine() {
        for (int line = 0; line < doc.getLineCount(); line++) if (!row(line).isBlank() && context.fingerprint(line) != null && green(line)) return line;
        throw new AssertionError("Corpus requires a known reviewed row");
    }

    private void edit(Runnable edit) { fresh(); WriteCommandAction.runWriteCommandAction(getProject(), edit); fresh(); }
    private void fresh() {
        PlatformTestUtil.waitWithEventsDispatching("Corpus projection did not finish", () -> service.isProjectionReady(doc), 30);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
    }
    private boolean green(int line) {
        return service.getRanges(doc).stream().anyMatch(r -> r.start() <= doc.getLineStartOffset(line) && r.end() >= doc.getLineEndOffset(line));
    }
    private void notGreen(int line) {
        checked++; if (green(line)) falseGreen++;
        assertFalse("Controlled changed or context-affected row must not be green; opaque row=" + line, green(line));
    }
    private void keep(int line) { assertTrue("Independent unchanged row must retain review; opaque row=" + line, green(line)); retained++; }
    private void isNew(int line) {
        hasStatus(line, FileProjection.Status.UNREVIEWED);
    }
    private void hasStatus(int line, FileProjection.Status expected) {
        notGreen(line);
        var actual = service.projection(doc).rows().stream().filter(r -> r.line() == line).findFirst();
        assertTrue("Controlled row must appear in the projection", actual.isPresent());
        assertEquals("Controlled row classification; opaque row=" + line, expected, actual.orElseThrow().status());
    }
    private String row(int line) { return doc.getText().substring(doc.getLineStartOffset(line), doc.getLineEndOffset(line)); }
    private String indentation(int line) { String value = row(line); return value.substring(0, value.length() - value.stripLeading().length()); }
    private int endOfLine(int line) { int end = doc.getLineEndOffset(line); return end < doc.getTextLength() ? end + 1 : end; }
    private void markLine(int line) { service.mark(doc, doc.getLineStartOffset(line), endOfLine(line)); fresh(); }
    private void reloadHistory() {
        var saved = XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ReviewHistoryStore.StoreState.class);
        store.loadState(saved); service.historyReloaded(); fresh();
    }
    private Map<FileProjection.Status, Integer> statuses() {
        var counts = new EnumMap<FileProjection.Status, Integer>(FileProjection.Status.class);
        for (var row : service.projection(doc).rows()) counts.merge(row.status(), 1, Integer::sum);
        return counts;
    }
    private void report() {
        int known = 0, unknown = 0;
        for (int i = 0; i < context.fingerprints().size(); i++) if (context.fingerprint(i) == null) unknown++; else known++;
        synchronized (COMPLETED) { COMPLETED.merge(category, 1, Integer::sum); }
        System.out.println("CORPUS_AUDIT category=" + category + " file=" + ordinal + " known=" + known + " unknown=" + unknown
                + " statuses=" + statuses() + " checked=" + checked + " falseGreen=" + falseGreen + " retained=" + retained
                + " completed=" + COMPLETED.size());
    }
    private String relativeFile() {
        return Path.of(getProject().getBasePath()).relativize(Path.of(file.getVirtualFile().getPath())).toString().replace('\\', '/');
    }
    private void git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=DoReview Test", "-c", "user.email=doreview@test.invalid",
                "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false", "-c", "core.longpaths=true",
                "-c", "core.hooksPath=" + Path.of(getProject().getBasePath(), ".test-no-hooks")));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(Path.of(getProject().getBasePath()).toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertEquals("Disposable fixture Git operation failed", 0, process.waitFor());
    }
    private void reloadFromDisk() {
        file.getVirtualFile().refresh(false, false); FileDocumentManager.getInstance().reloadFromDisk(doc);
        service.historyReloaded(); fresh();
    }
    private static <T extends PsiElement> List<T> sorted(java.util.Collection<T> elements) {
        return elements.stream().sorted(Comparator.comparingInt(PsiElement::getTextOffset)).toList();
    }
    private record Candidate(int line, int start, int end, PsiElement owner, TactStatement element) {}
    private record Condition(int start, int end, String text, int bodyLine) {}
    private record Header(int start, int end, int bodyLine) {}
    private record RecoveryPair(PsiElement earlier, TactBlock earlierBlock, int earlierBodyLine,
                                int followingBodyLine, int insertion, int headerStart, int blockStart) {}
    private record NumericEdit(int start, int end, String replacement, int line) {}
    private record HandlerBoundary(int upperBodyLine, int lowerBodyLine, int lowerBodyStart,
                                   int upperClosingBrace, int upperClosingParenthesis, int upperStringClosingQuote,
                                   Header lowerHeader, NumericEdit lowerNumber) {}
}
