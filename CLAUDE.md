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
├── MukkLogger.kt            # Centralized logging: object singleton, console + file output
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
- Next/Previous track cycles within `selectedFolderEntries`, respecting repeat mode (OFF/ONE/ALL) and shuffle
- **`SettingsState`** — `repeatMode`, `shuffleEnabled`, `availableAudioDevices`, `selectedAudioDevice`, `libraryPath`, `trackCount`. Managed via `_settingsState` MutableStateFlow in ViewModel, combined as 3rd top-level flow into `uiState`.

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
- Exposed v1 imports: `deleteAll` needs `org.jetbrains.exposed.v1.jdbc.deleteAll`, `eq` needs `org.jetbrains.exposed.v1.core.eq`, `transaction` needs `org.jetbrains.exposed.v1.jdbc.transactions.transaction`
- Logging: use `MukkLogger` (`object` singleton, NOT Koin-managed) — callable from anywhere including `main()`, top-level functions, and extension functions. Use `error()` for failures that affect functionality, `warn()` for recoverable issues, `debug()` for expected fallbacks (e.g. zenity not found). Always pass the `Throwable` to preserve stack traces. Log file: `~/.local/share/mukk/mukk.log`.
- GStreamer device enumeration: `DeviceMonitor` from `org.freedesktop.gstreamer.device`, filter with `addFilter("Audio/Sink", null)`, get devices via `monitor.devices`, create sink element via `device.createElement("audio-sink")`, set on PlayBin via `playBin.set("audio-sink", element)`
- Adding new settings: create field in `SettingsState` → update `_settingsState` in ViewModel → persist via `preferencesManager.set()` → restore in `restoreSettings()` → expose in `SettingsDialog.kt` (stateless composable). The `uiState` combine uses 3 top-level flows (primary, playback, settings) because Kotlin `combine()` supports max 5 params per call.
- Settings dialog: rendered conditionally in `App.kt` via `showSettingsDialog` state, opened from gear icon in `FolderTreePanel` header

## Android/Compose Rules

- Do not use early returns in Composable functions — use conditional wrapping
- Lambda parameters: present tense (`onClick` not `onClicked`)
- Prefer `kotlinx-collections-immutable` (`ImmutableList`, `persistentListOf()`) over `List`/`MutableList` in state classes and Composable parameters for stable recomposition

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
- Centralized logging: `MukkLogger` object in `MukkLogger.kt` — DEBUG/INFO to stdout, WARN/ERROR to stderr, all levels appended to `~/.local/share/mukk/mukk.log`. All 16 catch blocks now have logging with full stack traces.
- Tag change detection: `FileScanner.scanSingleFile()` compares `file.lastModified()` against DB record — if file is newer, re-reads metadata and updates via `TrackRepository.updateByPath()`. Works with manual rescan, auto-scan (FileSystemWatcher), and folder selection.

## Roadmap / TODO

### 5. Investigate and reduce memory usage (~400 MB)
The app currently consumes ~400 MB in the system resource monitor. Investigate whether this can be reduced. Potential areas to look at: JVM heap settings (default max heap may be oversized — try tuning `-Xmx` in the Compose Desktop config), GStreamer native memory overhead, Compose/Skia rendering buffers, loaded album art kept in memory, Exposed/SQLite connection pool size. Profile with VisualVM or `jcmd` to identify the biggest contributors. Consider whether JVM flags like `-XX:+UseSerialGC` or `-XX:MaxMetaspaceSize` help for a single-user desktop app.

### 6. Add copy file in the context menu (right click on the song)

### 7. Do formatTime, formatFileSize in viewmodel

### 10. Refactor scanSingleFile which returns either 0 or 1, which is cryptic

### 11. PlaybackBundle has val albumArt: ByteArray; IDE says that with 'Array' type in a 'data' class: it is recommended to override 'equals()' and 'hashCode()' 

### 14. modularisation

### 15. settings shows that Library is 0, though we have music already

### 16. when exiting the app you can see "Skia layer is disposed", presumable when we close the app while the music is playing

### 17. when selecting (clicking) a track in the track list, there is a delay until the track will be highlighted

### 18. noticed the track total length is 0 for some tracks/album, noticed only in sir lord baltimore - all albums

## Behavioral Guidelines

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.
- **Don't add UI elements or navigation that weren't asked for** - if asked to create a settings screen, don't add a settings button to other screens unless explicitly requested.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.