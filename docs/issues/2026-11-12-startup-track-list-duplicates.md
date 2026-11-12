# 2026-11-12 — Track list shows duplicated tracks after startup

**Status:** Done
**Link:** — (reported in chat)   **Updated:** 2026-11-12

## Report
The owner reported: "if I previously was listening a song in an album, then when I open the app
[...] the tracks in that album are duplicated, though if I click the album (so that the list of
songs will be refreshed) it will show them correctly."

- Environment: owner's library, playlist id 47, root `/media/gregory/g/music/Bow Wow`, browse
  folder `.../1980 - Telephone`, playing track `.../08. Tomorrow In Your Life.mp3`.
- The report does not say whether the "duplicates" have the same file names. They do not (see
  Findings).

## Findings
1. **The "duplicates" are rows for files that no longer exist.** The album folder on disk has 9
   files named `NN. Title.mp3`. The DB has 18 rows under that folder: those 9, plus 9 rows with
   older names (`04 - Carnival.mp3`, `Bow Wow - 01 - Hot Rod Tornado.mp3`, ...) and lower ids
   (88910–88918 vs 245752–245760). The files were renamed after the first scan. Verified with
   `sqlite3 library.db "select id, file_path ... like '.../1980 - Telephone/%'"` and `ls` of the folder.
2. **This is not limited to one album.** 93,329 of 147,466 rows in `media_tracks` point to files
   that do not exist. All of them are under `/media/gregory/g/music`, which is mounted. Under the
   `Bow Wow` root alone, 140 of 286 rows are stale. (Checked with a `[ -e path ]` loop over every row.)
3. **Startup builds the list from the DB, without checking the disk.** `restoreActivePlaylist()`
   calls `activatePlaylist()` (`MukkViewModel.kt:531`), which sets `_selectedFolderEntries` from
   `buildCachedEntries(trackRepository.findByPathPrefix(browsePath))` twice — before and after the
   scan (`MukkViewModel.kt:640`, `MukkViewModel.kt:648`). Every DB row under the prefix becomes a
   track-list row, including stale ones.
4. **Clicking the folder builds the list from the disk.** `selectFolder()` →
   `loadSelectedFolderEntries()` walks the folder with `walkTopDown()` and only looks up DB data
   per existing file (`MukkViewModel.kt:798`). Stale rows never appear. This is why a click "fixes" it.
5. **No code path removes rows for files that vanished while the app was closed.**
   `FileScanner.scan()` (`FileScanner.kt:17`) inserts and updates, but never deletes. It already
   loads `existingByPath` for the whole prefix, so it has the data to find stale rows. The only
   deletion paths are the live `FileSystemWatcher` delete event (`MukkViewModel.kt:838`),
   `clearLibrary()`, and playlist deletion. A rename done while Mukk is not running (or that the
   watcher misses) leaves the old row forever.
6. The unique index on `file_path` exists (`.schema media_tracks`), so the same path is never
   stored twice. The duplicates are different paths with the same tags.

## Root cause
`FileScanner.scan()` never prunes DB rows whose files no longer exist, so renamed or deleted files
leave stale rows in `media_tracks`. `activatePlaylist()` (`MukkViewModel.kt:640`, `:648`) builds
the track list straight from `findByPathPrefix()` without an existence check, so on startup those
stale rows show next to the renamed files. `selectFolder()` walks the disk instead, so it hides them.

## Impact
- Every startup with a restored playlist, and every playlist tab switch, shows ghost tracks in any
  folder that had files renamed or removed while Mukk was closed. For the owner this is common
  (63% of library rows are stale).
- Ghost rows can be played via double-click → playback of a missing file fails.
- Next/previous navigates through `_selectedFolderEntries`, so it can step onto ghost rows.
- The Settings "track count" is inflated (147k instead of about 54k).
- Workaround: click the folder again.

## Open questions
- Stale `waveform_cache` rows for the same files also stay. Not visible to the user. Not blocking;
  out of scope unless chosen below.
- `walkTopDown()` silently skips subfolders it cannot read. A prune based on its result would
  delete rows for an unreadable subfolder. The cost is a metadata re-read once the folder is
  readable again. No data the user created is lost. Not blocking.

## Options

### A. Prune stale rows in `FileScanner.scan()` (recommended, with B)
After the walk, delete rows in `existingByPath` whose path is not in the walked file set.
- Pros: fixes the cause. Cleans the library as each playlist is activated or a folder is clicked.
  Fixes the track count. Uses data `scan()` already loads.
- Cons: a new delete path in the scanner. Needs a repository method that deletes a list of paths
  in one transaction (93k single deletes would be slow).
- Risk: an unmounted drive returns early (`!directory.isDirectory`), so no mass wipe. Unreadable
  subfolder: see Open questions.
- Blast radius: `scan()` callers — `rescan()`, `selectFolder()`, `activatePlaylist()`.

### B. Filter cached entries by `File.exists()` in `activatePlaylist()`
- Pros: tiny. Fixes the first paint, which runs *before* the scan and so is not covered by A.
- Cons: alone, it hides the symptom and leaves the DB growing. One `stat` per row on the
  browse folder (cheap for an album; a large root folder costs more).
- Blast radius: only the startup / tab-switch list.

### C. Do nothing
Workaround exists, but ghost tracks are playable and break next/previous. Not recommended.

**Recommendation: A + B.** A removes the stale rows; B keeps the list correct during the
seconds before the scan finishes.

## Decision
2026-11-12, owner: go with A + B.

## Implementation plan
One part. A and B are one fix the owner will test together from one branch
(`fix/startup-stale-tracks`), and B is a two-line change.

### Part 1 — Prune stale rows on scan, and skip missing files in the cached list
- [x] `TrackRepository.deleteByPaths(paths)`: deletes the given paths in one transaction
  (chunked to stay under SQLite's bound-variable limit).
- [x] `FileScanner.scan()`: after the walk, call `deleteByPaths` for the paths in
  `existingByPath` that the walk did not find.
- [x] `MukkViewModel.buildCachedEntries()`: drop tracks whose file does not exist.

Verify: `./gradlew :core:data:jvmTest` (new `TrackRepositoryTest`: `deleteByPaths` removes exactly
the given rows and keeps the rest; fails before the change because the method does not exist),
`./gradlew composeApp:jvmMainClasses detekt`. Manual: the owner starts the app with the
"1980 - Telephone" folder restored and sees 9 tracks, not 18.

Landed: as planned. `:core:data:jvmTest` passes (2 new tests; they failed to compile before the
method existed), `composeApp:jvmMainClasses` and `detekt` pass. The owner confirmed the manual check
on the built `.deb` (9 tracks, not 18). Shipped in v1.1.4. Note: the first startup after this change prunes stale rows only under the active
playlist's root. Other folders are cleaned when they are scanned (folder click, tab switch, rescan).

