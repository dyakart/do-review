# DoReview

Persistent, personal code review tracking for PyCharm.

**Supported IDE:** PyCharm 2026.2 (IntelliJ Platform 262.*).

## Features

- Mark individual lines or selections; blank lines are skipped.
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

Structural context tracking supports Python, Tact and Tolk through their language plugins. Other languages use a whole-file fingerprint, so edits require rechecking unchanged reviewed lines. Marks track text and syntax context; they do not establish program correctness.

## Installation

On Windows, build using an installed PyCharm 2026.2:

```powershell
.\build.ps1
```

In PyCharm, open **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select the ZIP in `build/distributions/`.

## Storage

History, including full source snapshots, is stored locally in the IDE configuration directory as `review-marks-history.xml`, outside the project repository. Settings Sync is disabled for this history, and DoReview does not send source code over the network.

## Development

See [Development](docs/DEVELOPMENT.md) for build and test commands and [Architecture](docs/ARCHITECTURE.md) for component boundaries and data contracts.

## License

[MIT](LICENSE)
