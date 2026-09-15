# Open Folder button bypasses the playlist model

**Status:** Done
**Link:** docs/revisit.md ("FolderTreePanel's \"Open Folder\" button bypasses the playlist model")
**Updated:** 2026-09-15

## Report

Noted in `docs/revisit.md` while manually verifying `docs/issues/2026-09-15-playlists-and-queue.md`
Part 4 (the playlist tab strip): `FolderTreePanel`'s header has an "Open Folder" icon button that
still opens a folder the old, pre-playlist way, alongside the tab strip's own `+` button which is
the new, playlist-aware way to add a folder. No user-facing symptom was filed as a bug report — this
is an internal consistency finding from the previous implementation session, not a report from
outside. There is no environment/repro-steps gap to note, since the code path is fully visible.

## Findings

- `FolderTreePanel`'s `HeaderRow` always renders a `CreateNewFolder` icon button wired to
  `onOpenFolderClick` (`composeApp/src/jvmMain/kotlin/com/grappim/mukk/ui/FolderTreePanel.kt:159-166`).
- `MainLayout` renders `PlaylistTabBar` (with its own `+` → `onCreatePlaylist`) and `FolderTreePanel`
  (with `onOpenFolderClick`) side by side in the same screen, at the same time — neither is
  conditional on the other's absence (`MainLayout.kt:72-91`, `PlaylistTabBar` wired at line 76,
  `FolderTreePanel` at line 91). Both open-folder affordances are simultaneously visible.
- `onOpenFolderClick` in `App.kt:102-109` calls `pickDirectoryNative()` then
  `viewModel.scanDirectory(path)`. `onCreatePlaylist` in `App.kt:122-129` calls the same
  `pickDirectoryNative()` then `viewModel.createPlaylist(path)` — same picker, different
  ViewModel entry point.
- `scanDirectory()` (`MukkViewModel.kt:112-133`) scans the folder, then sets `_folderTreeState`
  directly to a fresh `FolderTreeState(rootPath = path, ...)`. It never touches `_activePlaylistId`
  or `PlaylistRepository`, so no `Playlists` row is created and no tab is added to the strip.
- `createPlaylist()` (`MukkViewModel.kt:493-499`) calls `playlistRepository.create(...)`, refreshes
  `_playlists`, then calls the shared `activatePlaylist()` (`MukkViewModel.kt:548-572`), which is
  the single place that sets `_activePlaylistId`, persists `preferencesManager.playlistActiveId`,
  and sets `_folderTreeState`. `selectPlaylist()` and the post-delete "select a neighbor" path both
  also go through `activatePlaylist()` — `scanDirectory()` is the only folder-opening path in the
  ViewModel that does not.
- `restoreActivePlaylist()` (`MukkViewModel.kt:464-...`) on startup reads
  `preferencesManager.playlistActiveId` and looks the id up in `playlistRepository.getAll()`. A
  folder opened via `scanDirectory()` has no row there, so it cannot be the one restored — confirms
  the "not remembered as a playlist across restarts" part of the original finding.

## Root cause

`FolderTreePanel`'s "Open Folder" button and `MukkViewModel.scanDirectory()`
(`MukkViewModel.kt:112-133`) predate the playlist model added in
`docs/issues/2026-09-15-playlists-and-queue.md`. That work added a second, playlist-aware
open-folder path (`createPlaylist()` → `activatePlaylist()`) reachable from the new tab strip's `+`
button, but did not remove or reroute the older path, so both now coexist and diverge: one creates
a `Playlists` row and an active tab, the other silently mutates `_folderTreeState` in place.

## Impact

- Low frequency in normal use, since the discoverable, prominent way to open a folder is now the
  tab strip's `+` (first thing in the layout, previously the only affordance). The header icon is
  a secondary control most users won't reach for once tabs exist.
- When it is used: the visible folder tree and track list change, but no tab reflects it, so the
  UI shows content with no highlighted tab — a state a user could plausibly read as a rendering bug.
- The opened folder is not persisted as a `Playlists` row, so on restart `restoreActivePlaylist()`
  falls back to whatever the saved `playlistActiveId` last was (or the fallback path if that's also
  gone) — the folder opened this way is silently lost on relaunch.
- No data corruption or crash; purely a UI/state consistency gap. No known workaround needed since
  the tab strip's `+` does the same job correctly.

## Open questions

None blocking a decision — the code path is fully traced and both options below are mechanically
straightforward.

## Options

### Option A — Remove the "Open Folder" button from `FolderTreePanel`

Delete the `IconButton`/`onOpenFolderClick` wiring from `HeaderRow`
(`FolderTreePanel.kt:159-166`), the `onOpenFolderClick` parameter threaded through
`FolderTreePanel` → `MainLayout` → `App.kt`, and `scanDirectory()` from `MukkViewModel.kt`
(unused after removal).

- **Pros:** Removes the inconsistent path entirely rather than reconciling it; simplest diff;
  `scanDirectory()`'s duplicate scan-then-set-state logic goes away, leaving `activatePlaylist()`
  as the single folder-opening path (matches the file's existing "avoid two ways to do the same
  thing" pattern, e.g. `TrackRepository` being the only Exposed entry point).
- **Cons:** A real functionality removal, not just a fix — a user who has muscle memory for the
  header icon loses it in favor of the tab strip's `+`, which sits in a different, less
  space-constrained place in the layout. If `FolderTreePanel` is ever reused in a context without
  the tab strip, this option removes its only way to open a folder.
- **Risk / blast radius:** Small — one composable's header loses one icon, one ViewModel method
  and its two call sites (`App.kt`, `MainLayout.kt` parameter threading) are deleted. No DB schema
  or persisted-preference change.

### Option B — Reroute the button through `onCreatePlaylist`

Change `App.kt:102-109`'s `onOpenFolderClick` lambda to call `viewModel.createPlaylist(path)`
instead of `viewModel.scanDirectory(path)` (or simplify further by passing the same
`onCreatePlaylist` lambda into both `FolderTreePanel` and `PlaylistTabBar` from `MainLayout`).
Delete `scanDirectory()` from `MukkViewModel.kt` once nothing calls it.

- **Pros:** Keeps the header icon as a second, familiar affordance for the same action, now
  participating correctly in the playlist model — no visible functionality loss for a user who
  prefers that button.
- **Cons:** Two buttons that do the exact same thing, in two different parts of the layout, is
  itself a minor consistency smell (which one does a new user reach for?) — trades a correctness
  bug for a redundancy. `docs/revisit.md`'s own suggested resolution list this as the alternative to
  removal, on the premise that "the tab strip's `+` now covers folder-adding" — implying redundancy,
  not need.
- **Risk / blast radius:** Same size as Option A structurally (one call site changed, one dead
  method removed), but leaves more surface area (two buttons) for a future session to have to
  reconcile again.

## Decision

**Recommendation: Option A** — remove the button. The tab strip's `+` fully covers folder-adding
now; keeping a second control for the identical action is the kind of redundancy the project's
"Simplicity First" guidance (`CLAUDE.md`) argues against, and Option A is also the smaller
long-term surface (one open-folder path instead of two draws that must stay in sync forever).

**Decided 2026-09-15 (user): Option A** — remove the header's "Open Folder" button and
`scanDirectory()`; the playlist tab strip's `+` remains the only way to open a folder.

## Implementation plan

### Part 1 — Remove the header button and `scanDirectory()` [x]

- Remove the `IconButton`/`onOpenFolderClick` wiring from `HeaderRow`
  (`FolderTreePanel.kt:159-166`) and the `onOpenFolderClick` parameter from `FolderTreePanel`
  and `HeaderRow`.
- Remove the `onOpenFolderClick` parameter and its argument from `MainLayout.kt`.
- Remove the `onOpenFolderClick` lambda and parameter from `App.kt:102-109`.
- Remove `scanDirectory()` from `MukkViewModel.kt:112-133` (no longer called anywhere).
- **Verify:** `./gradlew composeApp:jvmMainClasses` compiles clean.

## What landed

Removed the header "Open Folder" button (`FolderTreePanel.kt`'s `HeaderRow`), the
`onOpenFolderClick` parameter threaded through `FolderTreePanel` → `MainLayout` → `App.kt`, and
`MukkViewModel.scanDirectory()`. The playlist tab strip's `+` button (`createPlaylist()` →
`activatePlaylist()`) is now the only way to open a folder. `./gradlew composeApp:jvmMainClasses`
compiles clean. Nothing deliberately left out — this was the whole Part 1.
