package dev.doreview.ide.context;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.python.psi.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** PythonCore syntax adapter; no TypeEvalContext, resolution, imports or dataflow. */
public final class PythonContextProvider extends StructuredPsiContextProvider {
    private static final Set<String> GUARD_CALLS = Set.of("require", "throwIf", "throwUnless");
    @Override public String id() { return "python:" + version("PythonCore", "262") + ":structure-v4"; }
    @Override public boolean supports(PsiFile file) { return file instanceof PyFile; }

    @Override protected void collect(PsiElement element, List<Scope> scopes) {
        if (element instanceof PyStatementList node) {
            PyStatement[] statements = node.getStatements();
            int end = statements.length == 0 ? 0 : statements[statements.length - 1].getTextRange().getEndOffset();
            for (int index = 0; index + 1 < statements.length; index++) {
                PyStatement statement = statements[index];
                if (containsGate(statement, PythonContextProvider::isGate, PythonContextProvider::isDeclaration)) {
                    followingGuard(scopes, node, statement, statements[index + 1], end,
                            literalPayloads(statement, PythonContextProvider::gatePayloads, PythonContextProvider::isDeclaration));
                }
            }
        } else if (element instanceof PyFunction node) {
            add(scopes, node, "function", prefix(node, node.getStatementList()));
        } else if (element instanceof PyClass node) {
            add(scopes, node, "class", prefix(node, node.getStatementList()));
        } else if (element instanceof PyLambdaExpression node) {
            add(scopes, node, "lambda", prefix(node, node.getBody()));
        } else if (element instanceof PyIfStatement node) {
            List<TextRange> tests = new ArrayList<>();
            tests.add(prefix(node.getIfPart(), node.getIfPart().getStatementList()));
            add(scopes, node.getIfPart(), "if:then", tests.toArray(TextRange[]::new));
            for (PyIfPart part : node.getElifParts()) {
                tests.add(prefix(part, part.getStatementList()));
                add(scopes, part, "if:elif", tests.toArray(TextRange[]::new));
            }
            if (node.getElsePart() != null) add(scopes, node.getElsePart(), "if:else", tests.toArray(TextRange[]::new));
        } else if (element instanceof PyForStatement node) {
            TextRange header = prefix(node, node.getForPart().getStatementList());
            add(scopes, node, "for", header);
            if (node.getElsePart() != null) add(scopes, node.getElsePart(), "loop:else", header);
        } else if (element instanceof PyWhileStatement node) {
            TextRange header = prefix(node, node.getWhilePart().getStatementList());
            add(scopes, node, "while", header);
            if (node.getElsePart() != null) add(scopes, node.getElsePart(), "loop:else", header);
        } else if (element instanceof PyWithStatement node) {
            add(scopes, node, "with", prefix(node, node.getStatementList()));
        } else if (element instanceof PyTryExceptStatement node) {
            List<TextRange> handlers = new ArrayList<>();
            for (PyExceptPart part : node.getExceptParts()) handlers.add(prefix(part, part.getStatementList()));
            if (node.getElsePart() != null) handlers.add(prefix(node.getElsePart(), node.getElsePart().getStatementList()));
            if (node.getFinallyPart() != null) handlers.add(prefix(node.getFinallyPart(), node.getFinallyPart().getStatementList()));
            add(scopes, node, "try", handlers.toArray(TextRange[]::new));
            add(scopes, node.getTryPart(), "try:body");
            for (PyExceptPart part : node.getExceptParts()) add(scopes, part, "try:except", prefix(part, part.getStatementList()));
            if (node.getElsePart() != null) add(scopes, node.getElsePart(), "try:else");
            if (node.getFinallyPart() != null) add(scopes, node.getFinallyPart(), "try:finally");
        } else if (element instanceof PyMatchStatement node) {
            add(scopes, node, "match", range(node.getSubject()));
            List<TextRange> cases = new ArrayList<>();
            for (PyCaseClause part : node.getCaseClauses()) {
                cases.add(prefix(part, part.getStatementList()));
                add(scopes, part, "match:case", cases.toArray(TextRange[]::new));
            }
        } else if (element instanceof PyConditionalExpression node) {
            add(scopes, node.getTruePart(), "ternary:then", range(node.getCondition()));
            add(scopes, node.getFalsePart(), "ternary:else", range(node.getCondition()));
        } else if (element instanceof PyComprehensionElement node) {
            List<TextRange> clauses = new ArrayList<>();
            StringBuilder kinds = new StringBuilder("comprehension:").append(node.getNode().getElementType());
            for (PyComprehensionComponent component : node.getComponents()) {
                if (component instanceof PyComprehensionForComponent part) {
                    kinds.append(part.isAsync() ? ":async-for" : ":for");
                    clauses.add(range(part.getIteratorVariable()));
                    clauses.add(range(part.getIteratedList()));
                } else if (component instanceof PyComprehensionIfComponent part) {
                    kinds.append(":if");
                    clauses.add(range(part.getTest()));
                } else {
                    unknown(scopes, node);
                    return;
                }
            }
            if (clauses.isEmpty()) unknown(scopes, node);
            else add(scopes, node, kinds.toString(), clauses.toArray(TextRange[]::new));
        } else if (element instanceof PyCompoundStatement) {
            unknown(scopes, element);
        }
    }

    private static boolean isGate(PsiElement element) {
        if (element instanceof PyReturnStatement || element instanceof PyRaiseStatement
                || element instanceof PyAssertStatement || element instanceof PyBreakStatement
                || element instanceof PyContinueStatement) return true;
        if (element instanceof PyCallExpression call && call.getCallee() instanceof PyReferenceExpression callee) {
            String name = callee.getReferencedName();
            return name != null && GUARD_CALLS.contains(name);
        }
        return false;
    }

    private static boolean isDeclaration(PsiElement element) {
        return element instanceof PyFunction || element instanceof PyClass || element instanceof PyLambdaExpression;
    }

    private static List<TextRange> gatePayloads(PsiElement element) {
        var result = new ArrayList<TextRange>();
        if (element instanceof PyReturnStatement statement) addLiteral(result, statement.getExpression());
        if (element instanceof PyAssertStatement statement) {
            PyExpression[] arguments = statement.getArguments();
            for (int i = 1; i < arguments.length; i++) addLiteral(result, arguments[i]);
        }
        if (element instanceof PyCallExpression call && call.getCallee() instanceof PyReferenceExpression callee
                && !callee.isQualified() && callee.getReferencedName() != null
                && GUARD_CALLS.contains(callee.getReferencedName())) {
            String name = callee.getReferencedName();
            int condition = name.equals("require") ? 0 : 1;
            PyExpression[] arguments = call.getArguments();
            for (int i = 0; i < arguments.length; i++) if (i != condition) addLiteral(result, arguments[i]);
        }
        return result;
    }

    private static void addLiteral(List<TextRange> result, PsiElement expression) {
        if (expression instanceof PyNumericLiteralExpression || expression instanceof PyBoolLiteralExpression
                || expression instanceof PyNoneLiteralExpression
                || expression instanceof PyStringLiteralExpression string && !string.isInterpolated()) {
            result.add(range(expression));
        }
    }
}
