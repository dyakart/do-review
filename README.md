# DoReview

Track reviewed lines and share review history with your team through Git.

**Supported IDE:** PyCharm 2026.2 (IntelliJ Platform 262.*).

## Features

- Mark individual lines or selections; blank lines are skipped.
- Share reviews from any team member through the repository's `.doreview` directory.
- Track changes to reviewed text and its syntax context, with version comparison and deleted-line indicators.
- Keep review history across edits, Undo/Redo and Git branch changes. Exact, unambiguous matches restore marks; explicitly cleared marks stay cleared.
- Show an optional review stripe aligned with the CodeGlance minimap, or a whole-file overview when CodeGlance is unavailable.

## Usage

| Shortcut | Action |
| --- | --- |
| `Ctrl+M` | Toggle review for the current line or selection |
| `Alt+M` | Go to the next unreviewed line |
| `Alt+Shift+M` | Go to the previous unreviewed line |

Green marks indicate reviewed text with matching context. Yellow marks require review because context changed or the match is uncertain. Changed text has a separate marker. Navigation includes yellow lines and skips blanks.

The **DoReview** menu provides version comparison, history management and **Show DoReview Stripe**. Stripe visibility is saved per project and is off by default.

The stripe fills blank and whitespace-only rows with green because they need no review. Hovering shows `DoReview — blank line, no review required`. This fill applies only to the stripe; blank rows remain excluded from editor highlighting, review history and coverage. Unreviewed code takes priority over blank fill when rows share a minimap pixel.

Python, Tact and Tolk use their language plugins for structural context. Other languages use a conservative whole-file fingerprint. Marks track text and syntax context; they do not establish program correctness.

## Installation

On Windows, build using an installed PyCharm 2026.2:

```powershell
.\build.ps1
```

In PyCharm, open **Settings → Plugins → ⚙ → Install Plugin from Disk…**, select the ZIP in `build/distributions/` and restart the IDE.

## Shared history

After the first review, DoReview writes immutable JSON events and deduplicated full source snapshots to `.doreview/v1/` at the Git working-tree root, or project directory without Git. Include these files in normal commits; DoReview does not stage, commit or push. Valid reviews from any author contribute to shared coverage. Each checkout uses its own working-tree history. Authors use Git name/email configuration, falling back to the operating-system username.

A local SQLite database indexes each working tree independently of the IDE version. On Windows: `%LOCALAPPDATA%/DoReview/worktrees/<root-hash>/history.sqlite3`. It also holds pending provenance until publication to `.doreview`; preserve the database when backing up unpublished work. Database files stay outside Git.

Existing XML history is imported automatically as **Imported history**, with an XML backup retained. See [Architecture](docs/ARCHITECTURE.md) for storage details.

Use **DoReview → Rebuild Review Index** to export pending work, back up SQLite and rebuild its index. Retained work from another checkout must be published there first. Restore missing or damaged `.doreview` files from Git before rebuilding.

## Development

See [Development](docs/DEVELOPMENT.md) for build and test commands and [Architecture](docs/ARCHITECTURE.md) for component boundaries and data contracts.

## License

[MIT](LICENSE). Bundled dependencies retain their [third-party licenses](docs/THIRD_PARTY_LICENSES.md).
