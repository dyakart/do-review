package dev.doreview.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        var byId = new HashMap<String, ReviewEvidence>();
        history.evidence().forEach(proof -> byId.put(proof.id(), proof));
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
        cancellationCheck.run();
        var snapshot = FileSnapshot.create(current);
        var currentLines = LineTable.from(snapshot.text());
        var context = currentContext == null ? ContextMap.fallback(snapshot.text()) : currentContext;
        var live = hints == null ? ProjectionHints.empty() : hints;
        var rowBuilders = new ArrayList<RowBuilder>();
        for (int i = 0; i < currentLines.text().size(); i++) rowBuilders.add(new RowBuilder());
        var maps = new HashMap<String, SnapshotReconciler.Mapping>();
        var unresolvedTexts = new HashMap<ReviewedRange, Set<String>>();
        var deletions = new LinkedHashMap<DeletionKey, LinkedHashSet<EvidenceRef>>();
        var changeGroups = new LinkedHashMap<Object, ChangeGroup>();
        for (var proofRow : proofRows(history, cancellationCheck)) {
            cancellationCheck.run();
            var baseline = history.snapshots().get(proofRow.proof().snapshotId());
            var baselineContext = baselineContexts == null ? null : baselineContexts.get(baseline.id());
            if (baselineContext == null) {
                baselineContext = baseline.id().equals(snapshot.id()) ? context : ContextMap.fallback(baseline.text());
            }
            boolean exactSnapshot = baseline.id().equals(snapshot.id());
            if (exactSnapshot) {
                contribute(rowBuilders, currentLines, proofRow, proofRow.line(), baselineContext, context);
                continue;
            }
            boolean authoritative = live.authoritativeRefs().contains(proofRow.ref());
            var position = live.livePositions().get(proofRow.ref());
            if (authoritative && position != null) {
                if (position.end() <= snapshot.text().length()) {
                    int line = currentLines.lineAt(position.start());
                    if (line >= 0 && position.start() == currentLines.starts()[line]
                            && position.end() == currentLines.end(line)) {
                        contribute(rowBuilders, currentLines, proofRow, line, baselineContext, context);
                    }
                }
                continue;
            }
            if (authoritative && live.deletedRefs().contains(proofRow.ref())) {
                var anchor = live.deletedAnchors().get(proofRow.ref());
                addDeletion(deletions, anchor == null ? Math.min(proofRow.ref().baselineStart(), snapshot.text().length())
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
                    var key = new LocalChangeKey(unresolved.range(), reason, unresolved.excludedRows());
                    var diagnosticReason = reason;
                    changeGroups.computeIfAbsent(key, ignored -> new ChangeGroup(localChange, status,
                            diagnosticReason, unresolved.excludedRows())).add(proofRow);
                } else if (unresolved == null && !live.baselineTexts().containsKey(proofRow.ref())) {
                    // A cold checkpoint can retain a denial without retaining any coordinates.
                    // Text matching may locate a diagnostic, never recover that denied identity.
                    var mapping = maps.computeIfAbsent(baseline.id(), ignored -> {
                        cancellationCheck.run();
                        return SnapshotReconciler.map(baseline.text(), snapshot.text(), workBudget, cancellationCheck);
                    });
                    int mapped = mapping.currentLine(proofRow.line());
                    if (mapped >= 0) {
                        addPossibleDiagnostic(rowBuilders, mapped, FileProjection.Status.NEEDS_REVIEW,
                                FileProjection.Reason.AMBIGUOUS, proofRow);
                    } else {
                        var change = mapping.changeForOldLine(proofRow.line());
                        if (change != null && change.reason() == SnapshotReconciler.ChangeReason.TEXT_CHANGED
                                && change.oldEndLine() - change.oldStartLine() == 1
                                && change.currentEndLine() - change.currentStartLine() == 1) {
                            addPossibleDiagnostic(rowBuilders, change.currentStartLine(), FileProjection.Status.CHANGED,
                                    FileProjection.Reason.TEXT_CHANGED, proofRow);
                        }
                    }
                }
                continue; // Known lost identity may never rematch a newly inserted copy.
            }
            var mapping = maps.computeIfAbsent(baseline.id(), ignored -> {
                cancellationCheck.run();
                return SnapshotReconciler.map(baseline.text(), snapshot.text(), workBudget, cancellationCheck);
            });
            int mapped = mapping.currentLine(proofRow.line());
            if (mapped >= 0) {
                if (!authoritative) {
                    contribute(rowBuilders, currentLines, proofRow, mapped, baselineContext, context);
                }
                // An authoritative missing identity may not reappear on an identical copy.
                continue;
            }
            var change = mapping.changeForOldLine(proofRow.line());
            if (change == null) continue;
            if (change.reason() == SnapshotReconciler.ChangeReason.DELETED) {
                int anchor = change.currentStartLine() < currentLines.text().size()
                        ? currentLines.starts()[change.currentStartLine()] : snapshot.text().length();
                addDeletion(deletions, anchor, proofRow.line() - change.oldStartLine(), proofRow.ref());
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
                    rowBuilders.get(line).add(status, reason, proofRow);
                } else {
                    var key = new ChangeKey(baseline.id(), change);
                    changeGroups.computeIfAbsent(key, ignored -> new ChangeGroup(change, status, reason)).add(proofRow);
                }
            }
        }
        for (var group : changeGroups.values()) {
            var possible = new FileProjection.PossibleEvidence(group.reason, new ArrayList<>(group.refs));
            for (int line = group.change.currentStartLine(); line < group.change.currentEndLine(); line++) {
                if ((line & 255) == 0) cancellationCheck.run();
                if (!covered(group.excludedRows, currentLines.starts()[line], currentLines.end(line))) {
                    rowBuilders.get(line).addGroup(group.status, group.reason, group.sequence, possible);
                }
            }
        }
        var rows = new ArrayList<FileProjection.Row>();
        var reviewed = new ArrayList<ReviewedRange>();
        var yellow = new ArrayList<ReviewedRange>();
        var changed = new ArrayList<ReviewedRange>();
        for (int i = 0; i < rowBuilders.size(); i++) {
            if ((i & 255) == 0) cancellationCheck.run();
            var builder = rowBuilders.get(i);
            var range = currentLines.range(i);
            if (currentLines.text().get(i).isBlank()) {
                rows.add(new FileProjection.Row(i, range.start(), range.end(), FileProjection.Status.UNREVIEWED,
                        FileProjection.Reason.UNREVIEWED, List.of()));
                continue;
            }
            rows.add(new FileProjection.Row(i, range.start(), range.end(), builder.status,
                    builder.reason, builder.evidence()));
            switch (builder.status) {
                case REVIEWED -> reviewed.add(range);
                case NEEDS_REVIEW -> yellow.add(range);
                case CHANGED -> changed.add(range);
                default -> { }
            }
        }
        var deleted = deletions.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<DeletionKey, LinkedHashSet<EvidenceRef>> entry) -> entry.getKey().offset())
                        .thenComparingInt(entry -> entry.getKey().ordinal()))
                .map(entry -> new FileProjection.DeletedRow(entry.getKey().offset(), entry.getKey().ordinal(),
                        new ArrayList<>(entry.getValue()))).toList();
        return new FileProjection(snapshot.id(), history.revision(), rows, ReviewRanges.normalized(reviewed),
                ReviewRanges.normalized(yellow), ReviewRanges.normalized(changed), deleted);
    }

    private static void addPossibleDiagnostic(List<RowBuilder> rows, int line, FileProjection.Status status,
            FileProjection.Reason reason, ProofRow proof) {
        rows.get(line).addGroup(status, reason, proof.proof().sequence(),
                new FileProjection.PossibleEvidence(reason, List.of(proof.ref())));
    }

    private static void contribute(List<RowBuilder> rows, LineTable currentLines, ProofRow proof,
            int currentLine, ContextMap baselineContext, ContextMap currentContext) {
        if (!proof.lines().text().get(proof.line()).equals(currentLines.text().get(currentLine))) {
            rows.get(currentLine).add(FileProjection.Status.CHANGED, FileProjection.Reason.TEXT_CHANGED, proof);
            return;
        }
        String oldFingerprint = baselineContext.fingerprint(proof.line());
        String newFingerprint = currentContext.fingerprint(currentLine);
        if (!baselineContext.provider().equals(currentContext.provider())
                || oldFingerprint == null || newFingerprint == null || !proof.complete()) {
            rows.get(currentLine).add(FileProjection.Status.NEEDS_REVIEW, FileProjection.Reason.UNKNOWN_CONTEXT, proof);
        } else if (!oldFingerprint.equals(newFingerprint)) {
            rows.get(currentLine).add(FileProjection.Status.NEEDS_REVIEW, FileProjection.Reason.CONTEXT_CHANGED, proof);
        } else {
            rows.get(currentLine).add(FileProjection.Status.REVIEWED, FileProjection.Reason.EXACT, proof);
        }
    }

    private static void addDeletion(Map<DeletionKey, LinkedHashSet<EvidenceRef>> deletions,
            int anchor, int ordinal, EvidenceRef ref) {
        deletions.computeIfAbsent(new DeletionKey(anchor, ordinal), ignored -> new LinkedHashSet<>()).add(ref);
    }

    /** Active baseline row references, shared with local provenance helpers. */
    static List<ProofRow> proofRows(ReviewHistory history) {
        return proofRows(history, () -> { });
    }

    private static List<ProofRow> proofRows(ReviewHistory history, Runnable cancellationCheck) {
        var tables = new HashMap<String, LineTable>();
        var byProof = new HashMap<String, List<Revocation>>();
        history.revocations().forEach(revocation -> byProof.computeIfAbsent(revocation.evidenceId(), ignored -> new ArrayList<>()).add(revocation));
        var rows = new ArrayList<ProofRow>();
        for (var proof : history.evidence()) {
            cancellationCheck.run();
            var lines = tables.computeIfAbsent(proof.snapshotId(), id -> LineTable.from(history.snapshots().get(id).text()));
            var revokedRanges = ReviewRanges.normalized(byProof.getOrDefault(proof.id(), List.of()).stream()
                    .flatMap(item -> item.ranges().stream()).toList());
            for (var range : proof.ranges()) {
                for (int line = lines.lineAt(range.start()); line <= lines.lineAt(range.end() - 1); line++) {
                    if ((line & 255) == 0) cancellationCheck.run();
                    // Old journals can contain blank rows; retain the journal, ignore those proofs.
                    if (lines.text().get(line).isBlank()) continue;
                    int start = Math.max(range.start(), lines.starts()[line]);
                    int end = Math.min(range.end(), lines.end(line));
                    var ref = new EvidenceRef(proof.id(), start, end);
                    boolean revoked = overlaps(revokedRanges, start, end);
                    if (!revoked) rows.add(new ProofRow(proof, ref, line, lines,
                            start == lines.starts()[line] && end == lines.end(line)));
                }
            }
        }
        return rows;
    }

    private static boolean overlaps(List<ReviewedRange> ranges, int start, int end) {
        int low = 0;
        int high = ranges.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ranges.get(middle).end() <= start) low = middle + 1;
            else high = middle;
        }
        return low < ranges.size() && ranges.get(low).start() < end;
    }

    private static boolean covered(List<ReviewedRange> ranges, int start, int end) {
        int low = 0;
        int high = ranges.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ranges.get(middle).end() <= start) low = middle + 1;
            else high = middle;
        }
        return low < ranges.size() && ranges.get(low).start() <= start && ranges.get(low).end() >= end;
    }

    record ProofRow(ReviewEvidence proof, EvidenceRef ref, int line, LineTable lines, boolean complete) { }

    private record DeletionKey(int offset, int ordinal) { }
    private record ChangeKey(String snapshotId, SnapshotReconciler.Change change) { }
    private record LocalChangeKey(ReviewedRange range, FileProjection.Reason reason, List<ReviewedRange> excludedRows) { }

    private static final class ChangeGroup {
        private final SnapshotReconciler.Change change;
        private final FileProjection.Status status;
        private final FileProjection.Reason reason;
        private final LinkedHashSet<EvidenceRef> refs = new LinkedHashSet<>();
        private final List<ReviewedRange> excludedRows;
        private long sequence;

        private ChangeGroup(SnapshotReconciler.Change change, FileProjection.Status status, FileProjection.Reason reason) {
            this(change, status, reason, List.of());
        }

        private ChangeGroup(SnapshotReconciler.Change change, FileProjection.Status status,
                FileProjection.Reason reason, List<ReviewedRange> excludedRows) {
            this.change = change;
            this.status = status;
            this.reason = reason;
            this.excludedRows = excludedRows;
        }

        private void add(ProofRow proof) {
            refs.add(proof.ref());
            sequence = Math.max(sequence, proof.proof().sequence());
        }
    }

    private static final class RowBuilder {
        private FileProjection.Status status = FileProjection.Status.UNREVIEWED;
        private FileProjection.Reason reason = FileProjection.Reason.UNREVIEWED;
        private long sequence = -1;
        private final LinkedHashSet<EvidenceRef> refs = new LinkedHashSet<>();
        private final List<FileProjection.PossibleEvidence> groups = new ArrayList<>();

        private void add(FileProjection.Status candidate, FileProjection.Reason why, ProofRow proof) {
            refs.add(proof.ref());
            select(candidate, why, proof.proof().sequence());
        }

        private void addGroup(FileProjection.Status candidate, FileProjection.Reason why, long sequence,
                FileProjection.PossibleEvidence possible) {
            groups.add(possible);
            select(candidate, why, sequence);
        }

        private FileProjection.RowEvidence evidence() {
            return new FileProjection.RowEvidence(new ArrayList<>(refs), groups);
        }

        private void select(FileProjection.Status candidate, FileProjection.Reason why, long candidateSequence) {
            if (rank(candidate) > rank(status) || rank(candidate) == rank(status) && candidateSequence >= sequence) {
                status = candidate;
                reason = why;
                sequence = candidateSequence;
            }
        }

        private static int rank(FileProjection.Status status) {
            return switch (status) {
                case REVIEWED -> 3;
                case NEEDS_REVIEW -> 2;
                case CHANGED -> 1;
                case UNREVIEWED -> 0;
            };
        }
    }
}
