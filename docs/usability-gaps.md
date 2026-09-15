# Usability gap survey

Investigation from 2026-09-15: what Mukk lacks compared to other desktop music players
(foobar2000, MusicBee, DeaDBeeF, AIMP, Strawberry, Quod Libet). Each entry has enough evidence
to investigate cold and turn into a task. Priority is not yet assigned — that's the next step,
done together rather than guessed here.

For each gap that gets picked up: run it through `investigate-issue` (or equivalent) before
writing a fix, since several of these touch `MukkUiState`/`MukkViewModel` shape and deserve a
real design pass, not a quick patch.

## Navigation

### No search or filter
No search field exists anywhere in the state or UI. `MukkUiState.kt` has no query field;
`TrackListPanel.kt` and `FolderTreePanel.kt` only ever render `selectedFolderEntries` /
`getSubfolders()` output as-is. With a large library, the only way to find a track is clicking
through the folder tree by hand.

### Single library root only
`FolderTreeState.rootPath` (`core/model/.../FolderTreeState.kt:4`) is a single nullable
`String`, and `PreferencesManager.folderTreeRootPath` (`core/data/.../PreferencesManager.kt:52-54`)
persists exactly one path. Aggregating two folders (e.g. an internal drive + a NAS mount) isn't
possible — `scanDirectory()` (`MukkViewModel.kt:103-124`) replaces the tree wholesale.

## Playback

### No playlists or play queue
`nextTrack()`/`previousTrack()` (`MukkViewModel.kt:237-299`) only ever walk
`_selectedFolderEntries` — the audio files of whichever folder is currently selected. There is
no cross-folder queue, no "add to queue," no save/load `.m3u`, and no drag-to-reorder. This also
means there's no "up next" preview — you only learn what plays next when it starts.

### No gapless playback, crossfade, or ReplayGain
Confirmed absent by grep (`crossfade|gapless|replaygain` — zero hits outside this doc).
`AudioPlayer.play()` (`core/player/.../AudioPlayer.kt`) starts each track independently via
GStreamer `PlayBin`; there's no overlap handling and no per-track/album volume normalization.
Noticeable on live albums, classical, or DJ-mixed sets.

### No minimize-to-tray / background playback
`main.kt`'s `onCloseRequest` (`main.kt:67-82`) tears down `AudioPlayer`, `FileSystemWatcher`,
and `PreferencesManager` and exits. There is no "keep playing, hide the window" mode.

## Library / metadata

### No tag editing
`MetadataReader` (`core/scanner/.../MetadataReader.kt`) only reads tags
(`readNowPlayingExtras`, `readAlbumArt`, `readLyrics`) via JAudioTagger. Nothing writes tags
back to a file — fixing a wrong title/artist means leaving the app.

### No rating or favorites
`MediaTrackData` (`core/model/.../MediaTrackData.kt:3-18`) has no rating/favorite field, and
neither the DB schema (`MediaTracks.kt`) nor any UI exposes one. No "love" toggle, no star
rating, no smart-playlist-by-rating.

### Lyrics are read-only and unsynced
`readLyrics()` returns static text (`NowPlayingPanel.kt` renders it as a plain scrollable
block). No LRC time-sync / karaoke-style highlighting against `playbackState.positionMs`.

## Selection / bulk actions

### No multi-select in the track list
`selectedTrackPath` (`MukkUiState.kt:18`) is a single nullable `String`. `TrackListPanel.kt`
and `TrackContextDropdownMenu.kt` (`ui/components/TrackContextDropdownMenu.kt`) only ever
operate on one `FileEntry` at a time — no shift/ctrl-click, no "select all," no batch context
menu actions (e.g. bulk copy path, bulk add-to-queue once queueing exists).

## OS integration

### No global media-key / MPRIS support
`App.kt:86-93` binds only Space (play/pause), and only while the Mukk window has focus. No
`Ctrl+→`/`Ctrl+←` for next/prev, no volume shortcuts, and no MPRIS D-Bus interface — so
hardware media keys, GNOME/KDE media widgets, and `playerctl` cannot see or control Mukk. This
is a bigger miss on Linux specifically, where MPRIS is the standard integration point.

## Appearance

### No theme choice
`MukkTheme.kt` defines a single hardcoded dark `ColorScheme`. No light mode, no accent color,
no following the system theme.

## Scope note

The project's own differentiator is file-browser-first, not database-flat (`CLAUDE.md`'s
Project Context). Some of foobar2000's power-user surface (tag-editor grids, scripting-driven
custom columns) may be deliberately out of scope under "no bloat." Search, a play queue, and
MPRIS/media-key support are the three that stand out as basic navigation/OS-integration
expectations rather than bloat — worth weighing first when prioritizing.
