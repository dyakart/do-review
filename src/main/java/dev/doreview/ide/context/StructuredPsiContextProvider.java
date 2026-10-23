package dev.doreview.ide.context;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import dev.doreview.core.ContextMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayDeque;
import java.util.function.Function;
import java.util.function.Predicate;

/** Shares range mapping and lexical fingerprints; language adapters determine actual syntax nodes. */
abstract class StructuredPsiContextProvider implements ContextProvider {
    record Scope(TextRange range, String fingerprint, List<TextRange> headers) {
        Scope(TextRange range, String fingerprint) { this(range, fingerprint, List.of()); }
    }

    protected abstract void collect(PsiElement element, List<Scope> scopes);

    protected record PreparedPsi(PsiFile file, List<TextRange> unknown) { }

    /** Recovery may analyze another PSI copy, but cannot confirm the source it had to repair. */
    protected PreparedPsi prepare(PsiFile file, String text) { return new PreparedPsi(file, List.of()); }

    /** Only adapters can prove an independently closed declaration in their current PSI. */
    protected TextRange recoveryBoundary(PsiElement element) { return null; }

    protected static String version(String pluginId, String fallback) {
        var plugin = PluginManagerCore.getPlugin(PluginId.getId(pluginId));
        return plugin == null ? fallback : plugin.getVersion();
    }

    @Override
    public final ContextMap analyze(PsiFile file, String text) {
        String providerId = id();
        PreparedPsi prepared = prepare(file, text);
        PsiFile parsed = prepared.file;
        var scopes = new ArrayList<Scope>();
        for (TextRange range : prepared.unknown) scopes.add(new Scope(range, null));
        var errors = new ArrayList<TextRange>();
        var boundaries = new ArrayList<TextRange>();
        parsed.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override public void visitElement(PsiElement element) {
                ProgressManager.checkCanceled();
                collect(element, scopes);
                TextRange boundary = recoveryBoundary(element);
                if (boundary != null) boundaries.add(boundary);
                if (element instanceof PsiErrorElement) errors.add(element.getTextRange());
                super.visitElement(element);
            }
        });
        int[] starts = ContextAnalysisManager.lineStarts(text);
        var islands = new ArrayList<RecoveryIsland>();
        for (TextRange boundary : boundaries) {
            ProgressManager.checkCanceled();
            if (errors.stream().anyMatch(error -> touches(boundary, error))) continue;
            int first = lineAt(starts, boundary.getStartOffset());
            int last = lineAt(starts, boundary.getEndOffset() - 1);
            // A line shared with another declaration or malformed tokens cannot be spared.
            if (!text.substring(starts[first], boundary.getStartOffset()).isBlank()) first++;
            int lineEnd = last + 1 < starts.length ? starts[last + 1] : text.length();
            if (!text.substring(boundary.getEndOffset(), lineEnd).isBlank()) last--;
            if (first <= last) islands.add(new RecoveryIsland(boundary, first, last));
        }
        islands.sort(Comparator.comparingInt(RecoveryIsland::first));
        // Keep parser-error expansion separate: it must never clear an adapter's unknown scope
        // or a dependency on a malformed header. Only proven preceding declarations are spared.
        List<Scope> parsedScopes = List.copyOf(scopes);
        var errorRegions = new ArrayList<ErrorRegion>();
        for (TextRange error : errors) {
            ProgressManager.checkCanceled();
            TextRange affected = parsedScopes.stream().map(Scope::range)
                    .filter(range -> range.getStartOffset() <= error.getStartOffset()
                            && range.getEndOffset() >= error.getEndOffset())
                    .min(Comparator.comparingInt(TextRange::getLength)).orElse(parsed.getTextRange());
            errorRegions.add(new ErrorRegion(affected, error.getStartOffset()));
            // Conditions/handler headers can affect a scope outside their own subtree.
            for (Scope scope : parsedScopes) {
                if (scope.headers.stream().anyMatch(header -> touches(header, error))) {
                    scopes.add(new Scope(scope.range, null));
                }
            }
        }
        scopes.sort(Comparator.comparingInt((Scope scope) -> scope.range.getStartOffset())
                .thenComparing(Comparator.comparingInt((Scope scope) -> scope.range.getEndOffset()).reversed()));
        var values = new ArrayList<StringBuilder>(starts.length);
        boolean[] unknown = new boolean[starts.length];
        for (int ignored : starts) values.add(new StringBuilder(providerId));
        for (Scope scope : scopes) {
            ProgressManager.checkCanceled();
            int first = lineAt(starts, Math.min(scope.range.getStartOffset(), text.length()));
            int last = lineAt(starts, Math.min(Math.max(scope.range.getStartOffset(),
                    scope.range.getEndOffset() - 1), text.length()));
            for (int row = first; row <= last; row++) {
                if (scope.fingerprint == null) unknown[row] = true;
                else values.get(row).append('|').append(scope.fingerprint);
            }
        }
        for (ErrorRegion error : errorRegions) {
            int first = lineAt(starts, error.range.getStartOffset());
            int last = lineAt(starts, Math.max(error.range.getStartOffset(), error.range.getEndOffset() - 1));
            int islandAt = 0;
            for (int row = first; row <= last; row++) {
                ProgressManager.checkCanceled();
                while (islandAt < islands.size()) {
                    RecoveryIsland island = islands.get(islandAt);
                    if (island.last >= row && island.range.getEndOffset() < error.errorStart
                            && error.range.contains(island.range)) break;
                    islandAt++;
                }
                if (islandAt == islands.size() || row < islands.get(islandAt).first) unknown[row] = true;
            }
        }
        var result = new ArrayList<String>(starts.length);
        var fingerprints = new HashMap<String, String>();
        for (int row = 0; row < starts.length; row++) {
            result.add(unknown[row] ? null : fingerprints.computeIfAbsent(values.get(row).toString(),
                    ContextAnalysisManager::digest));
        }
        return new ContextMap(providerId, result);
    }

    private record RecoveryIsland(TextRange range, int first, int last) { }
    private record ErrorRegion(TextRange range, int errorStart) { }

    private static boolean touches(TextRange range, TextRange error) {
        // Include zero-width missing-token errors exactly at either boundary.
        return range.getStartOffset() <= error.getEndOffset() && range.getEndOffset() >= error.getStartOffset();
    }

    protected static TextRange closedDeclaration(PsiElement owner, PsiElement body,
                                                  PsiElement opening, PsiElement closing) {
        if (body == null || opening == null || closing == null
                || opening.getParent() != body || closing.getParent() != body
                || opening.getTextRange().getStartOffset() != body.getTextRange().getStartOffset()
                || closing.getTextRange().getEndOffset() != body.getTextRange().getEndOffset()
                || body.getTextRange().getEndOffset() != owner.getTextRange().getEndOffset()) return null;
        return owner.getTextRange();
    }

    private static int lineAt(int[] starts, int offset) {
        int at = Arrays.binarySearch(starts, offset);
        return at >= 0 ? at : -at - 2;
    }

    protected static TextRange prefix(PsiElement owner, PsiElement body) {
        return body == null ? null : new TextRange(owner.getTextRange().getStartOffset(),
                body.getTextRange().getStartOffset());
    }

    protected static TextRange range(PsiElement element) {
        return element == null ? null : element.getTextRange();
    }

    protected static void add(List<Scope> scopes, PsiElement owner, String kind, TextRange... headers) {
        if (owner == null) return;
        addRange(scopes, owner.getContainingFile(), owner.getTextRange(), kind, headers);
    }

    /** Explicit syntactic gates affect following rows, never preceding rows or other blocks. */
    protected static void followingGuard(List<Scope> scopes, PsiElement block, PsiElement statement, int end,
                                         List<TextRange> literalPayloads) {
        int start = statement.getTextRange().getEndOffset();
        if (start >= end) return;
        TextRange header = statement.getTextRange();
        String signature = "preceding-syntactic-guard:" + tokens(block.getContainingFile(), header, literalPayloads);
        // Keep the complete dependency: malformed diagnostics/return payloads are unknown too.
        scopes.add(new Scope(new TextRange(start, end), ContextAnalysisManager.digest(signature), List.of(header)));
    }

    /** Do not infer outer flow gates from the contents of nested declarations. */
    protected static boolean containsGate(PsiElement statement, Predicate<PsiElement> gate,
                                          Predicate<PsiElement> declaration) {
        var pending = new ArrayDeque<PsiElement>();
        pending.push(statement);
        while (!pending.isEmpty()) {
            ProgressManager.checkCanceled();
            PsiElement element = pending.pop();
            if (declaration.test(element)) continue;
            if (gate.test(element)) return true;
            for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                pending.push(child);
            }
        }
        return false;
    }

    /** Only adapters can identify payloads whose primitive literal value does not change the gate. */
    protected static List<TextRange> literalPayloads(PsiElement statement,
                                                    Function<PsiElement, List<TextRange>> payloads,
                                                    Predicate<PsiElement> declaration) {
        var result = new ArrayList<TextRange>();
        var pending = new ArrayDeque<PsiElement>();
        pending.push(statement);
        while (!pending.isEmpty()) {
            ProgressManager.checkCanceled();
            PsiElement element = pending.pop();
            if (declaration.test(element)) continue;
            result.addAll(payloads.apply(element));
            for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                pending.push(child);
            }
        }
        return result;
    }

    private static void addRange(List<Scope> scopes, PsiFile file, TextRange target,
                                 String kind, TextRange... headers) {
        StringBuilder signature = new StringBuilder(kind);
        for (TextRange header : headers) {
            if (header == null) {
                scopes.add(new Scope(target, null));
                return;
            }
            signature.append(':').append(tokens(file, header));
        }
        scopes.add(new Scope(target, ContextAnalysisManager.digest(signature.toString()),
                List.of(headers)));
    }

    protected static void unknown(List<Scope> scopes, PsiElement owner) {
        scopes.add(new Scope(owner.getTextRange(), null));
    }

    private static String tokens(PsiFile file, TextRange range) {
        return tokens(file, range, List.of());
    }

    private static String tokens(PsiFile file, TextRange range, List<TextRange> literalPayloads) {
        StringBuilder result = new StringBuilder();
        List<TextRange> ignored = literalPayloads.stream()
                .filter(payload -> range.contains(payload))
                .distinct().sorted(Comparator.comparingInt(TextRange::getStartOffset)).toList();
        int payloadAt = 0, emittedPayload = -1;
        PsiElement leaf = file.findElementAt(range.getStartOffset());
        while (leaf != null && leaf.getTextRange().getStartOffset() < range.getEndOffset()) {
            TextRange leafRange = leaf.getTextRange();
            while (payloadAt < ignored.size() && ignored.get(payloadAt).getEndOffset() <= leafRange.getStartOffset()) {
                payloadAt++;
            }
            if (payloadAt < ignored.size() && ignored.get(payloadAt).contains(leafRange)) {
                if (emittedPayload != payloadAt) result.append("[literal-payload]");
                emittedPayload = payloadAt;
            } else if (!(leaf instanceof PsiWhiteSpace) && !(leaf instanceof PsiComment)
                    && PsiTreeUtil.getParentOfType(leaf, PsiComment.class, false) == null) {
                String token = leaf.getText();
                result.append(token.length()).append(':').append(token);
            }
            leaf = PsiTreeUtil.nextLeaf(leaf);
        }
        return result.toString();
    }
}
