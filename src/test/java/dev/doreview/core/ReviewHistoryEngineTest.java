package dev.doreview.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Independent regressions for immutable evidence, context, provenance and revocation. */
public final class ReviewHistoryEngineTest {
    private static int checks;

    public static void main(String[] args) {
        historyAndRevocation();
        contextAndProofPriority();
        liveIdentityAndUndo();
        deletedIdentityAndProjectionRefresh();
        unknownAndFallback();
        mappingDiagnostics();
        cancellation();
        largeAmbiguousProjection();
        trustClassificationAudit();
        preciseManualClearAudit();
        multiEditIdentityAudit();
        duplicateCopyBoundaryAudit();
        knownOriginBoundaryMatrix();
        compactCheckpointAudit();
        pendingProjectionEditAudit();
        coldAuthorityDiagnostics();
        bulkReloadRestoresExactCandidateWithoutChangedDiagnostic();
        sharedProjectionWorkBudget();
        System.out.println("ReviewHistoryEngineTest: " + checks + " checks passed");
    }

    private static void historyAndRevocation() {
        String text = "A\nB\nC\n";
        var initial = ReviewHistory.empty();
        var reviewed = ReviewHistoryEngine.review(initial, text, List.of(r(0, 6)), 10);
        equal(0, initial.evidence().size(), "input history stays immutable");
        equal(1, reviewed.evidence().size(), "explicit mark creates evidence");
        equal(1, reviewed.snapshots().size(), "one baseline snapshot");
        var projected = project(reviewed, text);
        equal(List.of(r(0, 6)), projected.reviewedRanges(), "exact snapshot reviewed");
        var middle = projected.rows().get(1).evidenceRefs();
        var revoked = ReviewHistoryEngine.revoke(reviewed, middle);
        equal(List.of(r(0, 2), r(4, 6)), project(revoked, text).reviewedRanges(), "per-row revoke");
        equal(List.of(r(0, 6)), project(reviewed, text).reviewedRanges(), "old history remains unchanged");
        var rechecked = ReviewHistoryEngine.review(revoked, text, List.of(r(2, 4)), 20);
        equal(List.of(r(0, 6)), project(rechecked, text).reviewedRanges(), "new evidence after revoke can green");
        equal(1, rechecked.snapshots().size(), "snapshots deduplicated");
        equal(2, rechecked.evidence().size(), "proofs are append-only");
        var twice = ReviewHistoryEngine.review(reviewed, text, List.of(r(0, 6)), 30);
        var twiceProjection = project(twice, text);
        equal(2, twiceProjection.rows().get(1).evidenceRefs().size(), "all row proofs retained for manual clear");
        equal(List.of(r(0, 2), r(4, 6)), project(ReviewHistoryEngine.revoke(twice,
                twiceProjection.rows().get(1).evidenceRefs()), text).reviewedRanges(), "clear withdraws every version's applicable proof");
        var normalized = FileSnapshot.create("A\r\nB\rC\n");
        equal("A\nB\nC\n", normalized.text(), "snapshot LF normalization");
        equal(normalized.id(), FileSnapshot.create(normalized.text()).id(), "stable content id");
        boolean immutable = false;
        try { reviewed.evidence().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        equal(true, immutable, "history lists immutable");
    }

    private static void contextAndProofPriority() {
        String a = "if A:\n  same()\nend\n";
        String b = "if B:\n  same()\nend\n";
        var body = r(6, 15);
        var historyA = ReviewHistoryEngine.review(ReviewHistory.empty(), a, List.of(body), 1);
        var contexts = new HashMap<String, ContextMap>();
        contexts.put(FileSnapshot.create(a).id(), context(a, "if:A"));
        contexts.put(FileSnapshot.create(b).id(), context(b, "if:B"));
        var yellow = ReviewHistoryEngine.project(historyA, b, contexts, context(b, "if:B"), null);
        equal(FileProjection.Status.NEEDS_REVIEW, yellow.rows().get(1).status(), "same text changed enclosing context is yellow");
        equal(FileProjection.Reason.CONTEXT_CHANGED, yellow.rows().get(1).reason(), "yellow explains context");
        var historyB = ReviewHistoryEngine.review(historyA, b, List.of(body), 2);
        var green = ReviewHistoryEngine.project(historyB, b, contexts, context(b, "if:B"), null);
        equal(FileProjection.Status.REVIEWED, green.rows().get(1).status(), "new explicit proof beats old yellow");
        equal(2, green.rows().get(1).evidenceRefs().size(), "green keeps old yellow provenance for clear");
        equal(FileProjection.Status.REVIEWED,
                ReviewHistoryEngine.project(historyB, a, contexts, context(a, "if:A"), null).rows().get(1).status(),
                "return A restores A proof and preserves B proof");
        var cleared = ReviewHistoryEngine.revoke(historyB, green.rows().get(1).evidenceRefs());
        equal(FileProjection.Status.UNREVIEWED,
                ReviewHistoryEngine.project(cleared, a, contexts, context(a, "if:A"), null).rows().get(1).status(),
                "manual clear prevents A restoration");
        equal(2, historyB.evidence().size(), "projection never erases versions");
    }

    private static void liveIdentityAndUndo() {
        String a = "same\nsame\nlast\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), a, List.of(r(0, a.length())), 1);
        var initial = project(history, a);
        var hints = ProjectionHints.fromProjection(history, initial);
        String inserted = "same\nsame\nsame\nlast\n";
        hints = hints.applyEdit(a, inserted, 5, 0, 5);
        var result = project(history, inserted, hints);
        equal(FileProjection.Status.REVIEWED, result.rows().get(0).status(), "original duplicate before insertion stays green");
        equal(FileProjection.Status.UNREVIEWED, result.rows().get(1).status(), "new duplicate never inherits review");
        equal(FileProjection.Status.REVIEWED, result.rows().get(2).status(), "original duplicate after insertion stays green");

        String changed = "same\nXame\nlast\n";
        hints = ProjectionHints.fromProjection(history, initial).applyEdit(a, changed, 5, 1, 1);
        equal(FileProjection.Status.CHANGED, project(history, changed, hints).rows().get(1).status(), "changed row is red with provenance");
        String changedAgain = "same\nXame\nLAST\n";
        hints = hints.applyEdit(changed, changedAgain, 10, 4, 4);
        String partialUndo = "same\nsame\nLAST\n";
        hints = hints.applyEdit(changedAgain, partialUndo, 5, 1, 1);
        equal(FileProjection.Status.REVIEWED, project(history, partialUndo, hints).rows().get(1).status(),
                "partial Undo restores same-row proof although whole file differs");
        equal(FileProjection.Status.CHANGED, project(history, partialUndo, hints).rows().get(2).status(), "other changed row stays red");
        equal(List.of(r(0, a.length())), project(history, a, hints).reviewedRanges(), "whole snapshot return restores all unrevoked evidence");
    }

    private static void unknownAndFallback() {
        String a = "A\nB\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), a, List.of(r(0, a.length())), 1);
        var unknown = new ContextMap("typed:v1", new ArrayList<>(Collections.nCopies(3, null)));
        equal(FileProjection.Status.NEEDS_REVIEW,
                ReviewHistoryEngine.project(history, a, Map.of(FileSnapshot.create(a).id(), unknown), unknown, null)
                        .rows().get(0).status(), "exact snapshot does not bypass unknown structure");
        equal(List.of(r(0, a.length())), ReviewHistoryEngine.project(history, a, Map.of(),
                ContextMap.fallback(a), null).reviewedRanges(), "fallback current exact file is known context");
        String changed = "A\nX\n";
        var fallback = ReviewHistoryEngine.project(history, changed,
                Map.of(FileSnapshot.create(a).id(), ContextMap.fallback(a)), ContextMap.fallback(changed), null);
        equal(FileProjection.Status.NEEDS_REVIEW, fallback.rows().get(0).status(), "fallback changes all old unchanged rows to yellow");
        equal(FileProjection.Status.CHANGED, fallback.rows().get(1).status(), "fallback text change remains changed");
        equal(List.of(r(0, a.length())), project(history, a).reviewedRanges(), "typed context input can recover after parse failure");
    }

    private static void deletedIdentityAndProjectionRefresh() {
        String a = "top\nsame\nlast\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), a, List.of(r(0, a.length())), 1);
        var hints = ProjectionHints.fromProjection(history, project(history, a));
        String b = "TOP\nsame\nlast\n";
        hints = hints.applyEdit(a, b, 0, 3, 3);
        String deleted = "TOP\nlast\n";
        hints = hints.applyEdit(b, deleted, 4, 5, 0);
        var deletion = project(history, deleted, hints);
        equal(1, deletion.deletedCount(), "known deletion remains a deletion even global diff groups it with changed row");
        hints = hints.mergeResolved(ProjectionHints.fromProjection(history, deletion));
        var restoredHints = hints.applyEdit(deleted, b, 4, 0, 5);
        equal(FileProjection.Status.REVIEWED, project(history, b, restoredHints).rows().get(1).status(),
                "Undo deletion restores exact row at tombstone while another row remains changed");
        String prefixed = "new\n" + deleted;
        var movedAnchor = hints.applyEdit(deleted, prefixed, 0, 0, 4);
        String restoredAfterPrefix = "new\nTOP\nsame\nlast\n";
        movedAnchor = movedAnchor.applyEdit(prefixed, restoredAfterPrefix, 8, 0, 5);
        equal(FileProjection.Status.REVIEWED, project(history, restoredAfterPrefix, movedAnchor).rows().get(2).status(),
                "preceding insertion shifts tombstone and preserves restoration identity");
        String copyElsewhere = deleted + "same\n";
        var copiedHints = hints.applyEdit(deleted, copyElsewhere, deleted.length(), 0, 5);
        var copied = project(history, copyElsewhere, copiedHints);
        equal(FileProjection.Status.UNREVIEWED, copied.rows().get(2).status(), "copy at another location does not revive deleted proof");
        equal(1, copied.deletedCount(), "copy elsewhere does not erase original deletion history");

        String splitBase = "head\nAB\nlast\n";
        var splitHistory = ReviewHistoryEngine.review(ReviewHistory.empty(), splitBase, List.of(r(5, 8)), 1);
        var splitHints = ProjectionHints.fromProjection(splitHistory, project(splitHistory, splitBase));
        String split = "head\nA\nB\nlast\n";
        splitHints = splitHints.applyEdit(splitBase, split, 6, 0, 1);
        var sourceRef = splitHistory.evidence().get(0);
        var ref = new EvidenceRef(sourceRef.id(), 5, 8);
        equal(true, splitHints.authoritativeRefs().contains(ref), "split keeps authority for missing old identity");
        equal(false, splitHints.livePositions().containsKey(ref), "split drops exact row locator");
        // A fast filtered projection may contain no refs from this older baseline.
        var emptyProjection = project(ReviewHistory.empty(), split);
        splitHints = splitHints.mergeResolved(ProjectionHints.fromProjection(splitHistory, emptyProjection));
        equal(true, splitHints.authoritativeRefs().contains(ref), "filtered refresh cannot erase missing authoritative identity");
        String splitWithCopy = split + "AB\n";
        splitHints = splitHints.applyEdit(split, splitWithCopy, split.length(), 0, 3);
        equal(FileProjection.Status.UNREVIEWED, project(splitHistory, splitWithCopy, splitHints).rows().get(4).status(),
                "copy after split and filtered refresh cannot inherit old proof");
        String splitOtherChanged = "HEAD\nA\nB\nlast\n";
        var undoSplit = ProjectionHints.fromProjection(splitHistory, project(splitHistory, splitBase))
                .applyEdit(splitBase, split, 6, 0, 1).applyEdit(split, splitOtherChanged, 0, 4, 4);
        undoSplit = undoSplit.mergeResolved(ProjectionHints.fromProjection(splitHistory,
                project(splitHistory, splitOtherChanged, undoSplit)));
        String joinedBack = "HEAD\nAB\nlast\n";
        undoSplit = undoSplit.applyEdit(splitOtherChanged, joinedBack, 6, 1, 0);
        equal(FileProjection.Status.REVIEWED, project(splitHistory, joinedBack, undoSplit).rows().get(1).status(),
                "partial Undo split restores exact original hunk with other file changes");
        String copiedInside = "head\nA\nAB\nB\nlast\n";
        var insideCopyHints = ProjectionHints.fromProjection(splitHistory, project(splitHistory, splitBase))
                .applyEdit(splitBase, split, 6, 0, 1).applyEdit(split, copiedInside, 7, 0, 3);
        equal(FileProjection.Status.UNREVIEWED, project(splitHistory, copiedInside, insideCopyHints).rows().get(2).status(),
                "inserting exact copied row inside unrepaired split hunk stays new");

        String multiple = "head\nA\nBBB\nlast\n";
        var multipleHistory = ReviewHistoryEngine.review(ReviewHistory.empty(), multiple, List.of(r(5, 11)), 1);
        var multipleHints = ProjectionHints.fromProjection(multipleHistory, project(multipleHistory, multiple));
        String removed = "head\nlast\n";
        multipleHints = multipleHints.applyEdit(multiple, removed, 5, 6, 0);
        equal(2, project(multipleHistory, removed, multipleHints).deletedCount(), "multi-row deletion counts logical rows once");
        multipleHints = multipleHints.mergeResolved(ProjectionHints.fromProjection(multipleHistory,
                project(multipleHistory, removed, multipleHints)));
        multipleHints = multipleHints.applyEdit(removed, multiple, 5, 0, 6);
        equal(List.of(r(5, 11)), project(multipleHistory, multiple, multipleHints).reviewedRanges(), "multi-row tombstones restore exact baseline rows");
    }

    private static void mappingDiagnostics() {
        var changed = SnapshotReconciler.map("A\nB\nC\n", "A\nX\nC\n");
        equal(SnapshotReconciler.ChangeReason.TEXT_CHANGED, changed.changeForOldLine(1).reason(), "mapping classifies row modification");
        equal(SnapshotReconciler.ChangeReason.AMBIGUOUS,
                SnapshotReconciler.map("same\nsame\n", "same\nsame\nsame\n").changeForOldLine(0).reason(), "mapping classifies duplicate ambiguity");
        equal(SnapshotReconciler.ChangeReason.DIFF_LIMIT,
                SnapshotReconciler.map("same\n".repeat(1_100), "other\n".repeat(1_100)).changeForOldLine(0).reason(), "mapping exposes bounded gap limit");
        String text = "A\nB\nC\n";
        var first = ReviewHistoryEngine.review(ReviewHistory.empty(), text, List.of(r(2, 4)), 1);
        var twice = ReviewHistoryEngine.review(first, text, List.of(r(2, 4)), 2);
        var deleted = project(twice, "A\nC\n");
        equal(1, deleted.deletedCount(), "duplicate proofs do not inflate deleted row count");
        equal(2, deleted.deletedRefs().size(), "all deleted proof refs remain available for native diff/clear");
    }

    private static void cancellation() {
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), "A\n", List.of(r(0, 2)), 1);
        boolean cancelled = false;
        try {
            ReviewHistoryEngine.project(history, "A\n", Map.of(), context("A\n", "global"), null,
                    () -> { throw new IllegalStateException("cancelled"); });
        } catch (IllegalStateException expected) { cancelled = true; }
        equal(true, cancelled, "projection supports caller cancellation without IDE imports");
    }

    private static void largeAmbiguousProjection() {
        String a = "same\n".repeat(10_000);
        String b = "same\n" + a;
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), a, List.of(r(0, a.length())), 1);
        var projection = project(history, b);
        equal(FileProjection.Reason.AMBIGUOUS, projection.rows().get(0).reason(), "large duplicate insertion retains explicit uncertainty");
        equal(10_000, projection.rows().get(0).evidenceRefs().size(), "shared hunk provenance keeps all source rows");
        var hints = ProjectionHints.fromProjection(history, projection);
        equal(10_000, hints.authoritativeRefs().size(), "large ambiguous provenance captured once, not per destination row");
        equal(0, hints.livePositions().size(), "ambiguous rows never become invented exact identities");
        equal(true, projection.rows().getFirst().evidence().possibleGroups().getFirst()
                        == projection.rows().getLast().evidence().possibleGroups().getFirst(),
                "large possible-origin group remains shared across rows");
    }

    private static void trustClassificationAudit() {
        var failures = new ArrayList<String>();
        String a = "head\nold();\ntail\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), a, List.of(r(5, 12)), 1);
        String replaced = "head\nalpha();\nbeta();\ngamma();\ntail\n";
        var unknown = project(history, replaced);
        if (unknown.rows().get(1).reason() != FileProjection.Reason.AMBIGUOUS) {
            failures.add("one-to-many unanchored replacement pretends certain row identity");
        }
        String old = "head\nbefore_after();\ntail\n";
        var checked = ReviewHistoryEngine.review(ReviewHistory.empty(), old, List.of(r(0, old.length())), 1);
        int offset = old.indexOf('_') + 1;
        String insertion = "\ninserted();\n";
        String current = old.substring(0, offset) + insertion + old.substring(offset);
        var hints = ProjectionHints.fromProjection(checked, project(checked, old))
                .applyEdit(old, current, offset, 0, insertion.length());
        var live = project(checked, current, hints);
        if (live.rows().get(2).status() != FileProjection.Status.UNREVIEWED) {
            failures.add("complete newly inserted middle row inherits old-row diagnostic/provenance");
        }
        String copy = "\nbefore_after();\n";
        String copied = old.substring(0, offset) + copy + old.substring(offset);
        var copyHints = ProjectionHints.fromProjection(checked, project(checked, old))
                .applyEdit(old, copied, offset, 0, copy.length());
        var copiedProjection = project(checked, copied, copyHints);
        if (copiedProjection.rows().get(2).status() != FileProjection.Status.UNREVIEWED) {
            failures.add("newly inserted exact copy falsely inherits review from split original");
        }
        equal(java.util.List.of(r(0, 5), r(copied.indexOf("tail\n"), copied.length())),
                LineReviewTracker.applyEdit(old, copied, java.util.List.of(r(0, old.length())), offset, 0, copy.length()),
                "raw live tracker also refuses inserted exact copy");
        equal(FileProjection.Status.REVIEWED, live.rows().get(0).status(), "known unchanged prefix retained");
        equal(FileProjection.Status.REVIEWED, live.rows().get(4).status(), "known unchanged suffix retained");
        if (!failures.isEmpty()) throw new AssertionError("Trust audit: " + failures);
        checks += 3;
    }

    private static void preciseManualClearAudit() {
        String a = "head\nleft();\nright();\ntail\n";
        String variant = "HEADER\nleft();\nright();\ntail\n";
        String b = "head\nfirst();\nsecond();\nthird();\ntail\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), a,
                List.of(r(a.indexOf("left"), a.indexOf("tail"))), 1);
        history = ReviewHistoryEngine.review(history, variant,
                List.of(r(variant.indexOf("right"), variant.indexOf("tail"))), 2);
        history = ReviewHistoryEngine.review(history, b,
                List.of(r(b.indexOf("second"), b.indexOf("third"))), 3);
        var projection = project(history, b);
        var selected = projection.rows().get(2);
        equal(FileProjection.Status.REVIEWED, selected.status(), "new green proof dominates several old ambiguous versions");
        equal(4, selected.evidenceRefs().size(), "possible historical origins remain available for inspection");
        equal(1, selected.revocableRefs().size(), "only definite selected-row proof may be revoked");
        var copied = copyProjectionValues(projection);
        equal(projection, copied, "projection provenance survives a value round trip");
        equal(selected.revocableRefs(), copied.rows().get(2).revocableRefs(),
                "copied row never promotes possible origins into precise clear targets");
        equal(ProjectionHints.fromProjection(history, projection), ProjectionHints.fromProjection(history, copied),
                "copied possible groups retain the same provenance and hunk bounds");
        var directInput = new ArrayList<>(selected.evidence().direct());
        var possibleInput = new ArrayList<>(selected.evidence().possibleGroups());
        var immutableEvidence = new FileProjection.RowEvidence(directInput, possibleInput);
        directInput.clear();
        possibleInput.clear();
        equal(selected.evidence(), immutableEvidence, "typed provenance defensively copies input lists");
        var cleared = ReviewHistoryEngine.revoke(history, selected.revocableRefs());
        equal(List.of(r(a.indexOf("left"), a.indexOf("tail"))), project(cleared, a).reviewedRanges(),
                "clearing green B cannot erase unrelated ambiguous A neighbors");
        equal(FileProjection.Status.NEEDS_REVIEW, project(cleared, b).rows().get(2).status(),
                "clear removes B proof and leaves honest ambiguous historical state");
        equal(0, project(cleared, b).rows().get(2).revocableRefs().size(),
                "ambiguous possible origins cannot become row-clear targets");
        var rowA = project(cleared, a).rows().get(1);
        var clearKnownA = ReviewHistoryEngine.revoke(cleared, rowA.revocableRefs());
        equal(FileProjection.Status.UNREVIEWED, project(clearKnownA, a).rows().get(1).status(),
                "definite A row can be cleared precisely");
        equal(FileProjection.Status.REVIEWED, project(clearKnownA, a).rows().get(2).status(),
                "precise A clear preserves adjacent checked row");
    }

    private static void duplicateCopyBoundaryAudit() {
        String a = "head\nsame();\ntail\n";
        int offset = a.indexOf("same();") + "same();".length();
        String inserted = "\nsame();\n";
        String b = a.substring(0, offset) + inserted + a.substring(offset);
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), a, List.of(r(0, a.length())), 1);
        var hints = ProjectionHints.fromProjection(history, project(history, a)).applyEdit(a, b, offset, 0, inserted.length());
        var result = project(history, b, hints);
        equal(FileProjection.Status.REVIEWED, result.rows().get(1).status(),
                "known original complete row before inserted copy retains review");
        equal(FileProjection.Status.UNREVIEWED, result.rows().get(2).status(),
                "identical inserted copy stays new even when original row retained");
    }

    private static void knownOriginBoundaryMatrix() {
        String a = "head\nalpha_beta();\ntail\n";
        int bodyStart = a.indexOf("alpha");
        int bodyEnd = a.indexOf("tail");
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), a, List.of(r(0, a.length())), 1);
        for (int offset = bodyStart + 1; offset < bodyEnd; offset++) {
            for (String inserted : List.of("\nnew_row();\n", "\nalpha_beta();\n", "\nalpha_beta();\nalpha_beta();\n")) {
                String current = a.substring(0, offset) + inserted + a.substring(offset);
                var hints = ProjectionHints.fromProjection(history, project(history, a))
                        .applyEdit(a, current, offset, 0, inserted.length());
                var projection = project(history, current, hints);
                var cold = project(history, current, hints.compactForCheckpoint(current));
                equal(projection.reviewedRanges(), cold.reviewedRanges(), "compact checkpoint preserves matrix green identity");
                equal(projection.yellowRanges(), cold.yellowRanges(), "compact checkpoint preserves matrix uncertainty");
                equal(projection.changedRanges(), cold.changedRanges(), "compact checkpoint preserves matrix changed rows");
                var newRows = new ArrayList<Integer>();
                for (var row : projection.rows()) {
                    if (row.start() >= offset && row.end() <= offset + inserted.length()) {
                        newRows.add(row.line());
                        equal(FileProjection.Status.UNREVIEWED, row.status(), "known inserted row never green or historical across offset matrix");
                        equal(0, row.evidenceRefs().size(), "known inserted row has no inherited source proof");
                    }
                }
                equal(FileProjection.Status.REVIEWED, projection.rows().get(0).status(), "matrix preserves exact prefix identity");
                equal(FileProjection.Status.REVIEWED, projection.rows().get(projection.rows().size() - 1).status(),
                        "matrix preserves exact suffix identity");
                hints = hints.mergeResolved(ProjectionHints.fromProjection(history, projection));
                int tail = current.indexOf("tail\n");
                String modifiedElsewhere = current.substring(0, tail) + "TAIL\n";
                hints = hints.applyEdit(current, modifiedElsewhere, tail, 4, 4);
                projection = project(history, modifiedElsewhere, hints);
                for (int row : newRows) {
                    equal(FileProjection.Status.UNREVIEWED, projection.rows().get(row).status(),
                            "worker refresh plus unrelated edit cannot rematch inserted copy");
                }
                var checkpoint = hints.compactForCheckpoint(modifiedElsewhere);
                var reloaded = project(history, modifiedElsewhere, checkpoint);
                var settledReload = project(history, modifiedElsewhere, hints.compactForCheckpoint(modifiedElsewhere, history));
                for (int row : newRows) {
                    equal(FileProjection.Status.UNREVIEWED, reloaded.rows().get(row).status(),
                            "cold projection with compact checkpoint cannot green known inserted copy");
                    equal(FileProjection.Status.UNREVIEWED, settledReload.rows().get(row).status(),
                            "history-aware settled compaction cannot green known inserted copy");
                }
            }
        }
        String joined = "head\nab\nb\ntail\n";
        var joinedHistory = ReviewHistoryEngine.review(ReviewHistory.empty(), joined, List.of(r(0, joined.length())), 1);
        int offset = joined.indexOf("ab\n") + 1;
        String current = joined.substring(0, offset) + joined.substring(offset + 2);
        var joinedHints = ProjectionHints.fromProjection(joinedHistory, project(joinedHistory, joined))
                .applyEdit(joined, current, offset, 2, 0);
        equal(FileProjection.Status.NEEDS_REVIEW, project(joinedHistory, current, joinedHints).rows().get(1).status(),
                "joined row cannot inherit exact text proof when retained characters came from two original rows");
        equal(List.of(r(0, 5), r(current.indexOf("tail"), current.length())),
                LineReviewTracker.applyEdit(joined, current, List.of(r(0, joined.length())), offset, 2, 0),
                "raw tracker likewise invalidates mixed-origin exact joined row");
    }

    private static void compactCheckpointAudit() {
        String text = "head\nvalue();\ntail\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), text, List.of(r(0, text.length())), 1);
        var initial = ProjectionHints.fromProjection(history, project(history, text));
        equal(0, initial.compactForCheckpoint(text).authoritativeRefs().size(), "unchanged unique rows need no persistent hint");
        int offset = text.indexOf("value();") + 3;
        String inserted = "\nvalue();\n";
        String copied = text.substring(0, offset) + inserted + text.substring(offset);
        var live = initial.applyEdit(text, copied, offset, 0, inserted.length());
        var checkpoint = live.compactForCheckpoint(copied);
        equal(1, checkpoint.authoritativeRefs().size(), "checkpoint retains only hard split identity");
        equal(1, checkpoint.unresolvedPositions().size(), "checkpoint preserves new-row exclusions and inverse hunk metadata");
        equal(FileProjection.Status.UNREVIEWED, project(history, copied, checkpoint).rows().get(2).status(),
                "checkpoint prevents cold copy inheritance");
        String duplicate = "same();\nsame();\n";
        var duplicateHistory = ReviewHistoryEngine.review(ReviewHistory.empty(), duplicate, List.of(r(0, duplicate.length())), 1);
        var duplicateHints = ProjectionHints.fromProjection(duplicateHistory, project(duplicateHistory, duplicate));
        equal(2, duplicateHints.compactForCheckpoint(duplicate).livePositions().size(),
                "checkpoint keeps duplicate occurrence identity");
        equal(0, duplicateHints.compactForCheckpoint(duplicate, duplicateHistory).authoritativeRefs().size(),
                "history-aware checkpoint drops duplicates when exact snapshot restoration already fixes identity");
        String changed = "head\nVALUE();\ntail\n";
        var changedHints = initial.applyEdit(text, changed, text.indexOf("value"), 5, 5);
        equal(1, changedHints.compactForCheckpoint(changed).livePositions().size(), "changed one-to-one locator retained for partial Undo");
        String deleted = "head\ntail\n";
        var deletedHints = initial.applyEdit(text, deleted, 5, "value();\n".length(), 0).compactForCheckpoint(deleted);
        equal(1, deletedHints.deletedAnchors().size(), "checkpoint preserves deletion tombstone");
        var restored = deletedHints.applyEdit(deleted, text, 5, 0, "value();\n".length());
        equal(List.of(r(0, text.length())), project(history, text, restored).reviewedRanges(), "checkpoint tombstone can restore exact reviewed row");
        String boilerplate = "head\n}\n\n}\n\n}\ntail\n";
        var boilerHistory = ReviewHistoryEngine.review(ReviewHistory.empty(), boilerplate, List.of(r(0, boilerplate.length())), 1);
        var boilerHints = ProjectionHints.fromProjection(boilerHistory, project(boilerHistory, boilerplate));
        String prefix = "new_row();\n";
        String shifted = prefix + boilerplate;
        boilerHints = boilerHints.applyEdit(boilerplate, shifted, 0, 0, prefix.length());
        equal(true, boilerHints.compactForCheckpoint(shifted).authoritativeRefs().size() > 0,
                "conservative typing checkpoint retains repeated boilerplate");
        equal(0, boilerHints.compactForCheckpoint(shifted, boilerHistory).authoritativeRefs().size(),
                "history-aware settled checkpoint drops boilerplate with matching cold correspondence");
        String replaced = "HEAD\n}\n\n}\n\n}\ntail\n";
        var editedBoiler = ProjectionHints.fromProjection(boilerHistory, project(boilerHistory, boilerplate))
                .applyEdit(boilerplate, replaced, 0, 4, 4);
        equal(1, editedBoiler.compactForCheckpoint(replaced, boilerHistory).authoritativeRefs().size(),
                "single-row mutation checkpoint size follows changed row, not common braces");
        String increased = "}\n" + boilerplate;
        var moreDuplicates = ProjectionHints.fromProjection(boilerHistory, project(boilerHistory, boilerplate))
                .applyEdit(boilerplate, increased, 0, 0, 2);
        var accurate = moreDuplicates.compactForCheckpoint(increased, boilerHistory);
        equal(project(boilerHistory, increased, moreDuplicates).reviewedRanges(),
                project(boilerHistory, increased, accurate).reviewedRanges(),
                "increased duplicate occurrences keep safe known identity checkpoint");
        String balanced = "head\nsame();\nanchor();\nsame();\ntail\n";
        var balancedHistory = ReviewHistoryEngine.review(ReviewHistory.empty(), balanced, List.of(r(5, 13)), 1);
        var balancedHints = ProjectionHints.fromProjection(balancedHistory, project(balancedHistory, balanced));
        int remove = balanced.lastIndexOf("same();\n");
        String removed = balanced.substring(0, remove) + balanced.substring(remove + 8);
        balancedHints = balancedHints.applyEdit(balanced, removed, remove, 8, 0);
        String balancedCopy = removed.substring(0, 5) + "same();\n" + removed.substring(5);
        balancedHints = balancedHints.applyEdit(removed, balancedCopy, 5, 0, 8).compactForCheckpoint(balancedCopy, balancedHistory);
        var balancedProjection = project(balancedHistory, balancedCopy, balancedHints);
        equal(FileProjection.Status.UNREVIEWED, balancedProjection.rows().get(1).status(),
                "balanced delete-copy does not inherit review merely because duplicate counts stayed equal");
        equal(FileProjection.Status.REVIEWED, balancedProjection.rows().get(2).status(),
                "balanced delete-copy keeps original checked occurrence through settled compaction");
    }

    private static void pendingProjectionEditAudit() {
        String a = "head\nbefore_after();\ntail\n";
        String offline = "HEADER\nbefore_after();\ntail\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), a, List.of(r(0, a.length())), 1);
        int offset = offline.indexOf('_') + 1;
        String inserted = "\nbefore_after();\n";
        String current = offline.substring(0, offset) + inserted + offline.substring(offset);
        var pending = ProjectionHints.empty().applyEdit(offline, current, offset, 0, inserted.length());
        equal(FileProjection.Status.REVIEWED, project(history, current, pending).rows().get(2).status(),
                "unresolved pending history reproduces cold exact-copy hazard without guard");
        var guarded = pending.guardUnresolvedEdit(history, offline, current, offset, 0, inserted.length());
        equal(FileProjection.Status.UNREVIEWED, project(history, current, guarded).rows().get(2).status(),
                "pending-state guard blocks affected exact copy until its identity is known");
        equal(FileProjection.Status.REVIEWED, project(history, current, guarded).rows().get(4).status(),
                "pending guard preserves unrelated cold-mapped unchanged row");
        var settled = guarded.mergeResolved(ProjectionHints.fromProjection(history, project(history, current, guarded)));
        equal(FileProjection.Status.UNREVIEWED,
                project(history, current, settled.compactForCheckpoint(current, history)).rows().get(2).status(),
                "settled checkpoint retains pending identity denial through reload");
        var restored = guarded.applyEdit(current, a, 0, current.length(), a.length());
        equal(List.of(r(0, a.length())), project(history, a, restored).reviewedRanges(),
                "pending guard still permits exact reviewed snapshot restoration");
    }

    private static void coldAuthorityDiagnostics() {
        String a = "head\nvalue = 0;\ntail\n";
        int start = a.indexOf("value"), end = a.indexOf("tail");
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), a, List.of(r(0, a.length())), 1);
        history = ReviewHistoryEngine.revoke(history, project(history, a).rows().get(1).revocableRefs());
        history = ReviewHistoryEngine.review(history, a, List.of(r(start, end)), 2);
        var active = new EvidenceRef(history.evidence().getLast().id(), start, end);
        String deleted = a.substring(0, start) + a.substring(end);
        var knownDeletion = ProjectionHints.fromProjection(history, project(history, a))
                .applyEdit(a, deleted, start, end - start, 0).compactForCheckpoint(deleted, history);
        var cold = new ProjectionHints(Map.of(), knownDeletion.authoritativeRefs());
        String changed = a.replace("value = 0;", "value = 7;");
        var diagnostic = project(history, changed, cold);
        var row = diagnostic.rows().get(1);
        equal(FileProjection.Status.CHANGED, row.status(), "unseen cold one-row change retains changed diagnostic after re-review");
        equal(FileProjection.Reason.TEXT_CHANGED, row.reason(), "cold changed diagnostic explains text change");
        equal(List.of(r(start, end)), diagnostic.changedRanges(), "cold changed row supplies gutter range");
        equal(List.of(active), row.evidenceRefs(), "cold diagnostic retains only active historical origin for diff");
        equal(List.of(), row.revocableRefs(), "cold diagnostic never invents direct identity for manual revoke");
        equal(0, diagnostic.deletedCount(), "cold missing coordinates never invent a deletion anchor");
        equal(FileProjection.Status.REVIEWED, diagnostic.rows().get(0).status(), "cold denied row does not disturb reviewed prefix");
        equal(FileProjection.Status.REVIEWED, diagnostic.rows().get(2).status(), "cold denied row does not disturb reviewed suffix");
        var refreshed = cold.mergeResolved(ProjectionHints.fromProjection(history, diagnostic));
        equal(false, refreshed.livePositions().containsKey(active), "diagnostic refresh cannot turn possible origin into live identity");
        equal(true, refreshed.unresolvedPositions().get(active).originalText() == null,
                "cold diagnostic has no invented restorable original hunk");
        equal(FileProjection.Status.CHANGED, project(history, changed, refreshed).rows().get(1).status(),
                "second projection retains changed diagnostic without greening");
        String changedOffline = changed + "offline\n";
        var restoredText = refreshed.applyEdit(changed, changedOffline, changed.length(), 0, 8)
                .applyEdit(changedOffline, a + "offline\n", changed.indexOf('7'), 1, 1);
        var restoredCandidate = project(history, a + "offline\n", restoredText).rows().get(1);
        equal(FileProjection.Status.NEEDS_REVIEW, restoredCandidate.status(),
                "returning possible candidate text without its origin changes red to yellow, never green");
        equal(List.of(), restoredCandidate.revocableRefs(), "diagnostic edit cannot acquire a precise manual-clear target");

        String unchanged = a + "offline\n";
        var yellow = project(history, unchanged, cold);
        equal(FileProjection.Status.NEEDS_REVIEW, yellow.rows().get(1).status(), "unique cold unchanged candidate is yellow, never green");
        equal(FileProjection.Reason.AMBIGUOUS, yellow.rows().get(1).reason(), "cold unchanged diagnostic explains lost identity");
        equal(List.of(), yellow.rows().get(1).revocableRefs(), "yellow possible origin cannot revoke an unrelated copy");
        var yellowHints = cold.mergeResolved(ProjectionHints.fromProjection(history, yellow));
        String afterEdit = "new\n" + unchanged;
        yellowHints = yellowHints.applyEdit(unchanged, afterEdit, 0, 0, 4);
        equal(FileProjection.Status.NEEDS_REVIEW, project(history, afterEdit, yellowHints).rows().get(2).status(),
                "refresh and another local edit cannot certify a cold candidate");
        equal(FileProjection.Status.REVIEWED, project(history, a, cold).rows().get(1).status(),
                "whole reviewed snapshot restoration remains explicitly supported");

        String relocatedCopy = "head\nprefix\nvalue = 0;\ntail\noffline\n";
        var relocated = project(history, relocatedCopy, cold);
        equal(FileProjection.Status.NEEDS_REVIEW, relocated.rows().get(2).status(),
                "unique copied text after origin loss remains only a possible diagnostic");
        equal(List.of(), relocated.rows().get(2).revocableRefs(), "copied cold candidate never gains direct proof");
        equal(0, relocated.deletedCount(), "cold remapping does not fabricate deletion count");
        equal(0, project(history, deleted + "offline\n", cold).deletedCount(),
                "missing cold origin with no candidate does not create a guessed tombstone");
        String duplicated = "head\nprefix\nvalue = 0;\nvalue = 0;\ntail\noffline\n";
        var ambiguous = project(history, duplicated, cold);
        equal(FileProjection.Status.UNREVIEWED, ambiguous.rows().get(2).status(), "ambiguous copies have no invented unique locator");
        equal(FileProjection.Status.UNREVIEWED, ambiguous.rows().get(3).status(), "second ambiguous copy has no inherited diagnostic proof");
        equal(0, ambiguous.deletedCount(), "ambiguous cold gap never creates a restorable deletion");
    }

    private static void bulkReloadRestoresExactCandidateWithoutChangedDiagnostic() {
        String baseline = "head\nvalue = 0;\ntail\n";
        int start = baseline.indexOf("value"), end = baseline.indexOf("tail");
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), baseline,
                List.of(r(0, baseline.length())), 1);
        var ref = project(history, baseline).rows().get(1).evidenceRefs().getFirst();
        // An unseen external revision retains the lost-origin restriction without coordinates.
        var cold = new ProjectionHints(Map.of(), Set.of(ref));
        String intermediate = baseline.replace("value = 0;", "value = 7;");
        var diagnostic = project(history, intermediate, cold);
        equal(FileProjection.Status.CHANGED, diagnostic.rows().get(1).status(),
                "intermediate external revision really changes the reviewed row");
        var hints = cold.mergeResolved(ProjectionHints.fromProjection(history, diagnostic));
        var intermediateHints = hints;

        String replacement = "value = 0;\ninserted\n";
        String current = baseline.substring(0, start) + replacement + baseline.substring(end);
        hints = hints.applyEdit(intermediate, current, start, end - start, replacement.length());
        var restored = project(history, current, hints);
        equal(FileProjection.Status.NEEDS_REVIEW, restored.rows().get(1).status(),
                "exact original text after a bulk reload is ambiguous, never falsely text-changed");
        equal(FileProjection.Reason.AMBIGUOUS, restored.rows().get(1).reason(),
                "a lost origin explains the exact candidate without claiming its text changed");
        equal(List.of(), restored.rows().get(1).revocableRefs(),
                "restoring candidate text cannot invent a precise row identity");
        equal(false, restored.rows().get(2).status() == FileProjection.Status.REVIEWED,
                "the inserted row cannot inherit a historical review");
        equal(FileProjection.Status.REVIEWED, restored.rows().get(0).status(),
                "bulk reload diagnostic does not disturb the unchanged prefix");
        equal(FileProjection.Status.REVIEWED, restored.rows().get(3).status(),
                "bulk reload diagnostic does not disturb the unchanged suffix");

        hints = hints.mergeResolved(ProjectionHints.fromProjection(history, restored));
        equal(FileProjection.Status.NEEDS_REVIEW, project(history, current, hints).rows().get(1).status(),
                "repeated projection cannot turn the exact candidate back into a changed row");
        var checkpoint = hints.compactForCheckpoint(current, history);
        equal(FileProjection.Status.NEEDS_REVIEW, project(history, current, checkpoint).rows().get(1).status(),
                "checkpoint compaction preserves the exact candidate's lost-origin restriction");
        equal(List.of(), project(history, current, checkpoint).rows().get(1).revocableRefs(),
                "checkpoint compaction cannot promote a possible candidate to a precise identity");

        String otherReplacement = "value = 9;\ninserted\n";
        String otherBranch = baseline.substring(0, start) + otherReplacement + baseline.substring(end);
        var otherHints = intermediateHints.applyEdit(intermediate, otherBranch,
                start, end - start, otherReplacement.length());
        var otherProjection = project(history, otherBranch, otherHints);
        equal(FileProjection.Status.CHANGED, otherProjection.rows().get(1).status(),
                "an expanded reload hunk without an exact baseline candidate remains changed");
        equal(FileProjection.Reason.TEXT_CHANGED, otherProjection.rows().get(1).reason(),
                "different branch text retains the real text-change explanation");
    }

    /** Independent row identities and explicit review facts, checked through repeated worker refreshes. */
    private static void multiEditIdentityAudit() {
        var random = new Random(0x41554449544cL);
        for (int run = 0; run < 12; run++) {
            var state = new IdentityState();
            for (int step = 0; step < 50; step++) {
                // A unique unreviewed footer prevents accidental historical whole-file restoration.
                int footer = state.rows.size() - 1;
                state.replaceRow(footer, "epoch_" + run + "_" + step + "();\n");
                int row = random.nextInt(state.rows.size() - 1);
                switch (random.nextInt(5)) {
                    case 0 -> state.insertRow(row, "fresh_" + state.nextId + "();\n");
                    case 1 -> {
                        var old = state.rows.get(row).text();
                        state.replaceRow(row, (old.charAt(0) == 'X' ? "s" : "X") + old.substring(1));
                    }
                    case 2 -> {
                        if (state.rows.size() > 2) state.deleteRow(row);
                        else state.markRow(row);
                    }
                    case 3 -> state.clearRow(row);
                    default -> state.markRow(row);
                }
                state.assertProjection("identity history " + run + "/" + step);
            }
        }
    }

    private record IdentityRow(int id, String text) { }
    private record ModelFact(int baselineStart, String text) { }

    private static final class IdentityState {
        private final List<IdentityRow> rows = new ArrayList<>(List.of(new IdentityRow(0, "same();\n"),
                new IdentityRow(1, "same();\n"), new IdentityRow(2, "other();\n"), new IdentityRow(3, "epoch();\n")));
        private final Map<String, Map<Integer, ModelFact>> modelEvidence = new HashMap<>();
        private ReviewHistory history = ReviewHistory.empty();
        private ProjectionHints hints = ProjectionHints.empty();
        private FileProjection projection;
        private int nextId = 4;

        private IdentityState() {
            String text = text();
            history = ReviewHistoryEngine.review(history, text, List.of(r(0, text.length())), 1);
            var initial = new HashMap<Integer, ModelFact>();
            int start = 0;
            for (var row : rows) {
                initial.put(row.id(), new ModelFact(start, row.text()));
                start += row.text().length();
            }
            modelEvidence.put(history.evidence().get(0).id(), initial);
            refresh();
        }

        private String text() { return rows.stream().map(IdentityRow::text).collect(java.util.stream.Collectors.joining()); }
        private int start(int row) { return rows.subList(0, row).stream().mapToInt(item -> item.text().length()).sum(); }
        private void refresh() {
            projection = project(history, text(), hints);
            hints = hints.mergeResolved(ProjectionHints.fromProjection(history, projection));
        }

        private void replaceRow(int row, String replacement) {
            String previous = text();
            int offset = start(row);
            var old = rows.get(row);
            rows.set(row, new IdentityRow(old.id(), replacement));
            hints = hints.applyEdit(previous, text(), offset, old.text().length(), replacement.length());
            refresh();
        }

        private void insertRow(int row, String added) {
            String previous = text();
            int offset = start(row);
            rows.add(row, new IdentityRow(nextId++, added));
            hints = hints.applyEdit(previous, text(), offset, 0, added.length());
            refresh();
        }

        private void deleteRow(int row) {
            String previous = text();
            int offset = start(row);
            var removed = rows.remove(row);
            hints = hints.applyEdit(previous, text(), offset, removed.text().length(), 0);
            refresh();
        }

        private void markRow(int row) {
            int offset = start(row);
            history = ReviewHistoryEngine.review(history, text(), List.of(r(offset, offset + rows.get(row).text().length())),
                    history.revision() + 1);
            modelEvidence.put(history.evidence().getLast().id(), new HashMap<>(Map.of(rows.get(row).id(),
                    new ModelFact(offset, rows.get(row).text()))));
            refresh();
        }

        private void clearRow(int row) {
            var refs = new ArrayList<EvidenceRef>();
            int identity = rows.get(row).id();
            for (var entry : modelEvidence.entrySet()) {
                var fact = entry.getValue().remove(identity);
                if (fact != null) refs.add(new EvidenceRef(entry.getKey(), fact.baselineStart(),
                        fact.baselineStart() + fact.text().length()));
            }
            history = ReviewHistoryEngine.revoke(history, refs);
            refresh();
        }

        private void assertProjection(String description) {
            var facts = new HashMap<Integer, java.util.Set<String>>();
            for (var modelProof : modelEvidence.values()) {
                modelProof.forEach((identity, fact) -> facts.computeIfAbsent(identity, ignored -> new java.util.HashSet<>())
                        .add(fact.text()));
            }
            for (int i = 0; i < rows.size(); i++) {
                boolean expected = facts.getOrDefault(rows.get(i).id(), java.util.Set.of()).contains(rows.get(i).text());
                boolean green = projection.rows().get(i).status() == FileProjection.Status.REVIEWED;
                equal(expected, green, description + " row " + i + " exact reviewed identity");
                if (!facts.containsKey(rows.get(i).id())) {
                    equal(FileProjection.Status.UNREVIEWED, projection.rows().get(i).status(),
                            description + " row " + i + " never reviewed new identity");
                }
            }
        }
    }

    private static FileProjection copyProjectionValues(FileProjection source) {
        var rows = source.rows().stream().map(row -> new FileProjection.Row(row.line(), row.start(), row.end(),
                row.status(), row.reason(), new FileProjection.RowEvidence(new ArrayList<>(row.evidence().direct()),
                row.evidence().possibleGroups().stream().map(group -> new FileProjection.PossibleEvidence(group.reason(),
                        new ArrayList<>(group.refs()))).toList()))).toList();
        return new FileProjection(source.currentSnapshotId(), source.historyRevision(), rows,
                source.reviewedRanges(), source.yellowRanges(), source.changedRanges(), source.deletions());
    }

    private static void sharedProjectionWorkBudget() {
        String a = "a\nb\na\nvariant_a\n";
        String b = "a\nb\na\nvariant_b\n";
        String current = "b\na\nb\ncurrent\n";
        var history = ReviewHistoryEngine.review(ReviewHistory.empty(), a, List.of(r(0, a.length())), 1);
        history = ReviewHistoryEngine.review(history, b, List.of(r(0, b.length())), 2);
        var contexts = new HashMap<String, ContextMap>();
        history.snapshots().forEach((id, snapshot) -> contexts.put(id, context(snapshot.text(), "global")));
        var budget = new ProjectionWorkBudget(25);
        var projection = ReviewHistoryEngine.project(history, current, contexts, context(current, "global"),
                null, budget, () -> { });
        equal(25L, budget.consumedDiffCells(), "all historical snapshots share one quadratic allowance");
        equal(0L, budget.remainingDiffCells(), "shared quadratic allowance cannot overspend");
        equal(FileProjection.Reason.DIFF_LIMIT, projection.rows().getFirst().reason(),
                "newer unresolved proof reports aggregate allowance exhaustion");
        equal(List.of(), projection.reviewedRanges(), "budget exhaustion never invents reviewed rows");
        var hints = ProjectionHints.fromProjection(history, projection);
        hints.compactForCheckpoint(current, history, budget, () -> { });
        equal(25L, budget.consumedDiffCells(), "checkpoint may share the projection's remaining allowance");
        var linear = SnapshotReconciler.map("same\n".repeat(10_000), "new\n" + "same\n".repeat(10_000),
                new ProjectionWorkBudget(0), () -> { });
        equal(1, linear.currentLine(0), "zero quadratic allowance preserves unambiguous linear matching");
        equal(10_000, linear.currentLine(9_999), "linear matching remains available after quadratic exhaustion");
    }

    private static FileProjection project(ReviewHistory history, String current) {
        return project(history, current, null);
    }

    private static FileProjection project(ReviewHistory history, String current, ProjectionHints hints) {
        var contexts = new HashMap<String, ContextMap>();
        history.snapshots().forEach((id, snapshot) -> contexts.put(id, context(snapshot.text(), "global")));
        return ReviewHistoryEngine.project(history, current, contexts, context(current, "global"), hints);
    }

    private static ContextMap context(String text, String fingerprint) {
        int rows = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') rows++;
        return new ContextMap("test:v1", Collections.nCopies(rows, fingerprint));
    }

    private static ReviewedRange r(int start, int end) { return new ReviewedRange(start, end); }

    private static void equal(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        checks++;
    }
}
