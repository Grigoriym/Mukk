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
- Logging: use `MukkLogger` (`object` singleton, NOT Koin-managed). Use `error`/`warn`/`debug` with `Throwable` to preserve stack traces.
- Adding new settings: field in `SettingsState` → update `_settingsState` in ViewModel → persist via `preferencesManager.set()` → restore in `restoreSettings()` → expose in `SettingsDialog.kt`
- Compose Desktop focus: global key events require `FocusRequester` + `.focusable()` + `LaunchedEffect` to request focus. Without this, `onPreviewKeyEvent` won't fire until the user clicks something.

## Build Commands
- Compile check: `./gradlew composeApp:jvmMainClasses` (NOT `composeApp:classes` — that task doesn't exist)

## Gotchas & Import Paths

### Exposed ORM v1
- Critical imports differ from pre-v1: `deleteAll` → `org.jetbrains.exposed.v1.jdbc.deleteAll`, `eq` → `org.jetbrains.exposed.v1.core.eq`, `transaction` → `org.jetbrains.exposed.v1.jdbc.transactions.transaction`
- `transaction()` return type inference can fail — may need explicit type parameter
- `SqlExpressionBuilder.eq` needs explicit import for `find()` queries
- `EntityClass.new {}` lambda properties should use `this.` prefix to disambiguate

### Koin Imports
- `viewModel{}` DSL: `org.koin.core.module.dsl.viewModel`
- `koinViewModel()`: `org.koin.compose.viewmodel.koinViewModel`
- `koinInject()`: `org.koin.compose.koinInject`
- `startKoin`/`stopKoin`: `org.koin.core.context`

### Compose Desktop
- `Icons.Filled.VolumeUp` is deprecated — use `Icons.AutoMirrored.Filled.VolumeUp`
- `MenuAnchorType` is deprecated — use `ExposedDropdownMenuAnchorType`
- Material Icons Extended: `compose.materialIconsExtended` in JetBrains compose plugin DSL, add to `jvmMain.dependencies`

### GStreamer Device API
- Device enumeration: `DeviceMonitor` from `org.freedesktop.gstreamer.device`
- Filter audio sinks: `monitor.addFilter("Audio/Sink", null)`, then `monitor.start()`, `monitor.devices`, `monitor.stop()`
- Create sink: `device.createElement("audio-sink")`, set on PlayBin: `playBin.set("audio-sink", element)`, reset to default: `playBin.set("audio-sink", null)`

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

## Completed Features
- Media library scanner (recursive, JAudioTagger tags, SQLite storage)
- Folder tree UI (AIMP-style, hides empty folders)
- Track list UI (columnar table: #, File Name, Title, Album, Artist, Duration)
- Audio playback (play/pause/stop/seek via GStreamer, next/prev within folder)
- Transport bar (seek, volume, track info)
- Single-click selects folder, double-click expands/collapses
- Double-click to play track, single-click to highlight
- Highlight currently playing folder + track
- Global Space key = play/pause (works on startup via `FocusRequester` in `App.kt`)
- Persist volume, window size, last opened folder, currently playing track
- Now-playing info panel (album art, metadata, lyrics)
- Right-click context menu on tracks (copy path, open location)
- Rescan button + scan progress indicator
- Resizable panels with persisted widths
- Configurable track list columns (right-click header to toggle)
- Consolidated UI state into single `MukkUiState` data class
- Koin DI with explicit dependency graph
- Auto-scan on folder select + real-time filesystem monitoring (`FileSystemWatcher`)
- Settings dialog (audio output, repeat/shuffle, library management)
- Centralized logging (`MukkLogger`)
- Tag change detection (re-reads metadata when file modified time is newer)

## Roadmap

### 1. Investigate and reduce memory usage (~400 MB)
The app currently consumes ~400 MB in the system resource monitor. Investigate whether this can be reduced. Potential areas to look at: JVM heap settings (default max heap may be oversized — try tuning `-Xmx` in the Compose Desktop config), GStreamer native memory overhead, Compose/Skia rendering buffers, loaded album art kept in memory, Exposed/SQLite connection pool size. Profile with VisualVM or `jcmd` to identify the biggest contributors. Consider whether JVM flags like `-XX:+UseSerialGC` or `-XX:MaxMetaspaceSize` help for a single-user desktop app.

### 2. Modularisation

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