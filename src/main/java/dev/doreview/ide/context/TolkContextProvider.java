package dev.doreview.ide.context;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.ton.intellij.tolk.psi.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** TON 4.1.0 Tolk PSI adapter. Header/condition text only, never inferred types. */
public final class TolkContextProvider extends StructuredPsiContextProvider {
    private static final Set<String> GUARD_CALLS = Set.of("require", "throwIf", "throwUnless", "nativeThrow",
            "nativeThrowIf", "nativeThrowUnless");
    @Override public String id() { return "tolk:" + version("org.ton.intellij-ton", "4.1.0") + ":structure-v4"; }
    @Override public boolean supports(PsiFile file) { return file instanceof TolkFile; }

    @Override protected TextRange recoveryBoundary(PsiElement element) {
        if (!(element instanceof TolkFunction node) || !(node.getParent() instanceof TolkFile)) return null;
        TolkFunctionBody body = node.getFunctionBody();
        TolkBlockStatement block = body == null ? null : body.getBlockStatement();
        return block == null ? null : closedDeclaration(node, block, block.getLbrace(), block.getRbrace());
    }

    @Override protected void collect(PsiElement element, List<Scope> scopes) {
        if (element instanceof TolkFunction node) {
            add(scopes, node, "function", prefix(node, node.getFunctionBody()));
        } else if (element instanceof TolkLambdaFunExpression node) {
            add(scopes, node, "lambda", prefix(node, node.getFunctionBody()));
        } else if (element instanceof TolkStruct node) {
            add(scopes, node, "struct", prefix(node, node.getStructBody()));
        } else if (element instanceof TolkIfStatement node) {
            if (node.getBlockStatement() == null) unknown(scopes, node);
            else add(scopes, node.getBlockStatement(), "if:then", range(node.getCondition()));
            if (node.getElseBranch() != null) {
                add(scopes, node.getElseBranch(), "if:else", range(node.getCondition()));
            }
        } else if (element instanceof TolkWhileStatement node) {
            add(scopes, node, "while", range(node.getCondition()));
        } else if (element instanceof TolkRepeatStatement node) {
            add(scopes, node, "repeat", range(node.getExpression()));
        } else if (element instanceof TolkDoStatement node) {
            add(scopes, node, "do-while", range(node.getCondition()));
        } else if (element instanceof TolkTryStatement node) {
            TolkCatch catcher = node.getCatch();
            if (catcher == null) add(scopes, node, "try");
            else add(scopes, node, "try", prefix(catcher, catcher.getBlockStatement()));
        } else if (element instanceof TolkCatch node) {
            add(scopes, node, "catch", prefix(node, node.getBlockStatement()));
        } else if (element instanceof TolkMatchExpression node) {
            add(scopes, node, "match", range(node.getExpression()));
            List<TextRange> cases = new ArrayList<>();
            for (TolkMatchArm arm : node.getMatchArmList()) {
                cases.add(prefix(arm, arm.getMatchBody()));
                add(scopes, arm, "match:arm", cases.toArray(TextRange[]::new));
            }
        } else if (element instanceof TolkTernaryExpression node) {
            add(scopes, node.getThenBranch(), "ternary:then", range(node.getCondition()));
            add(scopes, node.getElseBranch(), "ternary:else", range(node.getCondition()));
        } else if (element instanceof TolkBlockStatement node) {
            List<TolkStatement> statements = node.getStatementList();
            int end = statements.isEmpty() ? 0 : statements.getLast().getTextRange().getEndOffset();
            for (int index = 0; index + 1 < statements.size(); index++) {
                TolkStatement statement = statements.get(index);
                if (containsGate(statement, TolkContextProvider::isGate, TolkContextProvider::isDeclaration)) {
                    followingGuard(scopes, node, statement, statements.get(index + 1), end,
                            literalPayloads(statement, TolkContextProvider::gatePayloads, TolkContextProvider::isDeclaration));
                }
            }
            PsiElement owner = node.getParent();
            if (!(owner instanceof TolkFunctionBody || owner instanceof TolkIfStatement
                    || owner instanceof TolkElseBranch || owner instanceof TolkWhileStatement
                    || owner instanceof TolkRepeatStatement || owner instanceof TolkDoStatement
                    || owner instanceof TolkTryStatement || owner instanceof TolkCatch
                    || owner instanceof TolkMatchBody || owner instanceof TolkBlockStatement)) {
                unknown(scopes, node);
            }
        }
    }

    private static boolean isGate(PsiElement element) {
        if (element instanceof TolkReturnStatement || element instanceof TolkThrowStatement
                || element instanceof TolkAssertStatement || element instanceof TolkNotNullExpression) return true;
        if (element instanceof TolkCallExpression call && call.getExpression() instanceof TolkReferenceExpression callee) {
            PsiElement identifier = callee.getIdentifier();
            return identifier != null && GUARD_CALLS.contains(identifier.getText());
        }
        return false;
    }

    private static boolean isDeclaration(PsiElement element) {
        return element instanceof TolkFunction || element instanceof TolkLambdaFunExpression;
    }

    private static List<TextRange> gatePayloads(PsiElement element) {
        var result = new ArrayList<TextRange>();
        if (element instanceof TolkReturnStatement statement) addLiteral(result, statement.getExpression());
        if (element instanceof TolkThrowStatement statement) {
            for (TolkExpression expression : statement.getExpressionList()) addLiteral(result, expression);
        }
        if (element instanceof TolkAssertStatement statement) {
            List<TolkExpression> arguments = statement.getExpressionList();
            for (int i = 1; i < arguments.size(); i++) addLiteral(result, arguments.get(i));
        }
        if (element instanceof TolkCallExpression call && call.getExpression() instanceof TolkReferenceExpression callee
                && callee.getIdentifier() != null && callee.getText().equals(callee.getIdentifier().getText())) {
            String name = callee.getIdentifier().getText();
            if (GUARD_CALLS.contains(name) && call.getArgumentList() != null) {
                int condition = name.equals("require") ? 0
                        : name.endsWith("If") || name.endsWith("Unless") ? 1 : -1;
                List<TolkArgument> arguments = call.getArgumentList().getArgumentList();
                for (int i = 0; i < arguments.size(); i++) if (i != condition) addLiteral(result, arguments.get(i).getExpression());
            }
        }
        return result;
    }

    private static void addLiteral(List<TextRange> result, PsiElement expression) {
        if (expression instanceof TolkLiteralExpression) result.add(range(expression));
    }
}
