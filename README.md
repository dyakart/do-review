# DoReview

Track reviewed lines and share review history with your team through Git.

**Supported IDE:** PyCharm 2026.2 (IntelliJ Platform 262.*).

## Features

- Mark individual lines or selections; blank lines are skipped.
- Share reviews from any team member through the repository's `.doreview` directory.
- Use Git's line diff in Git repositories, including unsaved document changes, with version comparison and deleted-line indicators.
- Keep review history across edits and Undo/Redo. New branches inherit the preceding branch's marks; returning to a branch restores its own history. Explicit clears remain effective in their branch.
- Refresh shared history in the background while keeping still-valid highlights visible.
- Show an optional review stripe aligned with the CodeGlance minimap, or a whole-file overview when CodeGlance is unavailable.

## Usage

Open a source file and mark the lines you have reviewed. DoReview expands selections to complete nonblank lines and keeps the saved text as review evidence. Edit the file normally: the plugin projects that evidence onto the current document instead of rewriting the review to match your edits.

| Shortcut | Action |
| --- | --- |
| `Ctrl+M` | Toggle review for the current line or selection |
| `Alt+M` | Go to the next unreviewed line |
| `Alt+Shift+M` | Go to the previous unreviewed line |

In a Git repository, green marks follow unchanged complete lines outside Git diff hunks. Changing a function header does not invalidate an unchanged body. Added lines remain unreviewed, and changed or deleted reviewed lines have separate markers. Git chooses correspondence between duplicate lines using the repository's diff algorithm. The comparison uses saved reviewed snapshots and the current document, including unsaved text; it is independent of the staging area.

Outside Git, yellow marks require review because syntax context changed or a match is uncertain. Navigation includes yellow lines and skips blanks.

The **DoReview** menu provides version comparison, history management and **Show DoReview Stripe**. Stripe visibility is saved per project and is off by default.

Use **Clear Review in Selection** or **Clear All Reviews in File** to revoke existing evidence. Returning to an older version of the text does not undo an explicit clear; marking it again creates a new review. Review coverage counts nonblank lines only.

Version comparison always opens the saved reviewed snapshot. A matching Git commit's hash and subject identify versions whose full file text matches after line-ending normalization; review dates and reviewers remain separate. Other versions are labelled **DoReview snapshot**, including imported or unsaved text without a matching Git revision.

The stripe fills blank and whitespace-only rows with green because they need no review. Hovering shows `DoReview — blank line, no review required`. This fill applies only to the stripe; blank rows remain excluded from editor highlighting, review history and coverage. Unreviewed code takes priority over blank fill when rows share a minimap pixel.

Outside Git, Python, Tact and Tolk use their language plugins for structural context; other languages use a conservative whole-file fingerprint. Git repositories require `git` on the IDE's `PATH`. Review marks do not establish program correctness.

## Installation

Build the plugin with Java 25 and the included Gradle wrapper:

```sh
./gradlew check buildPlugin verifyPluginStructure
```

On Windows, build using an installed PyCharm 2026.2:

```powershell
.\build.ps1
```

In PyCharm, open **Settings → Plugins → ⚙ → Install Plugin from Disk…**, select the ZIP in `build/distributions/` and restart the IDE.

The Gradle build downloads the pinned IDE and language plugins when local installations are not supplied. See [Development](docs/DEVELOPMENT.md) for local IDE paths, platform requirements and optional integration tests.

## Shared history

After the first review, DoReview writes immutable JSON events and deduplicated full source snapshots to `.doreview/v1/` at the Git working-tree root, or project directory without Git. Include these files in normal commits; DoReview does not stage, commit or push. Valid reviews from any author contribute to shared coverage. Authors use Git name/email configuration, falling back to the operating-system username.

When this worktree first visits a branch, DoReview remembers the preceding branch's active review events as a local starting point and maps them onto the new source through Git. Later marks and clears belong to the current branch. Returning to an already visited branch restores its saved starting point plus its own events and any history deliberately committed there. Untracked event files carried by Git from another branch do not transfer those later local operations. Detached checkouts are identified by commit; linked worktrees keep independent local state.

Visiting a branch or refreshing a projection creates no portable review event. An explicit mark or clear publishes its changes and any missing inherited parents needed to make the event self-contained for teammates. The local starting point is held in SQLite, so preserve the database for branch history that has not yet been published.

A typical team workflow is:

1. Review and mark source lines in the IDE.
2. Include the generated `.doreview` files in your normal Git commit alongside the source changes.
3. Share and update the repository through your usual Git workflow. DoReview loads the checked-out events and recomputes coverage against each file's current text.

Shared snapshots contain the full reviewed source text. Keep them within the same repository access boundary as the source itself. A nested Git repository or submodule owns its own history. Publication requires filesystem hard-link support so concurrent writers cannot replace immutable files; unsupported filesystems retain pending work locally and report a storage error.

A local SQLite database indexes each working tree independently of the IDE version. On Windows: `%LOCALAPPDATA%/DoReview/worktrees/<root-hash>/history.sqlite3`. It also holds pending provenance until publication to `.doreview`; preserve the database when backing up unpublished work. Database files stay outside Git.

Existing XML history is imported automatically as **Imported history**, with an XML backup retained. See [Architecture](docs/ARCHITECTURE.md) for storage details.

Use **DoReview → Rebuild Review Index** to export pending work, back up SQLite and rebuild its index. Retained work from another checkout must be published there first. Restore missing or damaged `.doreview` files from Git before rebuilding.

## Development

See [Development](docs/DEVELOPMENT.md) for build and test commands, [Architecture](docs/ARCHITECTURE.md) for component boundaries and data contracts, and [Known issues](docs/KNOWN_ISSUES.md) for recorded defects and verification limits.

## License

[MIT](LICENSE). Bundled dependencies retain their [third-party licenses](docs/THIRD_PARTY_LICENSES.md).
