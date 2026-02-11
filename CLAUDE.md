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
- **DI**: Koin 4.1.1 (`koin-core`, `koin-compose`, `koin-compose-viewmodel`)
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
├── main.kt                  # Entry point: startKoin, window setup, AudioPlayer dispose
├── App.kt                   # Root composable: koinViewModel, koinInject, collects state, file picker
├── MukkViewModel.kt         # Central ViewModel: folder tree state, playback, track selection
├── di/
│   └── AppModule.kt         # Koin module: singletons + viewModel factory
├── data/
│   ├── DatabaseInit.kt      # SQLite connection + schema creation (~/.local/share/mukk/library.db)
│   ├── MediaTracks.kt       # Exposed table definition
│   ├── MediaTrackEntity.kt  # Exposed entity + MediaTrackData data class + toData()
│   ├── TrackRepository.kt   # DB operations: getAllTracks, findByPath, existsByPath, insertIfAbsent, deleteByPath, deleteAll
│   ├── FileBrowserState.kt  # FileEntry + FolderTreeState data classes
│   ├── SettingsState.kt     # RepeatMode enum, AudioDeviceInfo, SettingsState data class
│   └── PreferencesManager.kt # Simple key-value prefs (~/.local/share/mukk/preferences.properties)
├── player/
│   ├── AudioPlayer.kt       # GStreamer PlayBin wrapper with position polling + device enumeration/selection
│   └── PlaybackState.kt     # PlaybackState data class + Status enum
├── scanner/
│   ├── FileScanner.kt       # Recursive directory scanner, delegates DB ops to TrackRepository
│   ├── FileSystemWatcher.kt # WatchService wrapper: real-time filesystem monitoring, emits FileSystemEvents
│   └── MetadataReader.kt    # JAudioTagger wrapper: AudioMetadata, readAlbumArt(), readLyrics()
└── ui/
    ├── MukkTheme.kt         # Material3 dark color scheme
    ├── MainLayout.kt        # Top-level layout: FolderTree | TrackList | NowPlayingPanel / TransportBar
    ├── SettingsDialog.kt    # Settings modal: audio output, playback (repeat/shuffle), library management
    ├── NowPlayingPanel.kt   # Album art, metadata, scrollable lyrics for current track
    ├── FolderTreePanel.kt   # Expandable folder tree with "Mukk" header + settings/open folder buttons
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
- DB access: all Exposed ORM operations go through `TrackRepository`. Only `data/` package files import Exposed. When adding new DB operations, add methods to `TrackRepository` — never use `transaction {}` directly in ViewModel or scanner code.
- DB location: `~/.local/share/mukk/library.db`
- Preferences file: `~/.local/share/mukk/preferences.properties`

## Dependency Injection (Koin)
All dependencies are wired via Koin in `di/AppModule.kt`. `DatabaseInit`, `PreferencesManager`, `MetadataReader`, `TrackRepository`, `FileScanner`, `AudioPlayer` are `single{}` singletons. `MukkViewModel` is registered via `viewModel{}`. `main.kt` calls `startKoin` before the Compose window. `App.kt` retrieves `MukkViewModel` via `koinViewModel()` and `PreferencesManager` via `koinInject()`. When adding a new service: create the class → register in `appModule` → inject via constructor (for non-Compose code) or `koinInject()` (for composables).

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
| `trackList.columns` | String | `""` | MukkViewModel (`\|`-delimited enum names) |
| `playback.repeatMode` | String | `"OFF"` | MukkViewModel (RepeatMode enum name) |
| `playback.shuffle` | String | `"false"` | MukkViewModel |
| `audio.device` | String | `"auto"` | MukkViewModel |

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
- Configurable track list columns (right-click header to toggle, persisted via PreferencesManager)
- Consolidated UI state into single `MukkUiState` data class (one `StateFlow` from ViewModel, simplified parameter passing)
- Koin DI: all singletons converted from `object` to `class`, explicit dependency graph via `di/AppModule.kt`
- Auto-scan: on-folder-select scan for unscanned files + real-time filesystem monitoring via `FileSystemWatcher` (WatchService). New/modified audio files get scanned automatically; deleted files are removed from DB and track list; new subdirectories appear in folder tree immediately.
- TrackRepository: all Exposed ORM operations consolidated in `data/TrackRepository.kt`. `FileScanner` and `MukkViewModel` no longer import Exposed directly — they depend on `TrackRepository` instead.
- Settings dialog: gear icon in FolderTreePanel header opens modal `SettingsDialog` with three sections — audio output device (GStreamer DeviceMonitor enumeration), playback behavior (repeat off/one/all + shuffle), library management (rescan, clear DB, reset preferences). All settings persisted via PreferencesManager. `nextTrack()` respects repeat/shuffle modes.

## Roadmap / TODO

### 4. Add @Preview to composables
Add `@Preview` annotations to UI composables for faster iteration in the IDE. Requires extracting composables to be previewable — pass data/state as parameters rather than reading from ViewModel directly. Add previews for key screens: FolderTreePanel, TrackListPanel, NowPlayingPanel, TransportBar, SeekBar, VolumeControl. Use sample/mock data for preview states (empty, playing, with lyrics, etc.).

### 5. Investigate and reduce memory usage (~400 MB)
The app currently consumes ~400 MB in the system resource monitor. Investigate whether this can be reduced. Potential areas to look at: JVM heap settings (default max heap may be oversized — try tuning `-Xmx` in the Compose Desktop config), GStreamer native memory overhead, Compose/Skia rendering buffers, loaded album art kept in memory, Exposed/SQLite connection pool size. Profile with VisualVM or `jcmd` to identify the biggest contributors. Consider whether JVM flags like `-XX:+UseSerialGC` or `-XX:MaxMetaspaceSize` help for a single-user desktop app.

### 6. Add copy file in the context menu (right click on the song)

### 7. Do formatTime in viewmodel

### 8. overhaul loading while adding/scanning new files, by showing scanned/total_number_of_files

### 9. Create a one place for logging which can be called from anywhere, and with that add logs to catch blocks where we swallow exceptions

### 10. Refactor scanSingleFile which returns either 0 or 1, which is cryptic

### 11. PlaybackBundle has val albumArt: ByteArray;  IDE says that with 'Array' type in a 'data' class: it is recommended to override 'equals()' and 'hashCode()' 

### 12. On reopening the app, the song that was played is not saved, i.e. the timing, so on restart I need to start the song again, we can make it to be controlled either playing right ahead, or just being in a paused state

### 13. I tried changing tags from songs, and the update wasn't seen in the app, though I rescaned just in case. Clearing the db helped