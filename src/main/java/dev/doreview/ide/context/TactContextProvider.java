package dev.doreview.ide.context;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.tonstudio.tact.lang.TactTypes;
import org.tonstudio.tact.lang.psi.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Tact 0.1.2 PSI adapter. No type or reference APIs are used. */
public final class TactContextProvider extends StructuredPsiContextProvider {
    private static final Set<String> GUARD_CALLS = Set.of("require", "assert", "throw", "throwIf", "throwUnless",
            "nativeThrow", "nativeThrowIf", "nativeThrowUnless");
    @Override public String id() { return "tact:" + version("org.tonstudio.tact", "0.1.2") + ":structure-v4"; }
    @Override public boolean supports(PsiFile file) { return file instanceof TactFile; }

    @Override protected PreparedPsi prepare(PsiFile file, String text) {
        return TactSyntaxRecovery.prepare(file, text);
    }

    @Override protected TextRange recoveryBoundary(PsiElement element) {
        PsiElement parent = element.getParent();
        if (!(parent instanceof TactFile || parent instanceof TactContractType || parent instanceof TactTraitType)) return null;
        PsiElement block;
        if (element instanceof TactContractInitDeclaration node) block = node.getBlock();
        else if (element instanceof TactMessageFunctionDeclaration node) block = node.getBlock();
        else if (element instanceof TactFunctionDeclaration node) block = node.getBlock();
        else if (element instanceof TactAsmFunctionDeclaration node) block = node.getAsmFunctionBody();
        else return null;
        if (block == null) return null;
        var opening = block.getNode().findChildByType(TactTypes.LBRACE);
        var closing = block.getNode().findChildByType(TactTypes.RBRACE);
        return closedDeclaration(element, block, opening == null ? null : opening.getPsi(),
                closing == null ? null : closing.getPsi());
    }

    @Override protected void collect(PsiElement element, List<Scope> scopes) {
        if (element instanceof TactBlock node) {
            List<TactStatement> statements = node.getStatementList();
            int end = statements.isEmpty() ? 0 : statements.getLast().getTextRange().getEndOffset();
            for (int index = 0; index + 1 < statements.size(); index++) {
                TactStatement statement = statements.get(index);
                if (containsGate(statement, TactContextProvider::isGate, TactContextProvider::isDeclaration)) {
                    followingGuard(scopes, node, statement, statements.get(index + 1), end,
                            literalPayloads(statement, TactContextProvider::gatePayloads, TactContextProvider::isDeclaration));
                }
            }
        } else if (element instanceof TactFunctionDeclaration node) {
            add(scopes, node, "function", prefix(node, node.getBlock()));
        } else if (element instanceof TactAsmFunctionDeclaration node) {
            var body = node.getAsmFunctionBody();
            if (recoveryBoundary(node) == null) unknown(scopes, node);
            else {
                // ASM stack/control dependencies are opaque: any body change requires
                // rechecking the complete declaration, while an exact body is known.
                add(scopes, node, "asm-function", prefix(node, body), range(body));
            }
        } else if (element instanceof TactContractInitDeclaration node) {
            add(scopes, node, "init", prefix(node, node.getBlock()));
        } else if (element instanceof TactMessageFunctionDeclaration node) {
            add(scopes, node, "handler", prefix(node, node.getBlock()));
        } else if (element instanceof TactContractDeclaration node) {
            add(scopes, node, "contract", prefix(node, node.getContractType().getLbrace()));
        } else if (element instanceof TactTraitDeclaration node) {
            add(scopes, node, "trait", prefix(node, node.getTraitType().getLbrace()));
        } else if (element instanceof TactStructDeclaration node) {
            add(scopes, node, "struct", prefix(node, node.getStructType().getLbrace()));
        } else if (element instanceof TactMessageDeclaration node) {
            add(scopes, node, "message", prefix(node, node.getMessageType().getLbrace()));
        } else if (element instanceof TactIfStatement node) {
            List<TextRange> tests = new ArrayList<>();
            tests.add(range(node.getExpression()));
            if (node.getBlock() == null) unknown(scopes, node);
            else add(scopes, node.getBlock(), "if:then", tests.toArray(TextRange[]::new));
            // Else-if nodes are nested IfStatements: ancestor tests are included explicitly.
            for (TactElseIfBranch branch : node.getElseIfBranchList()) {
                add(scopes, branch, "if:else-if", tests.toArray(TextRange[]::new));
                TactIfStatement nested = branch.getIfStatement();
                tests.add(nested == null ? null : range(nested.getExpression()));
            }
            if (node.getElseBranch() != null) {
                add(scopes, node.getElseBranch(), "if:else", tests.toArray(TextRange[]::new));
            }
        } else if (element instanceof TactWhileStatement node) {
            add(scopes, node, "while", range(node.getExpression()));
        } else if (element instanceof TactForEachStatement node) {
            add(scopes, node, "foreach", prefix(node, node.getBlock()));
        } else if (element instanceof TactRepeatStatement node) {
            add(scopes, node, "repeat", range(node.getExpression()));
        } else if (element instanceof TactUntilStatement node) {
            add(scopes, node, "do-until", range(node.getExpression()));
        } else if (element instanceof TactTryStatement node) {
            TactCatchClause catcher = node.getCatchClause();
            if (catcher == null) add(scopes, node, "try");
            else add(scopes, node, "try", prefix(catcher, catcher.getBlock()));
        } else if (element instanceof TactCatchClause node) {
            add(scopes, node, "catch", prefix(node, node.getBlock()));
        } else if (element instanceof TactTernaryExpr node) {
            add(scopes, node.getThenBranch(), "ternary:then", range(node.getCondition()));
            add(scopes, node.getElseBranch(), "ternary:else", range(node.getCondition()));
        } else if (element instanceof TactStatement node && node.getBlock() != null) {
            unknown(scopes, node);
        }
    }

    private static boolean isGate(PsiElement element) {
        if (element instanceof TactReturnStatement || element instanceof TactAssertNotNullExpr) return true;
        if (element instanceof TactCallExpr call) {
            PsiElement identifier = call.getIdentifier();
            return identifier != null && GUARD_CALLS.contains(identifier.getText());
        }
        return false;
    }

    private static boolean isDeclaration(PsiElement element) {
        return element instanceof TactFunctionDeclaration || element instanceof TactAsmFunctionDeclaration
                || element instanceof TactMessageFunctionDeclaration;
    }

    private static List<TextRange> gatePayloads(PsiElement element) {
        var result = new ArrayList<TextRange>();
        if (element instanceof TactReturnStatement statement) addLiteral(result, statement.getExpression());
        if (element instanceof TactCallExpr call && call.getExpression() instanceof TactReferenceExpression callee
                && callee.getQualifier() == null && call.getIdentifier() != null) {
            String name = call.getIdentifier().getText();
            if (GUARD_CALLS.contains(name)) {
                int condition = name.equals("require") || name.equals("assert") ? 0
                        : name.endsWith("If") || name.endsWith("Unless") ? 1 : -1;
                List<TactExpression> arguments = call.getArguments();
                for (int i = 0; i < arguments.size(); i++) if (i != condition) addLiteral(result, arguments.get(i));
            }
        }
        return result;
    }

    private static void addLiteral(List<TextRange> result, PsiElement expression) {
        if (expression instanceof TactLiteral || expression instanceof TactStringLiteral) result.add(range(expression));
    }
}
