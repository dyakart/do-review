# Development

## Environment

Use Java 25 and the Gradle wrapper included in the repository. The default build targets PyCharm 2026.2.3 and pinned language plugins, downloading missing dependencies. To use an installed IDE, pass `-PlocalIdePath=<installation-directory>`.

The wrapper pins Gradle 9.2.1 and its distribution checksum. A first build needs network access to the Gradle, Maven and JetBrains repositories, plus space for the IDE distribution and test sandbox. Compilation and the IDE test runtime must both use Java 25.

Install Git and make `git` available on `PATH` for both development and the IDE runtime. The regular test suite creates disposable repositories and exercises commits, checkouts and linked worktrees. Shared publication requires hard links within the repository filesystem; unsupported filesystems retain pending work and report the error rather than copying a partially visible event.

On macOS or Linux, a downloaded JDK can be used for one invocation without changing system settings:

```sh
JAVA_HOME="/absolute/path/to/jdk-home" ./gradlew check buildPlugin verifyPluginStructure
```

For a macOS JDK bundle, its Java home is normally the bundle's `Contents/Home` directory. Set `GRADLE_USER_HOME` for the invocation as well if an isolated dependency cache is needed.

On Windows, `build.ps1` uses the Java runtime bundled with a local PyCharm 2026.2 installation. It searches the standard JetBrains installation directory or accepts an explicit path:

```powershell
.\build.ps1 -IdePath 'D:/Apps/PyCharm 2026.2'
```

Language adapter dependencies are pinned in [build.gradle](../build.gradle). Local installations can be selected with `tactPluginPath` and `tonPluginPath`.

## Build

```sh
./gradlew check buildPlugin verifyPluginStructure
```

On Windows, use `.\gradlew.bat` or run `.\build.ps1`, which executes these tasks by default. `check` runs the IDE tests, standalone core tests and isolated core compilation.

Plugin archives are written to `build/distributions/`. JUnit reports are in `build/reports/tests/test/`. Tests default to `build/test-data/review-history/` for local SQLite data through `doreview.data.path`; individual tests may override this with a disposable directory. Test runs must not write history into the user's normal application-data directory.

## Runtime dependencies

The plugin packages Xerial SQLite JDBC 3.53.4.0 and Gson 2.13.2 through regular Gradle dependencies. SQLite uses the driver's native libraries, so installing or updating the plugin requires an IDE restart. The plugin does not depend on the IDE's internal SQLite API or Database Tools plugin.

[Third-party notices](THIRD_PARTY_LICENSES.md) are copied to `META-INF/THIRD_PARTY_LICENSES.md` in the plugin JAR. When changing dependencies, preserve their upstream license files and verify the distribution contains the required libraries and notices.

## Run and test

Launch a sandbox IDE:

```sh
./gradlew runIde
```

Run a single test class:

```sh
./gradlew test --tests dev.doreview.ide.SharedReviewHistoryStoreTest
```

`SharedReviewHistoryStoreTest` exercises the production facade with native SQLite, Git branch changes, XML import and editor lifecycle. `SharedHistoryRepositoryTest` covers portable event merging, durable pending work and concurrent database access. Other IDE tests use detached legacy fixtures where appropriate.

The Windows helper forwards Gradle arguments through `-Tasks`, for example `.\build.ps1 -Tasks runIde`.

## Test coverage map

- **Core algorithms:** `rangeEngineTest`, `snapshotReconcilerTest`, `lineReviewTrackerTest` and `reviewHistoryEngineTest` are executable regression suites registered together in Gradle. They retain separate task names and run with assertions enabled. `compileCoreIsolation` compiles every core source with only `java.base` available.
- **Core models and bounds:** `BlankReviewTest`, `ReviewHistoryIndexTest` and `ReviewHistoryPerformanceTest` exercise nonblank evidence, indexes, cache reuse and work budgets through JUnit.
- **Storage compatibility:** `ReviewHistoryStoreTest` and `CheckpointTransferTest` cover legacy state, checkpoints, validation and defensive copies. `SharedHistoryRepositoryTest` covers portable artifacts, pending durability, concurrent writers, publication races, corrupt/future formats and WAL backup.
- **Shared IDE history:** `SharedReviewHistoryStoreTest` exercises the production facade, migration, editor lifecycle, nested repository roots and checkout changes. `SharedHistoryGitWorkflowTest` covers independent clones, linked worktrees and concurrent review/revocation.
- **Git correspondence:** `ReviewGitDiffTest` runs real Git for insertions, deletions, duplicate rows, line endings, configured algorithms, process limits, cancellation and temporary-file ownership. `ReviewGitProjectionTest` verifies unsaved input, explicit revocations, ignored legacy heuristic constraints and the absence of PSI calls in Git mode. `SharedHistoryGitMembershipTest` validates exact committed payloads/paths, foreign branches and SHA-256 Git repositories.
- **Background refresh and collisions:** `ReviewBackgroundRefreshTest` covers no-op notifications, additive history refresh, metadata hints and synchronous clearing on real edits. `SharedHistoryFilesTest` races identical and conflicting immutable publishers and verifies that the winner's bytes are preserved.
- **Branch isolation:** `SharedHistoryCheckoutTest` covers branch bases, restarts, native deletions, failed first visits, owner-reservation failures and two-connection SQLite reconciliation. `SharedReviewHistoryStoreTest.testGitBranchInheritsUnchangedLinesAndRestoresEachBranchesExplicitRevocations` exercises the complete editor/storage path across source changes, clears and coordinator restarts.
- **Projection lifecycle:** service, scheduler and projection-computer suites cover edit ordering, stale delivery, frozen clear commands, cancellation and disposal. Context suites cover adapter replacement, fingerprints, guard boundaries and syntax recovery.
- **Presentation and metadata:** stripe, authors, shortcuts, widget and metadata suites protect rendering, split-editor ownership, navigation and installed plugin identities. CodeGlance has its separate optional suite below.
- **Selection coverage:** `ReviewSelectionCoverageTest` covers weighted totals, recursive/overlapping selections, unsaved text, blanks, excluded/binary/metadata/oversized entries, action availability without an editor and cancellation. `ReviewCoverageHistoryTest` verifies read-only cold pending reads, warm marks, mutation guards and future completion on disposal.

The regular build excludes `ContractCorpusAuditTest`. To run it against a local source corpus, provide the corpus directory explicitly:

```sh
./gradlew -PreviewCorpus="/absolute/path/to/corpus" test --tests dev.doreview.ide.ContractCorpusAuditTest
```

## Refactoring and extension rules

Keep the public facades and serialized state beans stable. Internal helpers belong in the existing packages with package-private visibility unless an external integration requires otherwise. Preserve plugin/action/extension IDs, provider versions, JSON/XML fields, SQLite schema and checkpoint checksums. The [architecture guide](ARCHITECTURE.md) describes the contracts and thread owners.

When changing a subsystem, record the original test results first, run its focused checks after the change, then run the complete build command. Existing failing behavior belongs in [Known issues](KNOWN_ISSUES.md); do not silently fix it or weaken test expectations as part of structural cleanup. Add regression tests for uncovered observable boundaries, rather than for the fact that a facade delegates to a helper.

Core changes must retain the `java.base` boundary and matching order. External line matchers enter through an operation-local workspace function; they must return exact complete-row correspondence and remain outside read actions. Git process sessions belong to one projection and must close on success, failure and cancellation. Do not add a cross-operation mapping cache without including every Git diff configuration input in its identity.

Context extensions must return immutable context maps and keep PSI inside the read phase; Git files bypass them. Storage helpers must not acquire independent connections or workers: transaction boundaries, checkout fences and publication ordering remain owned by their facades. Local checkout metadata must never change portable event identities or resurrect native events removed by a same-branch reset. Presentation changes must preserve EDT ownership, retain valid highlights across metadata-only refreshes and release resources with their existing `Disposable` owner.

Validate compatibility by reading pre-change event/checkpoint fixtures and checking the emitted fields, snapshot hashes and checksums. Preserve explicit revocations and ambiguous origin constraints across save/reload, edits and branch round trips. Do not change formats or bump dependency versions to make a refactor build.

## Performance fixtures

Run the synthetic archive and active-file measurements:

```sh
./gradlew test --tests dev.doreview.core.ReviewHistoryPerformanceTest --tests 'dev.doreview.ide.SharedHistoryRepositoryTest.testLargeArchive*'
```

The archive contains 5,000 events across 500 files sharing one four-byte snapshot. The active file has 80 snapshots, 400 reviews and 25,600 proof rows over 320 source rows; the current text changes one row and appends an unreviewed row. It supplies uniform context, excluding PSI, disk I/O and editor painting.

A Windows/Java 25 sample measured:

| Operation | Cold | Warm |
| --- | ---: | ---: |
| Archive index scan | 4,094.2 ms | 520.8 ms |
| Active-file projection | 115.2 ms | 36.1 ms |
| Active-file checkpoint | 116.0 ms | 67.5 ms |

Appending one archive event took 858.8 ms; constructing the active-file fixture took 161.0 ms. Warm active-file values are medians of three operations with new workspaces. Assertions check output, cache reuse and bounds; timings are observations rather than performance guarantees.

Sampled used-heap deltas were +21.9 MiB for the archive, −81.5 MiB constructing the active-file fixture, +34.0 MiB for its cold pass and +63.0 MiB across three warm passes. These samples include garbage collection and unreclaimed allocations; they measure neither retained memory nor peak memory.

## Optional CodeGlance integration tests

The regular suite covers the standalone stripe. Tests against the actual CodeGlance plugin require a compatible local installation and a separate, filtered run:

```sh
./gradlew -PcodeGlancePluginPath="/path/to/CodeGlancePro" test --tests dev.doreview.ide.ReviewStripeCodeGlanceTest
```

This property adds CodeGlance and `src/codeGlanceTest/java` to the test runtime. Production has no CodeGlance dependency. The bridge uses internal APIs; changes to it should be checked against the installed plugin's minimap geometry.
