# Known issues

This file records existing behavior found during structural refactoring. The refactor preserves these behaviors; it does not include functional fixes. Issue identifiers below are local documentation references, not tracker tickets.

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

The same command returns Author A after removing the inherited repository-selection variables, as the version-label lookup does. Remove the temporary directory after the check. A future fix should independently test worktree-specific author lookup in a controlled environment. It is deliberately outside this refactor.

## Unconfirmed hypotheses

No additional unconfirmed findings are currently recorded. Splitting responsibilities and removing duplicated code are refactoring changes, not evidence of a user-visible bug.

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

## Verification limits

Validation on 2026-11-03 used macOS ARM64, Temurin 25.0.5 and the pinned PyCharm 2026.2.3 SDK. The unchanged baseline at `7188ef6` ran 362 JUnit tests: 360 passed and the two tests described in DOR-002/DOR-003 failed. The refactored tree ran 364: 362 passed, with exactly the same two failures and no removed tests. Both added compatibility tests also passed separately against the baseline production classes.

The four standalone core suites passed all 9,617 checks before and after refactoring. Core isolation compilation, plugin archive creation and plugin-structure verification passed. The 85 existing public compiled types, including constructors, methods, fields and constants, matched the baseline. The complete `check` remains unsuccessful because of DOR-002 and DOR-003; those failures were not skipped or weakened.

The regular suite does not include tests against the actual CodeGlance plugin unless `codeGlancePluginPath` points to a compatible installation. The source-corpus audit similarly needs an explicit `reviewCorpus` directory. An omitted optional suite is not a passing result.

See [Development](DEVELOPMENT.md) for the full build command, isolated test-data paths and focused test suites. Synthetic performance fixtures check output and bounds; their timings do not establish real-project latency or retained-memory guarantees.
