package dev.doreview.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure immutable review history operations and current-file projection. */
public final class ReviewHistoryEngine {
    private ReviewHistoryEngine() {
    }

    /** Explicitly reviews touched nonblank complete rows; never replaces earlier versions. */
    public static ReviewHistory review(ReviewHistory history, String current,
            List<ReviewedRange> ranges, long createdAt) {
        var snapshot = FileSnapshot.create(current);
        var lines = LineTable.from(snapshot.text());
        var expanded = new ArrayList<ReviewedRange>();
        for (var range : ReviewRanges.normalized(ranges)) {
            if (range.end() > snapshot.text().length()) {
                throw new IllegalArgumentException("Review exceeds current snapshot");
            }
            for (int line = lines.lineAt(range.start()); line <= lines.lineAt(range.end() - 1); line++) {
                if (!lines.text().get(line).isBlank()) expanded.add(lines.range(line));
            }
        }
        if (expanded.isEmpty()) return history;
        long revision = Math.addExact(history.revision(), 1);
        var snapshots = new LinkedHashMap<>(history.snapshots());
        snapshots.putIfAbsent(snapshot.id(), snapshot);
        var evidence = new ArrayList<>(history.evidence());
        evidence.add(new ReviewEvidence(UUID.randomUUID().toString(), snapshot.id(), expanded, revision, createdAt));
        return new ReviewHistory(snapshots, evidence, history.revocations(), revision);
    }

    /** Revokes referenced past proof rows; a later explicit review remains possible. */
    public static ReviewHistory revoke(ReviewHistory history, List<EvidenceRef> refs) {
        if (refs.isEmpty()) return history;
        var grouped = new LinkedHashMap<String, List<ReviewedRange>>();
        var byId = ReviewHistoryIndex.of(history, () -> { }).byId();
        for (var ref : refs) {
            var proof = byId.get(ref.evidenceId());
            if (proof == null || ref.baselineEnd() > history.snapshots().get(proof.snapshotId()).text().length()) {
                throw new IllegalArgumentException("Unknown or out-of-bounds evidence reference");
            }
            grouped.computeIfAbsent(ref.evidenceId(), ignored -> new ArrayList<>()).add(ref.range());
        }
        long revision = Math.addExact(history.revision(), 1);
        var revocations = new ArrayList<>(history.revocations());
        grouped.forEach((id, ranges) -> revocations.add(new Revocation(id, ranges, revision)));
        return new ReviewHistory(history.snapshots(), history.evidence(), revocations, revision);
    }

    public static FileProjection project(ReviewHistory history, String current,
            Map<String, ContextMap> baselineContexts, ContextMap currentContext, ProjectionHints hints) {
        return project(history, current, baselineContexts, currentContext, hints, () -> { });
    }

    public static FileProjection project(ReviewHistory history, String current,
            Map<String, ContextMap> baselineContexts, ContextMap currentContext, ProjectionHints hints,
            Runnable cancellationCheck) {
        return project(history, current, baselineContexts, currentContext, hints,
                ProjectionWorkBudget.defaults(), cancellationCheck);
    }

    /** One quadratic allowance covers all historical snapshots in this projection. */
    public static FileProjection project(ReviewHistory history, String current,
            Map<String, ContextMap> baselineContexts, ContextMap currentContext, ProjectionHints hints,
            ProjectionWorkBudget workBudget, Runnable cancellationCheck) {
        Objects.requireNonNull(workBudget, "workBudget");
        return project(history, baselineContexts, currentContext, hints,
                new ReviewProjectionWorkspace(current, workBudget, cancellationCheck));
    }

    /** Reuses only this operation's prepared text/mappings, including a following checkpoint. */
    public static FileProjection project(ReviewHistory history, Map<String, ContextMap> baselineContexts,
            ContextMap currentContext, ProjectionHints hints, ReviewProjectionWorkspace workspace) {
        workspace.checkCanceled();
        var cancellationCheck = workspace.cancellationCheck();
        var snapshot = workspace.current();
        var currentLines = workspace.currentIndex().lines();
        var context = currentContext == null ? ContextMap.fallback(snapshot.text()) : currentContext;
        var live = hints == null ? ProjectionHints.empty() : hints;
        var result = new ProjectionAccumulator(currentLines);
        var fallbackContexts = new HashMap<String, ContextMap>();
        var unresolvedTexts = new HashMap<ReviewedRange, Set<String>>();
        for (var proofRow : proofRows(history, cancellationCheck)) {
            cancellationCheck.run();
            var baseline = history.snapshots().get(proofRow.proof().snapshotId());
            var baselineContext = baselineContexts == null ? null : baselineContexts.get(baseline.id());
            if (baselineContext == null) {
                baselineContext = baseline.id().equals(snapshot.id()) ? context :
                        fallbackContexts.computeIfAbsent(baseline.id(), ignored -> ContextMap.fallback(baseline.text()));
            }
            boolean exactSnapshot = baseline.id().equals(snapshot.id());
            if (exactSnapshot) {
                result.contribute(proofRow, proofRow.line(), baselineContext, context);
                continue;
            }
            boolean authoritative = live.authoritativeRefs().contains(proofRow.ref());
            var position = live.livePositions().get(proofRow.ref());
            if (authoritative && position != null) {
                if (position.end() <= snapshot.text().length()) {
                    int line = currentLines.lineAt(position.start());
                    if (line >= 0 && position.start() == currentLines.starts()[line]
                            && position.end() == currentLines.end(line)) {
                        result.contribute(proofRow, line, baselineContext, context);
                    }
                }
                continue;
            }
            if (authoritative && live.deletedRefs().contains(proofRow.ref())) {
                var anchor = live.deletedAnchors().get(proofRow.ref());
                result.addDeletion(anchor == null ? Math.min(proofRow.ref().baselineStart(), snapshot.text().length())
                                : anchor.offset(),
                        anchor == null ? proofRow.line() : anchor.ordinal(), proofRow.ref());
                continue;
            }
            if (authoritative) {
                var unresolved = live.unresolvedPositions().get(proofRow.ref());
                if (unresolved != null && unresolved.range().end() <= snapshot.text().length()) {
                    int first = currentLines.lineAt(unresolved.range().start());
                    int last = currentLines.lineAt(unresolved.range().end() - 1);
                    var reason = unresolved.reason();
                    if (reason == FileProjection.Reason.TEXT_CHANGED && unresolved.originalText() == null
                            && unresolvedTexts.computeIfAbsent(unresolved.range(), ignored ->
                            Set.copyOf(currentLines.text().subList(first, last + 1)))
                            .contains(proofRow.lines().text().get(proofRow.line()))) {
                        // Reloads can expand a diagnostic to several rows. Exact candidate
                        // text is ambiguous without its origin, never proof of a text change.
                        reason = FileProjection.Reason.AMBIGUOUS;
                    }
                    var status = reason == FileProjection.Reason.TEXT_CHANGED
                            ? FileProjection.Status.CHANGED : FileProjection.Status.NEEDS_REVIEW;
                    var localChange = new SnapshotReconciler.Change(0, 0, first, last + 1,
                            SnapshotReconciler.ChangeReason.TEXT_CHANGED);
                    result.addLocalChange(unresolved.range(), reason, unresolved.excludedRows(),
                            localChange, status, proofRow);
                } else if (unresolved == null && !live.baselineTexts().containsKey(proofRow.ref())) {
                    // A cold checkpoint can retain a denial without retaining any coordinates.
                    // Text matching may locate a diagnostic, never recover that denied identity.
                    var mapping = workspace.mapping(baseline);
                    int mapped = mapping.currentLine(proofRow.line());
                    if (mapped >= 0) {
                        result.addPossibleDiagnostic(mapped, FileProjection.Status.NEEDS_REVIEW,
                                FileProjection.Reason.AMBIGUOUS, proofRow);
                    } else {
                        var change = mapping.changeForOldLine(proofRow.line());
                        if (change != null && change.reason() == SnapshotReconciler.ChangeReason.TEXT_CHANGED
                                && change.oldEndLine() - change.oldStartLine() == 1
                                && change.currentEndLine() - change.currentStartLine() == 1) {
                            result.addPossibleDiagnostic(change.currentStartLine(), FileProjection.Status.CHANGED,
                                    FileProjection.Reason.TEXT_CHANGED, proofRow);
                        }
                    }
                }
                continue; // Known lost identity may never rematch a newly inserted copy.
            }
            var mapping = workspace.mapping(baseline);
            int mapped = mapping.currentLine(proofRow.line());
            if (mapped >= 0) {
                if (!authoritative) {
                    result.contribute(proofRow, mapped, baselineContext, context);
                }
                // An authoritative missing identity may not reappear on an identical copy.
                continue;
            }
            var change = mapping.changeForOldLine(proofRow.line());
            if (change == null) continue;
            if (change.reason() == SnapshotReconciler.ChangeReason.DELETED) {
                int anchor = change.currentStartLine() < currentLines.text().size()
                        ? currentLines.starts()[change.currentStartLine()] : snapshot.text().length();
                result.addDeletion(anchor, proofRow.line() - change.oldStartLine(), proofRow.ref());
            } else {
                var reason = switch (change.reason()) {
                    case AMBIGUOUS -> FileProjection.Reason.AMBIGUOUS;
                    case DIFF_LIMIT -> FileProjection.Reason.DIFF_LIMIT;
                    default -> FileProjection.Reason.TEXT_CHANGED;
                };
                var status = reason == FileProjection.Reason.TEXT_CHANGED
                        ? FileProjection.Status.CHANGED : FileProjection.Status.NEEDS_REVIEW;
                if (reason == FileProjection.Reason.TEXT_CHANGED
                        && change.oldEndLine() - change.oldStartLine() == change.currentEndLine() - change.currentStartLine()) {
                    // A changed row locator is diagnostic, never proof of unchanged content.
                    int line = change.currentStartLine() + proofRow.line() - change.oldStartLine();
                    result.addRow(line, status, reason, proofRow);
                } else {
                    result.addChange(baseline.id(), change, status, reason, proofRow);
                }
            }
        }
        return result.finish(snapshot.id(), history.revision(), cancellationCheck);
    }

    /** Active baseline row references, shared with local provenance helpers. */
    static List<ProofRow> proofRows(ReviewHistory history) {
        return proofRows(history, () -> { });
    }

    private static List<ProofRow> proofRows(ReviewHistory history, Runnable cancellationCheck) {
        return ReviewHistoryIndex.proofRows(history, cancellationCheck);
    }

    /** Reviewed baselines in immutable recency order; checkpoint-only texts require no PSI analysis. */
    public static List<FileSnapshot> snapshotsByRecency(ReviewHistory history, Runnable cancellationCheck) {
        return ReviewHistoryIndex.of(history, cancellationCheck).snapshotsByRecency();
    }

    record ProofRow(ReviewEvidence proof, EvidenceRef ref, int line, LineTable lines, boolean complete) { }
}
