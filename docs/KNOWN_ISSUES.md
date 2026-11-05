# Known issues

This file records existing defects, verification limits and the status of follow-up functional fixes. The structural refactor at `ed7cebd` preserved behavior; the subsequent Git/checkout fixes intentionally change the behavior described below. Issue identifiers are local documentation references, not tracker tickets.

## Addressed Git and refresh problems

### DOR-004: context heuristics invalidate lines outside Git changes

**Status:** addressed; verified with actual Git subprocesses and physical IDE fixtures. A manually installed IDE session has not been exercised.

**Location:** `ReviewProjectionComputer`, `ReviewGitDiff`, and the Git paths in `DoReviewService`.

**Conditions and impact:** a reviewed function header changes, an unsupported-language file changes anywhere, or an old origin checkpoint carries an ambiguous match. Syntax/whole-file fingerprints previously made unchanged rows require review. Git repositories now use zero-context Git hunks against the reviewed snapshot and current document, including unsaved text. Legacy heuristic constraints do not override Git correspondence. Explicit clears still revoke evidence.

**Verification:** `ReviewGitDiffTest`, `ReviewGitProjectionTest`, and Git edit/Undo/Redo/pull cases in `DoReviewServiceTest`. Duplicate rows follow Git's selected alignment; this is a deliberate change from persistent local-origin heuristics. Non-Git context behavior remains covered separately.

### DOR-005: carried local events mix branch review state

**Status:** addressed; real Git checkout, restart and linked-worktree regressions cover the local overlay.

**Location:** `SharedHistoryRepository`, `SharedHistoryCheckoutOverlay`, `SharedHistoryGitMembership`.

**Conditions and impact:** untracked `.doreview` files survive checkout, while tracked history may disappear in the destination branch. Previously the working-tree file set either leaked later branch operations or removed useful starting evidence. First visits now inherit a frozen local basis; branch-owned later operations remain isolated unless deliberately committed in the destination. Returning to an existing branch reuses its basis. Same-branch deletion/reset of native events remains effective.

**Verification:** `SharedHistoryCheckoutTest` exercises return/restart, missing artifacts, explicit inherited mutations, intentional committed sharing, failed first visits, publication interruption, SQL ownership failure and two-connection reconciliation. Ownership is durable before JSON publication; externally replaced SQL active sets cannot bypass reconciliation using stale filesystem metadata. `SharedReviewHistoryStoreTest` also covers the complete editor path across changed branch source and explicit revocations. `SharedHistoryGitMembershipTest` verifies exact blob identity. Existing events without recorded local ownership remain accepted as shared external history; the fix cannot infer which branch created pre-existing untracked files. Deleted local databases cannot recover unpublished branch bases from portable files alone.

### DOR-006: metadata refresh repeatedly clears valid presentation

**Status:** addressed by background reconciliation and stable presentation; automated editor-resource regressions pass.

**Location:** `SharedReviewHistoryStore`, `DoReviewService`, `ReviewEditorPresentation`, `ReviewStripePresentation`.

**Conditions and impact:** periodic scans, own publications or `.git`/`.doreview` notifications previously replaced all views and cleared confirmed highlights before recomputation. No-op changes now retain warmed models, highlighters and stripe snapshots. Additive refresh keeps still-supported marks visible while computation runs. Real edits, removals, revocations and unreadable history still invalidate affected marks.

**Verification:** `ReviewBackgroundRefreshTest` checks repeated notifications, metadata hints, blocked loading/capture, author metadata, explicit incoming revocation and synchronous clearing before text changes. Synthetic checks establish resource reuse, not a real-project latency guarantee.

### DOR-007: immutable publication can replace a racing writer

**Status:** addressed; deterministic concurrent tests reproduce the collision boundary and verify no replacement.

**Location:** `SharedHistoryFiles.writeImmutable`, `SharedHistoryRepository.savePending`.

**Conditions and impact:** two writers publish different bytes at the same final path after both observed it absent. POSIX atomic rename permits replacing an existing target. Publication now uses atomic hard-link creation, compares an existing winner and rejects conflicting bytes. Unsupported filesystems preserve pending work instead of publishing partial files. Snapshot identities remain content-addressed; event IDs remain UUIDs.

**Verification:** `SharedHistoryFilesTest` races identical and conflicting publishers. `SharedHistoryCheckoutTest.testIdenticalPendingStateDoesNotRewriteItsTimestampOrSnapshot` uses SQL triggers and exact payload comparison to check deduplication. Pure Git projection no longer emits checkpoint events or edit snapshots; explicit marks and clears still retain their evidence and causal parents.

## Confirmed defects

### DOR-001: inherited Git environment can select another repository's review author

**Status:** reproduced at the Git command boundary; the IDE call path is confirmed by source inspection. An end-to-end IDE reproduction has not been performed.

**Location:** [SharedReviewHistoryStore](../src/main/java/dev/doreview/ide/SharedReviewHistoryStore.java), `Session` construction and `gitConfig`. For comparison, [ReviewGitHistory](../src/main/java/dev/doreview/ide/ReviewGitHistory.java), `run` / `prepareEnvironment`, already removes inherited `GIT_*` variables for version-label lookups.

**Conditions:** the IDE process inherits `GIT_DIR` pointing to another valid repository. The selected worktree and that repository have different local `user.name` or `user.email` settings.

**Behavior and impact:** author lookup starts `git -C <selected-root> config --get user.name` and the corresponding email query with the inherited environment. `-C` changes the working directory but does not override `GIT_DIR`, so Git can return the other repository's identity. That author is retained in the storage session and may be written to new review events. This reproduction establishes an attribution problem; it does not demonstrate incorrect coverage, lost evidence or data corruption.

**Reproduction:** use two disposable repositories with different local identities:

```sh
scratch="$(mktemp -d)"
git init -q "$scratch/a"
git init -q "$scratch/b"
git -C "$scratch/a" config user.name 'Author A'
git -C "$scratch/b" config user.name 'Author B'
git -C "$scratch/a" config user.email 'a@example.invalid'
git -C "$scratch/b" config user.email 'b@example.invalid'

git -C "$scratch/a" config --get user.name
# Author A
GIT_DIR="$scratch/b/.git" git -C "$scratch/a" config --get user.name
# Author B
GIT_DIR="$scratch/b/.git" git -C "$scratch/a" config --get user.email
# b@example.invalid
```

The same command returns Author A after removing the inherited repository-selection variables, as the version-label, Git diff and committed-event lookups do. Remove the temporary directory after the check. A future fix should independently test worktree-specific author lookup in a controlled environment. Author lookup is outside the requested Git projection/checkout fixes.

## Unconfirmed hypotheses

No additional unconfirmed defects are currently recorded. Manual IDE latency and optional CodeGlance compatibility remain verification limits below.

## Baseline test and environment findings

These failures were reproduced on the unchanged baseline on macOS ARM64 with Java 25 and PyCharm 2026.2.3. Their test expectations and setup are preserved in this refactor.

### DOR-002: the external-file shortcut test reaches a forbidden VFS root

**Status:** reproduced in the baseline test suite.

**Location:** [ReviewEditorShortcutTest](../src/test/java/dev/doreview/ide/ReviewEditorShortcutTest.java), `testViewerAndExternalFileEditorNeverReceiveLocalNavigationShortcuts`.

**Conditions and reproduction:** run the regular `check`, or select this test with `./gradlew test --tests 'dev.doreview.ide.ReviewEditorShortcutTest.testViewerAndExternalFileEditorNeverReceiveLocalNavigationShortcuts'` in the macOS environment described above.

**Observed result:** the fixture creates an external temporary file and calls `refreshAndFindFileByNioFile`; the IDE test framework rejects the path with `VfsRootAccessNotAllowedError`. The test fails before checking the external editor's shortcuts. This is a test setup/access limitation, not evidence that production editor ownership is incorrect. No VFS permissions or test assertions were changed.

### DOR-003: the registered mark action does not expose the expected Ctrl+M shortcut in the macOS test

**Status:** reproduced in the baseline test suite; actual shortcut behavior in a manually launched IDE has not been verified.

**Location:** [ReviewMarkActionTest](../src/test/java/dev/doreview/ide/ReviewMarkActionTest.java), `testRegisteredMarkActionAdvertisesCtrlMShortcut`; the action's existing registration is in [plugin.xml](../src/main/resources/META-INF/plugin.xml).

**Conditions and reproduction:** run `./gradlew test --tests 'dev.doreview.ide.ReviewMarkActionTest.testRegisteredMarkActionAdvertisesCtrlMShortcut'` with the same macOS SDK/test environment.

**Observed result:** the loaded action's shortcut set does not contain the test's expected `KeyboardShortcut(ctrl M)`, so the assertion fails. The cause and any effect of macOS keymap selection need a separate investigation. This result alone does not establish which shortcut works in a running IDE. The keymap registration and test expectation remain unchanged.

## Structural-refactor baseline

Validation on 2026-11-03 used macOS ARM64, Temurin 25.0.5 and the pinned PyCharm 2026.2.3 SDK. The unchanged baseline at `7188ef6` ran 362 JUnit tests: 360 passed and the two tests described in DOR-002/DOR-003 failed. The refactored tree ran 364: 362 passed, with exactly the same two failures and no removed tests. Both added compatibility tests also passed separately against the baseline production classes.

The four standalone core suites passed all 9,617 checks before and after refactoring. Core isolation compilation, plugin archive creation and plugin-structure verification passed. The 85 existing public compiled types, including constructors, methods, fields and constants, matched the baseline. The complete `check` remains unsuccessful because of DOR-002 and DOR-003; those failures were not skipped or weakened.

The regular suite does not include tests against the actual CodeGlance plugin unless `codeGlancePluginPath` points to a compatible installation. The source-corpus audit similarly needs an explicit `reviewCorpus` directory. An omitted optional suite is not a passing result.

## Follow-up validation and limits

Validation on 2026-11-05 used the same isolated Java 25/PyCharm 2026.2.3 environment. The final suite ran 407 JUnit tests: 405 passed, with exactly the same DOR-002/DOR-003 failures, zero errors and zero skipped tests. All 9,617 standalone core checks passed. Core isolation compilation, `buildPlugin` and `verifyPluginStructure` passed; the overall `check` command remains unsuccessful because of those two baseline failures. No tests were removed. Git-specific expectations about writing edit checkpoints and applying old heuristic origin denials were intentionally replaced by the requested Git behavior; non-Git migration-denial assertions remain covered unchanged.

See [Development](DEVELOPMENT.md) for the full build command, isolated test-data paths and focused test suites. Synthetic performance fixtures check output and bounds; their timings do not establish real-project latency or retained-memory guarantees. A compatible local CodeGlance plugin and a source corpus were unavailable, so those optional checks were not run.

Git projection has bounded input/output and a three-second aggregate process deadline. If Git is unavailable, input exceeds these limits or computation fails, saved evidence remains intact and newly inferred marks are withheld. Existing exact-snapshot facts can remain confirmed. Git executable availability and filesystem hard-link support are runtime requirements.
