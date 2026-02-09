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

1. **Folder Tree** (`FolderTreePanel`, default 250dp, resizable 150–450dp) — expandable tree showing only folders that contain audio files (recursively). Header has "Mukk" title + open folder button. Single-click = select folder (shows tracks), double-click = expand/collapse children. Arrow icon also toggles expand. Playing folder gets subtle highlight + play indicator.
2. **Track List** (`TrackListPanel`, fills remaining space) — columnar table of audio files from the selected folder. Columns: #, File Name, Title, Album, Artist, Duration. Single-click = select/highlight track, double-click = play. Three visual states: playing (primary), selected (surfaceVariant), default.
3. **Now-Playing Panel** (`NowPlayingPanel`, default 280dp, resizable 150–450dp) — shows album art (square, rounded corners, placeholder music icon when missing), track metadata (title, artist, album, genre + year), and scrollable lyrics. Album art and lyrics read on-the-fly from audio files via `MetadataReader.readAlbumArt()` / `readLyrics()` when playback starts. Shows "No track playing" placeholder when idle.

Panel dividers are draggable (`DraggableDivider` in MainLayout.kt) with `E_RESIZE_CURSOR` hover icon. Widths persist to PreferencesManager (`panel.leftWidth`, `panel.rightWidth`).

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
- Folder tree hides folders with no audio files (recursive check via `containsAudioFiles()` using `walkTopDown()` — can be slow on huge trees, may need caching)
- Track list enriches audio files with DB metadata (title, artist, duration) when available; shows filename + "-" for unscanned files
- `getSubfolders()` is passed as a callback to FolderTreePanel and called inside `remember{}` — synchronous file I/O, memoized on `expandedPaths` changes
- Native file picker: tries zenity → kdialog → Swing JFileChooser fallback
- `combinedClickable` (from `ExperimentalFoundationApi`) used in both `FolderTreePanel` and `TrackListPanel` for single/double-click differentiation
- DB location: `~/.local/share/mukk/library.db`
- Preferences file: `~/.local/share/mukk/preferences.properties`

## Callback Flow
ViewModel exposes functions + StateFlows → `App.kt` collects state via `collectAsState()` and passes lambdas → `MainLayout` forwards to child panels. All UI composables are stateless — they receive data and callbacks as parameters. When adding a new action: add function to ViewModel → wire lambda in App.kt → thread through MainLayout → use in target panel.

## PreferencesManager Keys
| Key | Type | Default | Location |
|-----|------|---------|----------|
| `volume` | Double | `0.8` | MukkViewModel |
| `window.width` | Int | `1024` | main.kt |
| `window.height` | Int | `700` | main.kt |
| `folderTree.rootPath` | String | `""` | MukkViewModel |
| `folderTree.expandedPaths` | String | `""` | MukkViewModel (`\|`-delimited) |
| `folderTree.selectedPath` | String | `""` | MukkViewModel |
| `playingTrack` | String | `""` | MukkViewModel |
| `panel.leftWidth` | Int | `250` | MainLayout |
| `panel.rightWidth` | Int | `280` | MainLayout |

## MVP Features
1. **Media library scanner** — scan directories recursively, read tags with JAudioTagger, store in SQLite ✅
2. **Folder tree UI** — AIMP-style expandable folder tree, hides empty folders ✅
3. **Track list UI** — columnar table with file name, title, album, artist, duration ✅
4. **Audio playback** — play/pause/stop/seek via GStreamer, next/prev within selected folder ✅
5. **Playback controls UI** — transport bar with seek, volume, track info ✅

## Completed Features (post-MVP)
- Single-click selects folder, double-click expands/collapses
- Double-click to play track, single-click to highlight
- Highlight currently playing folder + track
- Global Space key = play/pause
- Persist volume, window size, last opened folder, currently playing track
- Now-playing info panel (album art, metadata, lyrics)
- Right-click context menu on tracks (copy path, open location)
- Rescan button + scan progress indicator
- Resizable panels with persisted widths

## Roadmap / TODO

### 1. Configurable track list columns
Data model for column definitions (visible, width, order). Right-click on column header to toggle columns on/off. Persist column config to PreferencesManager.

### 2. Auto-scan new folders added at runtime
Currently the folder tree reads the filesystem directly, so new folders appear when expanding/collapsing the tree. However, `FileScanner.scan()` only runs when the user opens a root folder via "Open Folder", so newly added folders have no metadata in the DB (tracks show filenames with no title/artist/album/duration). Need to either: (a) rescan when a folder is selected and has unscanned tracks, or (b) watch the filesystem for changes (`WatchService`), or (c) rely on the rescan button (item #1) as the manual solution.

### 3. Settings screen
Add a settings/preferences UI accessible from the app (e.g., gear icon in the header or a menu). Potential settings to expose over time: audio output device, theme/appearance, default scan directory, playback behavior (e.g., repeat mode, shuffle), column visibility defaults, etc. Use a dialog or a dedicated panel. Persist all settings via PreferencesManager.

### 4. Add dependency injection (Koin)
Introduce Koin as a lightweight DI framework. Currently all wiring is manual in `main.kt` (AudioPlayer, ViewModel, etc.). As the app grows (settings, more services), a proper DI setup will reduce boilerplate and make dependencies explicit. Koin is the natural choice — pure Kotlin, multiplatform-compatible, no code generation. Define modules for player, data/DB, scanner, and ViewModel layers.

### 5. Add @Preview to composables
Add `@Preview` annotations to UI composables for faster iteration in the IDE. Requires extracting composables to be previewable — pass data/state as parameters rather than reading from ViewModel directly. Add previews for key screens: FolderTreePanel, TrackListPanel, NowPlayingPanel, TransportBar, SeekBar, VolumeControl. Use sample/mock data for preview states (empty, playing, with lyrics, etc.).
