# Mukk — Music Player for Linux

## Project Context
- A desktop music player built with **Kotlin Multiplatform + Compose Desktop**
- Motivated by dissatisfaction with existing Linux players (AIMP broken on Linux, DeaDBeeF has political issues)
- Goal: clean media library viewer + player, no bloat
- Key differentiator: **file-system-based browsing** — AIMP-style folder tree + track list, not a database-driven flat track list

## Architecture Decisions
- **UI**: Compose Desktop (Kotlin/JVM) with Material3 dark theme
- **Audio playback**: GStreamer via `gst1-java-core:1.4.0`
- **Metadata/tags**: JAudioTagger for reading audio file tags
- **Database**: SQLite via Exposed ORM 1.0.0 + SQLite JDBC 3.51.1.0
- **State management**: MVVM with Kotlin StateFlow
- **Language**: Kotlin for all business logic and UI

## Tech Stack
- Kotlin 2.3.0, Compose Multiplatform 1.10.0
- Gradle 8.14.3 with version catalogs
- JVM target (desktop only for now, multiplatform potential later)
- Package: `com.grappim.mukk`
- Main class: `com.grappim.mukk.MainKt`

## Project Structure
- `composeApp/` — main module (Compose Desktop app)
- Source sets: `jvmMain` for desktop-specific code
- All source under `composeApp/src/jvmMain/kotlin/com/grappim/mukk/`

### Source Layout
```
com/grappim/mukk/
├── main.kt                  # Entry point: init DB, AudioPlayer, ViewModel, window
├── App.kt                   # Root composable: collects state, wires callbacks, file picker
├── MukkViewModel.kt         # Central ViewModel: folder tree state, playback, track selection
├── data/
│   ├── DatabaseInit.kt      # SQLite connection + schema creation (~/.local/share/mukk/library.db)
│   ├── MediaTracks.kt       # Exposed table definition
│   ├── MediaTrackEntity.kt  # Exposed entity + MediaTrackData data class + toData()
│   ├── FileBrowserState.kt  # FileEntry + FolderTreeState data classes
│   └── PreferencesManager.kt # Simple key-value prefs (~/.local/share/mukk/preferences.properties)
├── player/
│   ├── AudioPlayer.kt       # GStreamer PlayBin wrapper with position polling
│   └── PlaybackState.kt     # PlaybackState data class + Status enum
├── scanner/
│   ├── FileScanner.kt       # Recursive directory scanner, stores tracks in DB
│   └── MetadataReader.kt    # JAudioTagger wrapper: AudioMetadata, readAlbumArt(), readLyrics()
└── ui/
    ├── MukkTheme.kt         # Material3 dark color scheme
    ├── MainLayout.kt        # Top-level layout: FolderTree | TrackList | NowPlayingPanel / TransportBar
    ├── NowPlayingPanel.kt   # Album art, metadata, scrollable lyrics for current track
    ├── FolderTreePanel.kt   # Expandable folder tree with "Mukk" header + open folder button
    ├── TrackListPanel.kt    # Columnar track list (#, File Name, Title, Album, Artist, Duration)
    ├── TransportBar.kt      # Play/pause/stop/skip, seek bar, volume, track info
    └── components/
        ├── SeekBar.kt       # Seek slider with time labels + formatTime() helper
        └── VolumeControl.kt # Volume slider with icon
```

## UI Architecture — Three-Panel Layout
Three panels side by side, with a transport bar at the bottom:

1. **Folder Tree** (`FolderTreePanel`, fixed 250dp) — expandable tree showing only folders that contain audio files (recursively). Header has "Mukk" title + open folder button. Single-click = select folder (shows tracks), double-click = expand/collapse children. Arrow icon also toggles expand. Playing folder gets subtle highlight + play indicator.
2. **Track List** (`TrackListPanel`, fills remaining space) — columnar table of audio files from the selected folder. Columns: #, File Name, Title, Album, Artist, Duration. Single-click = select/highlight track, double-click = play. Three visual states: playing (primary), selected (surfaceVariant), default.
3. **Now-Playing Panel** (`NowPlayingPanel`, fixed 280dp) — shows album art (square, rounded corners, placeholder music icon when missing), track metadata (title, artist, album, genre + year), and scrollable lyrics. Album art and lyrics read on-the-fly from audio files via `MetadataReader.readAlbumArt()` / `readLyrics()` when playback starts. Shows "No track playing" placeholder when idle.

```
┌──────────────────┬──────────────────────────────────┬──────────────┐
│ Mukk        [+ ] │  #  File Name  Title Album Artist│  [Album Art] │
│                  │  01 01-Song.. Cheated Time Prayi.│              │
│ ▾ 📁 music      │  01 01.Can.. Can't.. Pred. Prayi.│  Title       │
│   ▸ 📁 Megadeth │  01 01-Rise.. Rise A. ACry Prayi.│  Artist      │
│   ▸ 📁 Slayer   │  02 02-AllD.. All Da. Time Prayi.│  Album       │
│   ▾ 📁 Pray. M.◄│  02 02.She'.. She's. Pred. Pray.│  Genre · Year│
│     📁 Album1   │  ...                             │              │
│     📁 Album2   │                                  │  (lyrics)    │
├──────────────────┴──────────────────────────────────┴──────────────┤
│  ◄◄  ▶/❚❚  ►►  |  ━━━●━━━  |  🔊 ────                            │
└────────────────────────────────────────────────────────────────────┘
```

## Key State Models
- **`FolderTreeState`** — `rootPath`, `expandedPaths: Set<String>`, `selectedPath`
- **`FileEntry`** — `file: File`, `isDirectory`, `name`, `trackData: MediaTrackData?`
- **`selectedFolderEntries`** — audio-only `FileEntry` list for the selected folder (no directories)
- **`selectedTrackPath`** — path of the track highlighted by single-click (distinct from playing track)
- **`currentAlbumArt`** — `ByteArray?` loaded on-the-fly when playback starts, cleared on stop
- **`currentLyrics`** — `String?` loaded on-the-fly when playback starts, cleared on stop
- Next/Previous track cycles within `selectedFolderEntries`

## Key Patterns
- Audio file extensions: `mp3, flac, ogg, wav, aac, opus, m4a` (defined in both `FileScanner` and `MukkViewModel`)
- Folder tree hides folders with no audio files (recursive check via `containsAudioFiles()` using `walkTopDown()`)
- Track list enriches audio files with DB metadata (title, artist, duration) when available; shows filename + "-" for unscanned files
- `getSubfolders()` is passed as a callback to FolderTreePanel and called inside `remember{}` — it's synchronous file I/O, works because tree builds are memoized on `expandedPaths` changes
- Native file picker: tries zenity → kdialog → Swing JFileChooser fallback
- DB location: `~/.local/share/mukk/library.db`
- Preferences file: `~/.local/share/mukk/preferences.properties` (volume, window size)
- Global Space key toggles play/pause via `onPreviewKeyEvent` on root `Box` in `App.kt`
- `combinedClickable` (from `ExperimentalFoundationApi`) used in both `FolderTreePanel` and `TrackListPanel` for single/double-click differentiation
- Window size persisted via debounced `snapshotFlow` on `WindowState.size` + save on close

## Implementation Notes
- **Deleted files** (replaced by folder tree approach): `Sidebar.kt`, `FileBrowserPanel.kt`, `NowPlayingFolderPanel.kt`, `FileBrowserState` data class
- The old breadcrumb navigation (`navigateToDirectory`, `navigateUp`, `navigateToRoot`, `buildPathSegments`) was removed from ViewModel
- `containsAudioFiles()` uses `walkTopDown()` which can be slow on very large directory trees — may need caching if performance is an issue

## MVP Features
1. **Media library scanner** — scan directories recursively, read tags with JAudioTagger, store in SQLite ✅
2. **Folder tree UI** — AIMP-style expandable folder tree, hides empty folders ✅
3. **Track list UI** — columnar table with file name, title, album, artist, duration ✅
4. **Audio playback** — play/pause/stop/seek via GStreamer, next/prev within selected folder ✅
5. **Playback controls UI** — transport bar with seek, volume, track info ✅

## Completed Features (post-MVP)
1. **Single-click selects folder, double-click expands** — `combinedClickable` in FolderTreePanel ✅
2. **Show tracks for folders with subfolders** — resolved by fix #1 (single-click always selects) ✅
3. **Double-click to play track** — single-click = select/highlight, double-click = play ✅
4. **Highlight currently playing folder + track** — playing folder highlighted in tree with play icon, playing track highlighted in list ✅
5. **Global Space key = play/pause** — `onPreviewKeyEvent` in App.kt ✅
6. **Persist volume** — saved to preferences.properties via PreferencesManager ✅
7. **Persist window size** — saved/restored via PreferencesManager + snapshotFlow ✅
8. **Now-playing info panel (third panel)** — album art, metadata (title/artist/album/genre/year), scrollable lyrics via `NowPlayingPanel` ✅
9. **Persist last opened folder** — saves/restores `rootPath`, `expandedPaths`, `selectedPath` via PreferencesManager (`|`-delimited expanded paths) ✅

## Roadmap / TODO

### 1. Rescan button
Add a refresh icon in FolderTreePanel header (next to open-folder button). Re-runs `FileScanner.scan()` on current root, reloads entries and track metadata.

### 2. Right-click context menu on tracks
Context menu on track rows with options: "Copy file", "Open file location" (`xdg-open` on parent dir), "Copy file path to clipboard".

### 3. Resizable panels
Replace fixed panel widths (250dp / 280dp) with draggable splitters. Custom drag-handle `Modifier` on divider areas. Persist widths to PreferencesManager.

### 4. Configurable track list columns
Data model for column definitions (visible, width, order). Right-click on column header to toggle columns on/off. Persist column config to PreferencesManager.

### 5. Auto-scan new folders added at runtime
Currently the folder tree reads the filesystem directly, so new folders appear when expanding/collapsing the tree. However, `FileScanner.scan()` only runs when the user opens a root folder via "Open Folder", so newly added folders have no metadata in the DB (tracks show filenames with no title/artist/album/duration). Need to either: (a) rescan when a folder is selected and has unscanned tracks, or (b) watch the filesystem for changes (`WatchService`), or (c) rely on the rescan button (item #1) as the manual solution.

### 6. Scan progress indicator
When opening a new folder (triggering `FileScanner.scan()`), show a progress indicator so the user knows scanning is in progress. Could be a linear progress bar in the folder tree header, a modal/overlay, or inline text showing scanned file count.

### 7. Bug: "Mukk" title missing on first start
On first launch (no folder opened yet), the "Mukk" title in the top-left corner of FolderTreePanel is not visible. It appears after opening a folder. Likely a layout/visibility issue in FolderTreePanel when there is no root path set.
