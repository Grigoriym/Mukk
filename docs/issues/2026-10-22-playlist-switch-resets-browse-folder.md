# 2026-10-22 — Switching playlists resets the browsed folder to the playlist root

**Status:** In progress
**Link:** none (reported in chat)   **Updated:** 2026-10-22

## Report
The owner first reported this in chat on 2026-10-22 as "Select one playlist then choose another,
go back, the list is duplicated." They found the cause while answering a follow-up question:

> when we change playlists, the selector goes to the folder root, e.g. if there are several
> albums, it will show all of them, as if it is duplicated. [...] Not sure what to fix

So the symptom is this. Browse album X inside playlist P, switch to playlist Q, then switch back
to P. The track list now shows every album under P's root, not album X. That looks like a
duplicated list, but no track appears twice.

## Findings
1. **No real duplicates exist.** In the owner's `~/.local/share/mukk/library.db` (read-only
   query), `count(*)` and `count(distinct file_path)` in `media_tracks` are both 146791. The
   track list is a `LazyColumn` keyed by `file.absolutePath` (`TrackListPanel.kt:94`), so a real
   duplicate would crash the app, not render twice.
2. **A tab switch always browses the playlist root.** `selectPlaylist()` calls
   `activatePlaylist(playlist, browsePath = playlist.folderPath, expandedPaths =
   setOf(playlist.folderPath))` (`MukkViewModel.kt:538-541`). `activatePlaylist()` then loads
   `trackRepository.findByPathPrefix(browsePath)` (`MukkViewModel.kt:614`, `:622`), which is
   every track under the root, recursively.
3. **The previous position is not kept anywhere.** `activatePlaylist()` overwrites
   `_folderTreeState` and saves it to `folderTree.selectedPath` / `folderTree.expandedPaths`
   (`MukkViewModel.kt:601-606`). These preference keys hold one state for the active playlist
   only. Nothing stores a browse position per playlist.
4. **App startup already restores the position, but only for the active playlist.**
   `restoreActivePlaylist()` passes the saved `folderTreeSelectedPath` and
   `folderTreeExpandedPaths` (`MukkViewModel.kt:524-534`). So "reopen where I was" is already
   the expected behavior for a restart, and a tab switch does not follow it.
5. **The root browse was the planned behavior, not an accident.** The playlist design
   (`docs/issues/2026-09-15-playlists-and-queue.md`, Part 3) switches to the playlist's folder.
   It does not mention keeping a per-playlist position. So this is a missing feature, not a
   regression.

## Root cause
`selectPlaylist()` (`MukkViewModel.kt:538-541`) always passes the playlist root as `browsePath`
and only the root as `expandedPaths`, and no per-playlist browse position exists to pass
instead. Going back to a playlist therefore shows all its albums at once.

## Impact
Everyone who uses more than one playlist and browses below the root. It costs a re-navigation
after each switch, and the full list looks like duplicates. The workaround is to click the album
in the folder tree again.

## Open questions
1. If the remembered folder was deleted or renamed meanwhile, fall back to the root? Proposed:
   yes, the same `File(it).isDirectory` check that `restoreActivePlaylist()` uses.
2. Should the remembered position survive a restart for inactive playlists too? This decides
   between Option B and Option C.

## Options
**A. Do nothing / working as intended.** Root browse is a valid "overview" design.
- Pros: no change.
- Cons: the owner reports it as confusing. Startup already restores the position (Finding 4),
  so a tab switch behaves differently from a restart.

**B. Remember the browse position per playlist in memory (recommended).**
- Changes: a `MutableMap<Long, FolderTreeState>` in `MukkViewModel`. Before `activatePlaylist()`
  switches away, store the current `_folderTreeState` under the old playlist id.
  `selectPlaylist()` reads the stored state, checks that its folders still exist, and falls
  back to the root.
- Pros: small, ViewModel-only change. No schema change. The active playlist already survives
  a restart through the existing preference keys.
- Cons: after a restart, inactive playlists open at the root again. Deleted playlists must be
  removed from the map in `deletePlaylist()`.
- Risk / blast radius: the switch path, which earlier had the OOM and lag fixes
  (`docs/issues/2026-09-15-playlist-switch-oom.md`). The change only picks a different
  `browsePath`, and `findByPathPrefix` on a sub-folder loads fewer rows, not more.

**C. Persist the browse position per playlist.** Add `selected_path` and `expanded_paths`
columns to `PlaylistsTable`, and write them on every folder selection.
- Pros: positions survive a restart for every playlist.
- Cons: it needs a schema migration for existing databases. It adds a DB write per folder
  click, and two sources of truth for the active playlist (these columns and the
  `folderTree.*` preferences). That is more code and more risk for a small gain.

Recommendation: **B**. It fixes the reported flow (switch away, come back). Option C can come later
if losing the position on restart turns out to matter.

## Decision
Option C, approved by the owner in chat on 2026-10-22: "we can do db changes, since only I use
it, if it helps to ease the usage." Open question 1: fall back to the root when the saved folder
is gone (the proposed default). Open question 2: yes, positions survive a restart.

## Implementation plan

### Part 1 — Store a browse position per playlist [x]
- `PlaylistsTable`: add `selected_path` (varchar 1024, default `""`) and `expanded_paths`
  (text, default `""`, `|`-delimited, the same format as `folderTree.expandedPaths`).
- `DatabaseInit`: after `SchemaUtils.create`, run `SchemaUtils.addMissingColumnsStatements(
  PlaylistsTable)` so an existing `library.db` gets the new columns. This function is not
  deprecated in Exposed 1.0.0; `createMissingTablesAndColumns` is.
- `core:model`: new `PlaylistBrowseState(selectedPath, expandedPaths)`.
- `PlaylistRepository`: `getBrowseStates(): Map<Long, PlaylistBrowseState>` (only rows with a
  saved position) and `saveBrowseState(id, state)`.
- **Verify:** `./gradlew :core:data:jvmTest`, with new tests in `PlaylistRepositoryTest`:
  save/read round trip, and a database created with the old `playlists` schema gains the
  columns and keeps its rows.
- **Landed:** as planned. 7/7 tests pass. With the migration line commented out, the old-schema
  test fails (1 of 7), so it does exercise the migration.

### Part 2 — Restore the position on a playlist switch [ ]
- `MukkViewModel`: keep an in-memory `Map<Long, PlaylistBrowseState>`, loaded in
  `restoreActivePlaylist()`. `saveFolderTreeState()` updates the active playlist's entry and
  writes it through `saveBrowseState`. `selectPlaylist()` uses the saved state, keeping only
  folders that still exist under the playlist root. Without one it falls back to the root.
  `deletePlaylist()` removes the entry.
- **Verify:** `./gradlew composeApp:jvmMainClasses detekt`, plus a `sqlite3` query on the
  `playlists` row after browsing. **Manual** (the owner): browse an album in playlist P, switch
  to Q, switch back. Expected: the same album is selected and the same folders are expanded,
  also after a restart.
