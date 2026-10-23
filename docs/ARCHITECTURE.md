# Architecture

DoReview is a local IntelliJ Platform plugin for PyCharm 2026.2 (platform 262). It tracks explicit review evidence and projects that evidence onto the current document. A green mark confirms matching text, origin and tracked syntax context; it does not establish semantic equivalence or analyze dependencies between functions.

## Components

Production code lives under `src/main/java/dev/doreview`.

| Component | Responsibility |
| --- | --- |
| `core` | Immutable snapshots, evidence, revocations, row identity and matching. Depends only on `java.base`; `compileCoreIsolation` enforces this boundary. |
| `DoReviewService` | Project session, commands, document/VFS events and projection coordination. |
| `ReviewProjectionScheduler`, `ReviewProjectionComputer` | Cancellable context capture, background matching and guarded delivery. |
| `ContextAnalysisManager`, `ContextProvider` | Cached context maps and optional language adapters. |
| `ReviewHistoryStore` and codecs | Application persistence, validation, migration and checkpoint commits. |
| `ReviewEditorPresentation`, stripe classes | Editor resources and rendering from immutable `FileProjection` values. |
| `ReviewHistoryActions` | Reviewed-version selection, native diff and explicit history attachment. |

## Review model

`ReviewHistory` holds content-addressed `FileSnapshot` values, explicit `ReviewEvidence` and `Revocation` records. Edits change the projection, not the saved evidence. A revocation targets particular evidence and rows; returning to old text must not override it. A later explicit review creates fresh evidence.

`FileProjection` is the shared interpretation used by highlighting, coverage and navigation. Matching requires exact text and unambiguous origin before context can confirm a review. Local edit hints preserve original-row identity, so inserting an identical copy does not review the copy. Ambiguous external changes remain unconfirmed. Checkpoints preserve these constraints across editor closure and restart; hints cannot create evidence.

Blank rows are excluded from marking, coverage and deletion markers. Projection work shares a budget of four million quadratic diff cells. Exhaustion leaves unresolved matches unconfirmed rather than guessing; this is not a wall-clock limit.

## Execution and concurrency

The project service and editor presentation run on the event dispatch thread (EDT). The scheduler replaces previous projection work for a document; frozen revocation commands run independently so ordinary refreshes cannot supersede them.

`ReviewProjectionComputer.capture` reads PSI under a cancellable non-blocking read action and returns immutable context maps. PSI objects never leave this phase. Matching and checkpoint preparation run in a separate pooled task outside the read lock, with independent cancellation indicators.

Before applying a result on EDT, the service checks document identity, modification stamp, task generation, history revision, storage reload epoch and context-provider generation. A checkpoint commit additionally requires the same immutable history instance used by the worker. Cancellation or failure cannot publish confirmed stale marks.

`beforeDocumentChange` synchronously clears confirmed highlighting and stripe snapshots before editor range markers can expand over inserted text. New marks appear only after the applicable projection is available.

## Persistence and compatibility

`ReviewHistoryStore` persists schema 2 in the application configuration file `review-marks-history.xml`, keyed by canonical project path. Branches in one checkout share history; separate project roots and worktrees have separate keys. Settings Sync is disabled for this storage. Source text is stored literally: path macro substitution is disabled, including strings such as `$USER_HOME$`.

`ReviewJournalCodec` validates immutable domain values; `ReviewHistoryStateCopies` detaches persistence DTOs. `ProjectionCheckpointCodec` writes checksum-protected checkpoint v2 and reads v1. Checkpoints are derived identity constraints, not review evidence. Schema 1 recovery validates hashes; unknown journal schemas remain read-only. Legacy `.idea/review-marks.xml` is imported once from saved text with a backup in `review-marks-legacy-backups`.

Preserve plugin ID `dev.reviewmarks`, action IDs `ReviewMarks.*`, widget ID `ReviewMarks.Coverage`, extension point `dev.reviewmarks.contextProvider` and color keys `REVIEW_MARKS_REVIEWED` / `REVIEW_MARKS_NEEDS_REVIEW`. Stripe visibility is separate project state: `DoReviewStripeSettings.showReviewStripe` in `workspace.xml`, defaulting to false.

## Language context

Python, Tact and Tolk adapters load through optional plugin descriptors. They fingerprint enclosing declarations, control-flow headers, branch roles and preceding guards or early exits. Unsupported languages use a whole-file fingerprint, so any file change invalidates their context. Provider failures and uncertain parse regions produce unknown context.

Tact assembly declarations include both header and complete body in their fingerprint: changing any instruction requires rechecking that declaration. Parse recovery can confirm independently closed declarations only with verified boundaries. `TactSyntaxRecovery` uses lexer-checked boundaries and one bounded parse of an in-memory masked copy; it never repairs the source document. Historical context analysis and caches are bounded. Provider changes invalidate caches and projections through a generation counter.

## Editor and stripe lifecycle

Presentation owns only its own highlighters, shortcuts, listeners and panels through `Disposable`. Logical stripe snapshots are shared per document and modification stamp; geometry belongs to each editor, including split editors. Painting reads cached snapshots, never history or PSI. Closing an editor or disabling the stripe releases its resources.

The stripe is 14 logical pixels wide. Raster overlap priority is deletion, change, recheck, reviewed. `ReviewStripeHost` isolates SDK-specific attachment through `TextEditorComponent.__add`; it skips unsupported hosts and occupied columns. A hierarchy listener handles editors whose host appears later.

`ReviewStripeCodeGlanceBridge` optionally reads a live CodeGlance minimap through guarded reflection, including private `minimap` access. This is an internal compatibility bridge, not a public CodeGlance API. It uses native row corrections, crop position and scale to align with Git markers, without reparenting CodeGlance components or retaining its classloader through strong caches. Unavailable geometry falls back to a whole-file overview. Click/drag uses the matching inverse transform and scrolls to the selected code; wheel events go to the editor. A 100 ms Swing timer follows asynchronous minimap geometry only while the stripe is showing and stops on disposal.
