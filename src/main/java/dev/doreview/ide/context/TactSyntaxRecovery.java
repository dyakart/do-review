package dev.doreview.ide.context;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.tonstudio.tact.lang.TactTypes;
import org.tonstudio.tact.lang.lexer.TactLexer;
import org.tonstudio.tact.lang.psi.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** A bounded retry of the installed parser, never a replacement parser or source edit. */
final class TactSyntaxRecovery {
    private static final int UNKNOWN = -2;
    private static final int FILE = -1;
    private static final int MAX_TOKENS = 100_000;
    private static final int MAX_REPAIRS = 16;
    private static final int MAX_GAP = 64;

    private TactSyntaxRecovery() { }

    static StructuredPsiContextProvider.PreparedPsi prepare(PsiFile original, String text) {
        if (PsiTreeUtil.findChildOfType(original, PsiErrorElement.class) == null) return prepared(original, List.of());
        Lexed lexed = lex(text);
        if (lexed.limited) return prepared(original, List.of(original.getTextRange()));
        List<Repair> repairs = lexed.balanced ? repairs(original, lexed) : List.of();
        PsiFile parsed = original;
        var unknown = new ArrayList<TextRange>();
        if (!repairs.isEmpty()) {
            char[] masked = text.toCharArray();
            for (Repair repair : repairs) {
                for (TextRange token : repair.identifiers) Arrays.fill(masked, token.getStartOffset(), token.getEndOffset(), ' ');
            }
            PsiFile retry = PsiFileFactory.getInstance(original.getProject()).createFileFromText(new String(masked), original);
            if (retry != null) {
                List<TextRange> damaged = validate(retry, repairs, lexed);
                if (damaged != null) { parsed = retry; unknown.addAll(damaged); }
            }
        }
        // Recovery by the language parser alone can expose a peer despite an unclosed '('.
        // Confirm that each declaration really starts directly in its lexical owner frame.
        parsed.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override public void visitElement(PsiElement element) {
                ProgressManager.checkCanceled();
                if (isDeclaration(element)) {
                    int expected = expectedOwner(element);
                    if (expected == UNKNOWN || lexed.ownerAt(element.getTextOffset()) != expected) unknown.add(element.getTextRange());
                }
                if (element instanceof TactAsmFunctionDeclaration asm && asm.getAsmFunctionBody() == null) {
                    // The installed parser rolls back a malformed ASM body, leaving its
                    // tokens outside the declaration. Its header-only range cannot protect them.
                    int at = lexed.indexAt(asm.getTextRange().getEndOffset());
                    while (at >= 0 && at < lexed.tokens.size() && trivia(lexed.tokens.get(at).type)) at++;
                    if (at >= 0 && at < lexed.tokens.size()) {
                        Token token = lexed.tokens.get(at);
                        if (token.type == TactTypes.LBRACE) {
                            unknown.add(new TextRange(asm.getTextOffset(),
                                    lexed.braceEnds.getOrDefault(token.start, text.length())));
                        }
                    }
                }
                super.visitElement(element);
            }
        });
        return prepared(parsed, List.copyOf(unknown));
    }

    private static StructuredPsiContextProvider.PreparedPsi prepared(PsiFile file, List<TextRange> unknown) {
        return new StructuredPsiContextProvider.PreparedPsi(file, unknown);
    }

    private record Token(IElementType type, int start, int end, int owner) { }
    private record Frame(IElementType type, int start) { }
    private record Lexed(List<Token> tokens, Map<Integer, Integer> braceEnds, boolean balanced, boolean limited) {
        int indexAt(int offset) {
            int low = 0, high = tokens.size() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1, start = tokens.get(middle).start;
                if (start < offset) low = middle + 1;
                else if (start > offset) high = middle - 1;
                else return middle;
            }
            return -1;
        }
        int ownerAt(int offset) { int index = indexAt(offset); return index < 0 ? UNKNOWN : tokens.get(index).owner; }
    }

    private static Lexed lex(String text) {
        var lexer = new TactLexer();
        lexer.start(text);
        var tokens = new ArrayList<Token>();
        var braces = new HashMap<Integer, Integer>();
        var stack = new ArrayDeque<Frame>();
        boolean invalid = false, quoted = false;
        while (lexer.getTokenType() != null) {
            ProgressManager.checkCanceled();
            if (tokens.size() == MAX_TOKENS) return new Lexed(tokens, braces, false, true);
            IElementType type = lexer.getTokenType();
            int start = lexer.getTokenStart(), end = lexer.getTokenEnd();
            int owner = invalid || quoted ? UNKNOWN : stack.isEmpty() ? FILE
                    : stack.size() == 1 && stack.peek().type == TactTypes.LBRACE ? stack.peek().start : UNKNOWN;
            tokens.add(new Token(type, start, end, owner));
            if (type == TactTypes.OPEN_QUOTE) quoted = true;
            else if (type == TactTypes.CLOSING_QUOTE) quoted = false;
            else if (type == TokenType.BAD_CHARACTER) invalid = true;
            else if (type == TactTokenTypes.MULTI_LINE_COMMENT
                    && (end - start < 4 || !text.regionMatches(end - 2, "*/", 0, 2))) invalid = true;
            else if (!quoted) {
                if (type == TactTypes.LBRACE || type == TactTypes.LPAREN || type == TactTypes.LBRACK) {
                    stack.push(new Frame(type, start));
                } else if (type == TactTypes.RBRACE || type == TactTypes.RPAREN || type == TactTypes.RBRACK) {
                    IElementType opening = type == TactTypes.RBRACE ? TactTypes.LBRACE
                            : type == TactTypes.RPAREN ? TactTypes.LPAREN : TactTypes.LBRACK;
                    if (stack.isEmpty() || stack.peek().type != opening) invalid = true;
                    else {
                        Frame frame = stack.pop();
                        if (type == TactTypes.RBRACE) braces.put(frame.start, end);
                    }
                }
            }
            lexer.advance();
        }
        return new Lexed(tokens, braces, !invalid && !quoted && stack.isEmpty(), false);
    }

    private record Repair(int start, int parametersEnd, int ownerStart, int ownerOpen,
                          int ownerEnd, int bodyOpen, int bodyEnd, List<TextRange> identifiers) { }

    private static List<Repair> repairs(PsiFile file, Lexed lexed) {
        var result = new ArrayList<Repair>();
        var validHeaders = new IdentityHashMap<PsiElement, Boolean>();
        for (TactMessageFunctionDeclaration handler : PsiTreeUtil.findChildrenOfType(file, TactMessageFunctionDeclaration.class)) {
            ProgressManager.checkCanceled();
            PsiElement owner = handler.getParent();
            PsiElement opening = ownerOpening(owner);
            if (handler.getBlock() != null || opening == null || lexed.ownerAt(handler.getTextOffset()) != opening.getTextOffset()) continue;
            PsiElement parameters = handler.getParameters() != null ? handler.getParameters() : handler.getReceiveStringId();
            PsiElement closing = closingParameters(handler);
            if (parameters == null || closing == null || hasError(parameters)
                    || closing.getTextRange().getEndOffset() != handler.getTextRange().getEndOffset()) continue;
            if (!validHeaders.computeIfAbsent(owner, ignored -> {
                TextRange header = new TextRange(owner.getTextRange().getStartOffset(), opening.getTextRange().getEndOffset());
                return PsiTreeUtil.findChildrenOfType(owner, PsiErrorElement.class).stream()
                        .noneMatch(error -> intersects(header, error.getTextRange()));
            })) continue;
            int end = closing.getTextRange().getEndOffset();
            int closingAt = lexed.indexAt(closing.getTextOffset());
            if (closingAt < 0 || lexed.tokens.get(closingAt).type != TactTypes.RPAREN
                    || lexed.tokens.get(closingAt).end != end) continue;
            int at = lexed.indexAt(end);
            if (at < 0) continue;
            var identifiers = new ArrayList<TextRange>();
            while (at < lexed.tokens.size()) {
                Token token = lexed.tokens.get(at++);
                if (token.end - end > MAX_GAP) break;
                if (trivia(token.type)) continue;
                if (token.type == TactTypes.IDENTIFIER) identifiers.add(new TextRange(token.start, token.end));
                else {
                    Integer ownerEnd = lexed.braceEnds.get(opening.getTextOffset());
                    Integer bodyEnd = lexed.braceEnds.get(token.start);
                    if (token.type == TactTypes.LBRACE && !identifiers.isEmpty() && bodyEnd != null
                            && ownerEnd != null && bodyEnd < ownerEnd) {
                        result.add(new Repair(handler.getTextOffset(), end, owner.getTextOffset(), opening.getTextOffset(),
                                ownerEnd, token.start, bodyEnd, List.copyOf(identifiers)));
                    }
                    break;
                }
            }
            if (result.size() == MAX_REPAIRS) break;
        }
        return result;
    }

    /** Every claimed retry boundary must agree with the original lexer, including its owner. */
    private static List<TextRange> validate(PsiFile retry, List<Repair> repairs, Lexed lexed) {
        var byStart = new HashMap<Integer, TactMessageFunctionDeclaration>();
        for (var handler : PsiTreeUtil.findChildrenOfType(retry, TactMessageFunctionDeclaration.class)) byStart.put(handler.getTextOffset(), handler);
        var unknown = new ArrayList<TextRange>();
        for (Repair repair : repairs) {
            ProgressManager.checkCanceled();
            var handler = byStart.get(repair.start);
            if (handler == null || handler.getBlock() == null || hasError(handler)) return null;
            PsiElement owner = handler.getParent(), opening = ownerOpening(owner), closing = closingParameters(handler);
            PsiElement ownerClose = ownerClosing(owner);
            var block = handler.getBlock();
            var bodyOpen = block.getNode().findChildByType(TactTypes.LBRACE);
            var bodyClose = block.getNode().findChildByType(TactTypes.RBRACE);
            if (opening == null || ownerClose == null || closing == null || bodyOpen == null || bodyClose == null
                    || owner.getTextOffset() != repair.ownerStart || opening.getTextOffset() != repair.ownerOpen
                    || ownerClose.getTextRange().getEndOffset() != repair.ownerEnd
                    || closing.getTextRange().getEndOffset() != repair.parametersEnd
                    || bodyOpen.getStartOffset() != repair.bodyOpen || bodyClose.getTextRange().getEndOffset() != repair.bodyEnd
                    || block.getTextRange().getEndOffset() != repair.bodyEnd || handler.getTextRange().getEndOffset() != repair.bodyEnd
                    || lexed.ownerAt(handler.getTextOffset()) != repair.ownerOpen) return null;
            unknown.add(handler.getTextRange());
        }
        return unknown;
    }

    private static boolean trivia(IElementType type) {
        return TactTokenTypes.INSTANCE.getWHITE_SPACES().contains(type) || TactTokenTypes.INSTANCE.getCOMMENTS().contains(type);
    }

    private static PsiElement closingParameters(TactMessageFunctionDeclaration handler) {
        return handler.getParameters() != null ? handler.getParameters().getRparen()
                : handler.getReceiveStringId() == null ? null : handler.getReceiveStringId().getRparen();
    }

    private static PsiElement ownerOpening(PsiElement owner) {
        return owner instanceof TactContractType contract ? contract.getLbrace()
                : owner instanceof TactTraitType trait ? trait.getLbrace() : null;
    }

    private static PsiElement ownerClosing(PsiElement owner) {
        return owner instanceof TactContractType contract ? contract.getRbrace()
                : owner instanceof TactTraitType trait ? trait.getRbrace() : null;
    }

    private static int expectedOwner(PsiElement declaration) {
        PsiElement parent = declaration.getParent(), opening = ownerOpening(parent);
        return parent instanceof TactFile ? FILE : opening == null ? UNKNOWN : opening.getTextOffset();
    }

    private static boolean isDeclaration(PsiElement element) {
        return element instanceof TactMessageFunctionDeclaration || element instanceof TactContractInitDeclaration
                || element instanceof TactFunctionDeclaration || element instanceof TactAsmFunctionDeclaration;
    }

    private static boolean hasError(PsiElement element) { return PsiTreeUtil.findChildOfType(element, PsiErrorElement.class) != null; }

    private static boolean intersects(TextRange a, TextRange b) {
        return a.getStartOffset() <= b.getEndOffset() && a.getEndOffset() >= b.getStartOffset();
    }
}
