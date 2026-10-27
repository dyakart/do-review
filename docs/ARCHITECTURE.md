# Architecture

DoReview targets PyCharm 2026.2 (platform 262). It shares review evidence through Git and projects it onto current documents. Green confirms matching text, origin and tracked syntax context; it does not establish program correctness.

## Components

| Component | Responsibility |
| --- | --- |
| `core` | Immutable snapshots, evidence, revocations, row identity and matching. Depends only on `java.base`; `compileCoreIsolation` enforces this boundary. |
| `DoReviewService` | Project commands, document/VFS events and projection coordination. |
| `ReviewProjectionScheduler`, `ReviewProjectionComputer` | Cancellable context capture, background matching and guarded delivery. |
| `ContextAnalysisManager`, `ContextProvider` | Context caches and optional language adapters. |
| `ReviewHistoryStore`, `SharedReviewHistoryStore`, `LegacyReviewHistoryModel` | Facade, serial persistence coordinator and legacy compatibility model. |
| `SharedHistoryRepository`, `ReviewRepositoryPaths` | Portable events, SQLite storage, checkout reconciliation and filesystem identity. |
| `ReviewEditorPresentation`, stripe classes | Editor resources and rendering from immutable `FileProjection` values. |
| `ReviewHistoryActions`, `ReviewGitHistory` | Version comparison, exact Git content labels, authors and explicit history attachment. |

## Review model

`ReviewHistory` contains content-addressed `FileSnapshot` values, explicit `ReviewEvidence` and targeted `Revocation` records. Edits change the projection, not saved evidence. Returning to old text cannot override an explicit revocation; a later review creates new evidence.

Green requires exact text, unambiguous origin and matching context. Local edit hints preserve original-row identity, so inserting an identical copy does not review the copy. Ambiguous external changes remain unconfirmed. Blank rows are excluded from marking, coverage and deletion markers. Projection work shares a four-million-cell quadratic diff budget; unresolved matches remain unconfirmed when it is exhausted.

## Execution and concurrency

`DoReviewService` and presentation run on the event dispatch thread (EDT). Context capture uses a cancellable non-blocking read action and returns immutable maps; PSI never leaves that phase. Matching and checkpoint preparation run outside the read lock in a separate pooled task. Projection jobs replace older document jobs; frozen revocations survive view refreshes within their checkout.

Delivery checks document identity, modification stamp, task generation, history revision, storage epoch and context-provider generation. `beforeDocumentChange` synchronously clears confirmed highlighting and stripe snapshots before range markers can expand over inserted text.

Persistence uses an application-owned serial worker. Initial loading, filesystem scans, database work and cold decoding run off EDT; UI consumers use warmed immutable models. Queued writes carry frozen state. Cancellation or failure must not publish stale green marks.

## Shared artifacts and local storage

The nearest ancestor Git working-tree root owns `.doreview`; a nested repository or submodule is a separate boundary. Without Git, the project base directory is the root. Portable paths are relative to that root.

Each operation publishes an immutable JSON batch at `.doreview/v1/events/<prefix>/<uuid>.json`. Events carry author identity, evidence, revocations, aliases, checkpoint constraints and causal parents. LF-normalized UTF-8 snapshots are deduplicated at `.doreview/v1/snapshots/<prefix>/<sha256>.txt`. Snapshot hashes are validated, and `.doreview/.gitattributes` prevents line-ending conversion. Review authors come from Git name/email configuration, falling back to the operating-system username. Any author's valid evidence contributes to coverage.

Git sharing uses ordinary user commits and pushes. DoReview does not stage files, commit or contact a remote. Rescans reconcile additions and removals: each checkout uses its working-tree event set, not the accumulated union of previously visited branches.

SQLite stores the local index, compressed snapshot copies and pending provenance. On Windows its path is `%LOCALAPPDATA%/DoReview/worktrees/<canonical-root-SHA256>/history.sqlite3`; macOS uses `~/Library/Application Support/DoReview`, and Linux uses `$XDG_DATA_HOME/DoReview` or `~/.local/share/DoReview` as the data root. `doreview.data.path` overrides that root for isolated tests. Worktree keys resolve real paths where possible and fold case on Windows.

The database uses schema 1, WAL, full synchronization and a bounded busy timeout. Pending provenance is checkout-bound and durable until successfully published; the database must not be treated as a disposable cache while pending work exists. Database files stay outside Git. `SharedHistoryRepository.backup` uses SQLite's backup API to include committed WAL contents consistently.

Existing XML history is imported in the background as **Imported history**, preserving an XML backup in the IDE configuration's `review-marks-legacy-backups` directory. Imported checkpoints merge independently with current provenance; validation preserves origin denials and never creates review evidence.

**Rebuild Review Index** first publishes pending work for the active checkout, refuses to discard retained work from other checkouts or missing causal parents, and creates a consistent `history-before-rebuild-*.sqlite3` backup beside the database. It then reconstructs derived tables from `.doreview`. Missing or corrupted shared artifacts must be restored from Git first; rebuilding cannot repair the portable source. Unsupported formats are preserved without being overwritten. Successful legacy migration is recorded per project so future starts do not deserialize the global XML archive again.

Preserve plugin ID `dev.reviewmarks`, action IDs `ReviewMarks.*`, widget ID `ReviewMarks.Coverage`, extension point `dev.reviewmarks.contextProvider` and color keys `REVIEW_MARKS_REVIEWED` / `REVIEW_MARKS_NEEDS_REVIEW`. Stripe visibility remains separate project state in `workspace.xml`, defaulting to false.

## Version comparison

Reviewed snapshots remain the authoritative comparison baseline. The chooser can label a snapshot with a Git source commit's hash and subject only after its full file content matches the snapshot's SHA-256 after LF normalization. Review date and reviewer are displayed separately; a matching source commit does not identify when the review was created or imply that the entire commit was reviewed.

Git lookup runs off EDT with bounded history, output and execution time. It follows the current checkout's file history, including renames, and performs no Git writes. Versions without a match retain an explicit **DoReview snapshot** label; no match does not establish that the text was never committed. Imported, unsaved and non-Git snapshots remain available without changing the history schema, and snapshot text is loaded only when selected for comparison.

## Language context

Optional Python, Tact and Tolk adapters fingerprint declarations, control-flow headers, branch roles and preceding guards or early exits. Unsupported languages use a whole-file fingerprint. Provider failures and uncertain parse regions produce unknown context.

Tact assembly fingerprints include the header and complete body. Recovery requires verified declaration boundaries. `TactSyntaxRecovery` performs one bounded parse of an in-memory masked copy with lexer-checked boundaries; it never repairs source. Analysis and caches are bounded; provider changes invalidate them and projections through a generation counter.

## Editor lifecycle and stripe

Presentation owns highlighters, shortcuts, listeners and panels through `Disposable`. Logical stripe snapshots are prepared asynchronously from immutable document text and shared per document and modification stamp; geometry belongs to each editor, including splits. Delivery checks the active task and document stamp. Replaced work, document edits, stripe disablement and disposal cancel pending tasks. Painting reads cached snapshots without rescanning document text, history or PSI.

The stripe is 14 logical pixels wide. Blank and whitespace-only rows use the reviewed green color with a separate `DoReview — blank line, no review required` tooltip. This display-only fill creates no review evidence and does not change editor highlighting, coverage or navigation. Raster priority is deletion, change, recheck, reviewed, unreviewed code, blank. Unreviewed code masks blank fill when rows share a pixel. `ReviewStripeHost` isolates SDK-specific attachment through `TextEditorComponent.__add`, skips unsupported hosts and handles delayed attachment.

`ReviewStripeCodeGlanceBridge` reads a live minimap through guarded internal reflection, including private `minimap` access. Native row corrections, crop position and scale align marks with CodeGlance's Git markers. The bridge never reparents CodeGlance components or retains its classloader through strong caches. Unavailable geometry falls back to a whole-file overview. Click/drag navigates through the matching inverse transform; wheel events go to the editor. A 100 ms timer follows asynchronous minimap geometry while showing and stops on disposal.
