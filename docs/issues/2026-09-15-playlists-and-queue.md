# 2026-09-15 — Folder-linked playlists

**Status:** Done — Part 4 landed
**Link:** `docs/usability-gaps.md` ("No playlists or play queue")   **Updated:** 2026-09-15

## Report

This isn't a bug report — it's usability gap #3 from a self-directed audit
(`docs/usability-gaps.md`), comparing Mukk against other desktop players. The original
finding: playback only ever cycles within whichever folder is currently selected; there's no
way to save a curated collection or switch between multiple "libraries."

The initial finding didn't say what "playlist" should mean, so that was clarified directly
with the user before any design work:

> What I want from playlists is to create a playlist, link it with a folder, and it will
> stay. If I need a new folder, I just create a new playlist and link that folder there. So
> each playlist is a list of songs that are there [in the linked folder].

Follow-up confirmed two things: the folder link is **recursive** (subfolders included, same
as today's browsing) and **live** (the playlist reflects the folder's current contents, not a
snapshot taken at link time).

So a "playlist," in this app, is a **named, saved link to a folder** — not a hand-picked,
arbitrarily-ordered list of individual songs like most other players' playlists.

## Findings

1. **Playback sequencing is folder-scoped today.** `nextTrack()`/`previousTrack()`
   (`MukkViewModel.kt:237-299`) index into `_selectedFolderEntries`
   (`MukkViewModel.kt:40`, populated by `loadSelectedFolderEntries()` at
   `MukkViewModel.kt:522-548`), which walks *whichever folder is currently selected*, using
   `walkTopDown()` — i.e. already recursive. This is exactly the machinery a folder-linked
   playlist needs; nothing new has to be built for "list the songs in this folder,
   recursively."

2. **The app already supports exactly one saved root, and this request is "make that N,
   named, and switchable."** `FolderTreeState.rootPath` (`core/model/.../FolderTreeState.kt:4`)
   and `PreferencesManager.folderTreeRootPath` (`PreferencesManager.kt:52-54`) hold a single
   path. `scanDirectory()` (`MukkViewModel.kt:103-124`) is what runs when a folder is opened —
   it scans, sets `_folderTreeState`, and calls `startWatching()`. A playlist switch can reuse
   this exact path, just keyed by a stored, named folder instead of a fresh file-picker
   result.

3. **"Live" already has an implementation: `FileSystemWatcher`.** `startWatching()`
   (`MukkViewModel.kt:591-604`) watches one root at a time and `handleFileSystemEvent()`
   (`MukkViewModel.kt:606-663`) already handles add/change/delete recursively for whatever
   root is currently active. Live-updating playlist contents = keeping this watcher pointed
   at the active playlist's folder; no new file-watching logic needed.

4. **No per-song membership, ordering, or curation is required.** Because a playlist's songs
   are just "what's in the linked folder," this needs **no join table, no ordering column, no
   drag-to-reorder UI** — the sort order is whatever `loadSelectedFolderEntries()` already
   produces (`MukkViewModel.kt:541-545`: by parent, then track number, then name). This makes
   the feature much smaller than a conventional playlist system.

5. **A playlist record is just `{name, folderPath}`.** The only genuinely new persisted thing
   is a small table (or list) mapping a user-chosen name to a folder path — comparable in
   size to `WaveformCacheTable` (`core/data/.../WaveformCacheTable.kt:5-9`), not to a
   media-library schema.

6. **No UI currently exists to hold or switch between multiple named roots.**
   `FolderTreePanel.kt` renders one tree for one `rootPath`; there's no list of saved
   roots/playlists to pick from, and the "Open Folder" button (`App.kt:34-63`,
   `FolderTreePanel`'s header) only ever replaces the single root. A switcher UI is new
   surface, but small — a name list/chips, not a new panel.

7. **Rescanning an already-known folder is not free, even though it skips the expensive
   part.** `FileScanner.scan()` (`FileScanner.kt:15-33`) always walks the whole directory
   tree on disk and does one `TrackRepository.findByPath()` DB lookup per file
   (`FileScanner.kt:51-53`); it only skips the genuinely expensive step — the JAudioTagger
   tag read (`MetadataReader.read()`, `MetadataReader.kt:40-54`, calls `AudioFileIO.read()`)
   — when `file.lastModified()` hasn't advanced past the DB's stored value
   (`FileScanner.kt:60-62`). On a folder with thousands of files, the walk-plus-per-file-query
   cost alone is enough to make "just rescan on every switch" feel slow, which is the
   concern raised when this was discussed with the user.

8. **The database already holds enough to answer "what's in this folder" without touching
   disk at all.** `MediaTracks.filePath` (`core/data/.../MediaTracks.kt`) stores every
   previously-scanned track's full path. A query filtering by folder-path prefix costs one
   DB read, no `walkTopDown()`, no per-file JAudioTagger calls — this is the basis for
   Option B below.

9. **There's already a precedent for "remember and restore this on launch."**
   `saveFolderTreeState()`/`restoreFolderTreeState()` (`MukkViewModel.kt:424-453`) persist
   `folderTree.rootPath` and `folderTree.selectedPath` and restore them on startup, including
   re-running `loadSelectedFolderEntries()` and `startWatching()` for whatever was open last.
   Remembering the active playlist (and reopening it on launch) is the same pattern, just
   sourced from a `Playlists` row instead of a raw path — and it should use the Option B
   cache-first flow below, not a blocking rescan, so launch isn't slowed down by whichever
   playlist happens to be large.

## Root cause

Not a defect — this is a feature request. Restated: the app was built around a single saved
folder root (`folderTree.rootPath`), because the original design didn't anticipate someone
wanting several independent, named libraries to jump between (e.g. "Podcasts," "Workout,"
"Classical").

## Impact

Anyone who splits their music across a few distinct, purpose-specific folders currently has
to re-pick a folder (losing the other one's context) every time they want to switch between
them, instead of naming each once and switching instantly.

## Resolved design decisions

All three implementation-level questions raised earlier are now settled with the user
(2026-09-15):

1. **Migrating today's single root.** The existing `folderTree.rootPath` becomes the first
   playlist automatically, named `"Default"`. Rename is supported (same rename affordance as
   every other playlist — see below), so nothing about this is a special case in the data
   model, only in the one-time migration step that creates it.

2. **UI placement: browser-style tabs.** A new tab strip sits above the three-panel row in
   `MainLayout.kt` (above `FolderTreePanel` / `TrackListPanel` / `NowPlayingPanel`), one tab
   per playlist, plus a trailing `+` button that opens the folder picker
   (`pickDirectoryNative()`, `App.kt:34-63`) to create a new playlist. Default name for a new
   tab is the picked folder's directory name, editable afterward — rename via the same
   double-click-to-edit or right-click-menu convention already used elsewhere (e.g.
   `TrackContextDropdownMenu.kt`). Tabs are drag-reorderable, writing the `sortOrder` column
   noted under Option B; the active tab is the active playlist (`playlist.activeId`).

3. **Deleting a playlist deletes its data.** Removing a playlist removes the `Playlists` row
   *and* every `MediaTracks` row whose `filePath` falls under that playlist's folder (via
   `TrackRepository`, following the existing `deleteByPath`/`deleteAll` pattern in
   `TrackRepository.kt:114-132` — this needs one new bulk-delete-by-prefix method). It does
   **not** touch files on disk — only DB rows and the `Playlists` link.
   - **New consideration surfaced by this:** `WaveformCacheTable` rows are keyed by
     `filePath` too (`WaveformCacheTable.kt:6`) and would otherwise become orphaned once their
     track is gone. The same bulk delete should also clear matching `WaveformCacheTable`
     rows — otherwise the waveform cache grows stale entries for files that have long since
     been un-playlisted.
   - **New consideration:** if the deleted playlist is the currently active one, or contains
     the currently playing track, deleting it needs to behave like the existing
     `FileSystemEvent.DirectoryDeleted`/`AudioFileDeleted` handling already does
     (`MukkViewModel.kt:624-661`) — stop playback if the playing track was under that folder,
     and switch the active tab to an adjacent playlist (browser-tab convention: closing the
     active tab selects a neighboring one).

## Options

Switching should feel instant, but re-running a full disk walk on every switch (or on every
app launch, for whichever playlist was last open) is the cost the user flagged as a real
concern for a large folder (Finding 7). Two ways to get "instant":

### Option A — Concurrently-watched playlists (instant via always-warm state)
Every linked folder is scanned and watched at once, all the time, so every playlist's data is
already live in memory when you switch to it.

- **Pros:** conceptually simple mental model — everything is always current.
- **Cons:** `FileSystemWatcher` (`core/scanner/.../FileSystemWatcher.kt`) currently watches a
  single root — this needs multi-root support, and `MukkViewModel`'s event handling
  (`handleFileSystemEvent`, `MukkViewModel.kt:606-663`) assumes one `_folderTreeState`. Real
  architectural change. Also does the most work the user will never look at: every linked
  folder gets scanned on startup whether or not you ever switch to it that session.
- **Risk:** medium. **Blast radius:** `FileSystemWatcher`, `MukkViewModel` event handling,
  `FolderTreeState` shape.

### Option B — Cached snapshot first, reconcile in the background (recommended)
Switching to a playlist shows what the database already knows about that folder
**immediately** — a single `MediaTracks.filePath`-prefix query (Finding 8), no disk I/O. In
parallel, kick off the existing `fileScanner.scan()` (which already skips the expensive tag
read for unchanged files) to reconcile with disk, refresh the visible list once that
completes, and point `FileSystemWatcher` at the new root for ongoing live updates — reusing
`startWatching()`/`handleFileSystemEvent()` exactly as they work today.

- **Pros:** switching feels instant for any previously-scanned playlist, without watching
  folders nobody is currently looking at; reuses nearly all existing machinery
  (`scanDirectory()`, `startWatching()`, `handleFileSystemEvent()`) — the only new pieces are
  the `Playlists` table/repository, the switcher UI, and one new prefix-query method on
  `TrackRepository`.
- **Cons:** the *first* time a playlist's folder is ever opened, there's no cached snapshot
  yet, so that one genuinely waits on a full scan — same as opening a folder today. A brief
  "stale until reconciled" window exists between showing the cache and the background scan
  finishing (acceptable: `FileSystemWatcher` closes that window going forward the same way it
  already does for the single root today).
- **Risk:** low–medium. **Blast radius:** one new small table/repository, one new
  `TrackRepository` query method, `MukkViewModel`'s folder-open path split into
  "show cache" + "reconcile" steps, `FolderTreePanel.kt` gains a switcher.

The `Playlists` table needs an explicit position column (e.g. `sortOrder: Int`), not just
insertion order, since the switcher's ordering should be user-controlled and remembered
(Finding 9) — reordering the switcher writes new `sortOrder` values, the same shape as
`trackList.columnWidths` already persists per-column values today. The active playlist is
remembered the same way `folderTree.selectedPath` is now: a new `playlist.activeId`
preference, restored on launch through this same cache-first flow rather than a blocking
scan.

### Recommendation
**Option B.** It delivers exactly the "instant, but stays live" behavior discussed, without
Option A's cost of scanning/watching folders the user isn't even looking at. Only the very
first visit to a brand-new playlist folder pays the full scan cost — unavoidable either way,
since nothing can be cached before it's been read once.

## Decision

User confirmed the feature shape: a playlist is a named, saved link to a folder; membership
is recursive and live (2026-09-15). User separately flagged that a full rescan on every
switch/launch is too slow for a large folder and asked for cache-then-live-update behavior —
Option B is the direct answer to that. User also asked that the switcher's playlist order and
the previously-active playlist both be remembered across restarts — covered by the
`sortOrder` column and `playlist.activeId` preference noted under Option B. The three
remaining implementation questions (root migration, UI placement, delete semantics) were
resolved with the user the same day — see "Resolved design decisions" above: existing root
becomes a renameable `"Default"` playlist, UI is a browser-style tab strip above the
three-panel layout with a `+` button, and deleting a playlist deletes its DB data (tracks +
waveform cache) but never touches files on disk.

**Full design is approved.** See Implementation plan below.

## Implementation plan

No unit test harness exists today for the DB or ViewModel layers — `TrackRepository` and
`WaveformRepository` have zero automated tests, and `core:data` has no test source set wired
(only `composeApp`'s `commonTest` depends on `kotlin-test`, and it's unused). This feature is
where that starts: `DatabaseInit` gets a constructor parameter for the DB file (defaulting to
the real `~/.local/share/mukk/library.db` path, so production behavior is unchanged) so tests
can point it at a temp file, and `core:data` gets a `jvmTest` source set. `PlaylistRepository`
— being new code, not a retrofit — gets real tests: create/rename/reorder, and delete
cascading into both `MediaTracks` and `WaveformCacheTable`. The ViewModel/UI parts (3–4) stay
manual for now, verified by hand in the running app once the code side is done, per the
in-progress plan of testing what's practical to test and growing coverage from here rather
than blocking this feature on retrofitting every pre-existing method.

### Part 1 — Data layer: `Playlists` table + repository [x]
- `core/data`: `PlaylistsTable.kt` (`LongIdTable("playlists")`: `name`, `folderPath`,
  `sortOrder: Int`), `PlaylistEntity.kt` (mirrors `WaveformCacheEntity.kt`), `Playlist` data
  class (`core/model`, mirrors `MediaTrackData`'s split from its Exposed entity).
- `PlaylistRepository`: `getAll()` (ordered by `sortOrder`), `create(name, folderPath)`
  (assigns next `sortOrder`), `rename(id, name)`, `reorder(idsInNewOrder)`, `delete(id)`.
- `delete(id)` removes the `Playlists` row and calls two new bulk methods:
  `TrackRepository.deleteByPathPrefix(folderPath)` and
  `WaveformRepository.deleteByPathPrefix(folderPath)` (Finding under "Resolved design
  decisions" #3 — waveform cache must be cleaned too, or it accumulates orphaned entries).
- New `TrackRepository.findByPathPrefix(folderPath): List<MediaTrackData>` — the instant
  cache-read query from Option B.
- Register `Playlists` in `DatabaseInit.kt`'s `SchemaUtils.create(...)`; wire
  `PlaylistRepository` into `AppModule.kt`.
- **Verify:** `./gradlew :core:data:compileKotlinJvm` compiles; manually exercise the
  repository once Part 3 wires it into the running app (this part alone adds no UI-reachable
  behavior).
- **Landed:** `DatabaseInit` gained a `dbFile` constructor parameter (default: the real
  `~/.local/share/mukk/library.db` path) instead of only a bare no-arg constructor, so
  `core:data`'s new `jvmTest` source set can point it at a temp file — this is the
  testability seam the plan's intro paragraph called for, not a deviation. Verified with
  `./gradlew :core:data:compileKotlinJvm :core:data:jvmTest :core:data:detekt
  :composeApp:jvmMainClasses` — all green, including 5 new `PlaylistRepositoryTest` cases
  (create/rename/reorder, delete-cascades-into-tracks-and-waveform-cache, delete-unknown-id).

### Part 2 — Migration: existing root becomes "Default" [x]
- On startup, if the `Playlists` table is empty and `preferencesManager.folderTreeRootPath`
  is non-empty, create one playlist named `"Default"` pointing at that path with
  `sortOrder = 0`, and mark it active.
- **Verify (manual):** with an existing pre-upgrade install (a root folder already set), run
  the app once after this change and confirm exactly one playlist exists, named "Default",
  pointing at the previous root, shown as the active tab.
- **Landed:** added `PreferencesManager.playlistActiveId` (`playlist.activeId`, default `0` =
  none) and a `migrateRootToDefaultPlaylist()` step in `main.kt`, run via `runBlocking` before
  the window opens. No tab UI exists yet (Part 4), so verification was data-layer only: ran
  the real app against the user's actual `library.db`/`preferences.properties` and confirmed
  one `playlists` row (`1|Default|/media/gregory/g/music|0`) and `playlist.activeId=1`.

### Part 3 — ViewModel: playlist state + cache-first switch [x]
- `MukkViewModel` gains `_playlists`, `_activePlaylistId`, exposed via `MukkUiState`.
- `selectPlaylist(id)`: read the playlist's `folderPath`, immediately populate
  `_selectedFolderEntries` from `trackRepository.findByPathPrefix(folderPath)` (no disk I/O),
  then in the background run the existing `fileScanner.scan()` → refresh entries →
  `startWatching(folderPath)` (splits `scanDirectory()`'s current "scan, then show" into
  "show cached, then reconcile").
- `createPlaylist`, `renamePlaylist`, `reorderPlaylists`, `deletePlaylist` (stops playback
  first if the playing track is under the deleted playlist's folder, then switches the active
  tab to a neighbor — same shape as the existing `FileSystemEvent.DirectoryDeleted` handling
  at `MukkViewModel.kt:647-661`).
- Persist active playlist as a new `playlist.activeId` `PreferencesManager` key, restored on
  launch the same way `restoreFolderTreeState()` restores `folderTree.selectedPath` today.
- **Verify (manual):** create two playlists pointing at two folders; switch between them and
  confirm the track list appears with no visible delay; add a file to the non-active
  playlist's folder, switch to it, confirm the new file appears (reconcile ran); restart the
  app and confirm the previously active tab reopens with its tracks shown immediately.
- **Landed:** implemented `selectPlaylist`/`createPlaylist`/`renamePlaylist`/
  `reorderPlaylists`/`deletePlaylist` and a shared private `activatePlaylist()` that does the
  cache-read → background-scan → `startWatching()` sequence; `init()`'s
  `restoreFolderTreeState()` call was replaced with `restoreActivePlaylist()`, which loads
  playlists, resolves `playlist.activeId`, and falls back to the old
  `restoreFolderTreeState()` only when no active playlist exists yet (fresh installs).
  `deletePlaylist` picks the neighboring playlist by index (browser-tab-close convention) and
  fully resets folder-tree/watcher state when the last playlist is removed. No tab UI exists
  yet (Part 4), so switching itself couldn't be clicked through by hand; instead ran the real
  dev build against the user's actual `library.db`/`preferences.properties` (after the user
  stopped their running `/opt/mukk/bin/Mukk` instance to free the single-instance lock) and
  confirmed via `mukk.log` and a window screenshot that `restoreActivePlaylist()` picked up
  the existing "Default" playlist, showed its tracks instantly from cache, ran the background
  reconcile scan, and restarted `FileSystemWatcher` on its folder — with resumed playback
  intact and no errors. `./gradlew :composeApp:jvmMainClasses :composeApp:detekt
  :core:data:jvmTest` all pass; `composeApp`'s detekt baseline gained one new `LargeClass`
  entry for `MukkViewModel` (grown by this part's new methods — splitting it is a separate
  refactor, out of scope here).

### Part 4 — UI: browser-style tab strip [x]
- New `PlaylistTabBar.kt` composable placed above the three-panel row in `MainLayout.kt`; one
  tab per playlist (name + close control), trailing `+` button that calls
  `pickDirectoryNative()` (`App.kt:34-63`) then `onCreatePlaylist(path)`.
- Drag-to-reorder using the same `detectDragGestures` pattern as the existing column-resize
  and panel-divider drags (`TrackListPanel.kt:224-236`, `MainLayout.kt:147-167`).
- Rename via double-click-to-edit inline text field.
- Closing a tab deletes immediately, no confirmation dialog (decided with user, 2026-09-15).
- **Verify (manual):** tabs render for each playlist; `+` creates and switches to a new one;
  dragging reorders and the order survives a restart; closing a tab deletes it, confirmed by
  reopening the app and seeing it gone.
- **Landed:** manual verification caught a real bug before landing: dragging visually reordered
  tabs but never persisted — `playlistTabGestures`' `pointerInput(onClick, onDoubleClick)`
  (`PlaylistTabBar.kt`) was keyed on inline lambdas that get recreated every recomposition,
  and `onDrag` itself triggers a recomposition (it drives the dragged tab's offset), so the
  gesture-detection coroutine restarted mid-drag and silently swallowed the eventual pointer-up
  before `onDragEnd` — and therefore `onReorderPlaylists` — ever fired. Fixed by keying
  `pointerInput` on `Unit` and reading all five callbacks through `rememberUpdatedState` instead.
  Confirmed via screenshot automation: drag-then-restart now shows the DB's `sort_order` updated
  immediately and the new order surviving a process restart. Create (`+`) and close/delete were
  confirmed manually by the user in the running app.

Order matters: each part leaves the app compiling and working (Parts 1–2 are inert until
Part 3 wires them in; Part 3 alone changes no visible UI; Part 4 is what a user actually
sees).
