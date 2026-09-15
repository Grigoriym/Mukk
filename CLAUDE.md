# Mukk — Music Player for Linux

## Project Context
- A desktop music player built with **Kotlin Multiplatform + Compose Desktop**
- Motivated by dissatisfaction with existing Linux players (AIMP broken on Linux, DeaDBeeF has political issues)
- Goal: clean media library viewer + player, no bloat
- Key differentiator: **file-system-based browsing** — AIMP-style folder tree + track list, not a database-driven flat track list

## Architecture Decisions
- **UI**: Compose Desktop (Kotlin/JVM) with Material3 dark theme
- **Audio playback**: GStreamer via `gst1-java-core`
- **Metadata/tags**: JAudioTagger for reading audio file tags
- **Database**: SQLite via Exposed ORM + SQLite JDBC
- **DI**: Koin (`koin-core`, `koin-compose`, `koin-compose-viewmodel`)
- **State management**: MVVM with Kotlin StateFlow
- **Language**: Kotlin for all business logic and UI

## Tech Stack
- Kotlin, Compose Multiplatform (versions: `gradle/libs.versions.toml`)
- Gradle with version catalogs (version: `gradle/wrapper/gradle-wrapper.properties`)
- JVM target (desktop only for now, multiplatform potential later)
- Package: `com.grappim.mukk`
- Main class: `com.grappim.mukk.MainKt`

## Project Structure
Gradle multi-module: `composeApp` (UI + ViewModel + DI wiring) depends on four `core:*`
library modules that hold the non-UI logic. Module boundaries are enforced by what each
`build.gradle.kts` declares as a dependency — see `settings.gradle.kts` for the module list.

- `composeApp/` — Compose Desktop app: entry point, root composable, ViewModel, all `ui/`
- `core:model/` — shared data classes/enums (`commonMain`) + `MukkLogger` (`jvmMain`); no
  Exposed, no GStreamer, no JAudioTagger — the dependency-free layer everything else builds on
- `core:data/` — SQLite/Exposed persistence (`TrackRepository`, `WaveformRepository`,
  `DatabaseInit`) + `PreferencesManager`; the only module that imports Exposed
- `core:player/` — `AudioPlayer` (GStreamer `PlayBin` wrapper) + `WaveformExtractor`
- `core:scanner/` — `FileScanner`, `FileSystemWatcher`, `MetadataReader` (JAudioTagger)

### Source Layout
```
composeApp/src/jvmMain/kotlin/com/grappim/mukk/
├── main.kt                  # Entry point: SingleInstance check, startKoin, window setup, AudioPlayer dispose
├── App.kt                   # Root composable: koinViewModel, koinInject, collects state, file picker
├── SingleInstance.kt        # Single-instance lock; relaunch focuses the existing window instead of opening a second one
├── MukkViewModel.kt         # Central ViewModel: folder tree state, playback, track selection
├── MukkUiState.kt           # Consolidated UI state combined from folder/playback/settings flows
├── di/
│   └── AppModule.kt         # Koin module: singletons + viewModel factory
├── model/
│   └── TreeItem.kt          # Flattened folder-tree row model for FolderTreePanel
├── utils/
│   └── Formatting.kt        # Shared formatting helpers (e.g. time display)
└── ui/
    ├── MukkTheme.kt         # Material3 dark color scheme
    ├── MainLayout.kt        # Top-level layout: FolderTree | TrackList | NowPlayingPanel / TransportBar; draggable dividers
    ├── SettingsDialog.kt    # Settings modal: audio output, playback (repeat/shuffle/resume), library management
    ├── NowPlayingPanel.kt   # Album art, metadata (always fully shown), scrollable lyrics filling the rest
    ├── FolderTreePanel.kt   # Expandable folder tree with "Mukk" header + settings/open folder buttons
    ├── TrackListPanel.kt    # Columnar track list (#, File Name, Title, Album, Artist, Duration)
    ├── TransportBar.kt      # Play/pause/stop/skip, waveform seek bar, volume, track info
    └── components/
        ├── SeekBar.kt                    # Seek slider with time labels + formatTime() helper
        ├── WaveformSeekBar.kt            # Seek bar rendered over the track's waveform peaks
        ├── VolumeControl.kt              # Volume slider with icon
        ├── InstantClickable.kt           # Click modifier that fires on press, not release
        └── TrackContextDropdownMenu.kt   # Right-click menu: copy path, copy file, open location

core/model/src/commonMain/kotlin/com/grappim/mukk/core/model/
├── FolderTreeState.kt, FileEntry.kt, MediaTrackData.kt, PlaybackState.kt, PlaybackStatus.kt
├── SettingsState.kt      # RepeatMode, ResumeMode enums, AudioDeviceInfo, SettingsState data class
├── ScanProgress.kt, TrackListColumn.kt, AudioMetadata.kt, Playlist.kt
core/model/src/jvmMain/kotlin/com/grappim/mukk/core/model/
└── MukkLogger.kt          # Centralized logging: object singleton, console + file output

core/data/src/jvmMain/kotlin/com/grappim/mukk/core/data/
├── DatabaseInit.kt        # SQLite connection + schema creation; takes an optional `dbFile`
│                            (defaults to ~/.local/share/mukk/library.db) so tests can point it elsewhere
├── MediaTracks.kt, MediaTrackEntity.kt   # Exposed table + entity + MediaTrackData.toData()
├── TrackRepository.kt     # DB ops: getAllTracks, findByPath, existsByPath, insertIfAbsent, deleteByPath, deleteAll, findByPathPrefix, deleteByPathPrefix
├── WaveformCacheTable.kt, WaveformCacheEntity.kt, WaveformRepository.kt  # cached waveform peaks per track; also deleteByPathPrefix
├── PlaylistsTable.kt, PlaylistEntity.kt, PlaylistRepository.kt  # named, saved folder links (see docs/issues/2026-09-15-playlists-and-queue.md)
└── PreferencesManager.kt  # Simple key-value prefs (~/.local/share/mukk/preferences.properties)

core/data/src/jvmTest/kotlin/com/grappim/mukk/core/data/
└── PlaylistRepositoryTest.kt  # first test in the repo; `./gradlew :core:data:jvmTest`

core/player/src/jvmMain/kotlin/com/grappim/mukk/core/model/player/
├── AudioPlayer.kt         # GStreamer PlayBin wrapper: position polling, device enumeration/selection
└── WaveformExtractor.kt   # Decodes a track to peak data for WaveformSeekBar

core/scanner/src/jvmMain/kotlin/com/grappim/mukk/core/model/scanner/
├── FileScanner.kt         # Recursive directory scanner, delegates DB ops to TrackRepository
├── FileSystemWatcher.kt   # WatchService wrapper: real-time filesystem monitoring, emits FileSystemEvents
└── MetadataReader.kt      # JAudioTagger wrapper: AudioMetadata, readAlbumArt(), readLyrics()
```

## UI Architecture — Three-Panel Layout
Three panels side by side, with a transport bar at the bottom:

1. **Folder Tree** (`FolderTreePanel`, default 250dp, resizable 150–450dp) — expandable tree showing only folders that contain audio files (recursively). Header has "Mukk" title + open folder button. Single-click = select folder (shows tracks), double-click = expand/collapse children. Arrow icon also toggles expand. Playing folder gets subtle highlight + play indicator.
2. **Track List** (`TrackListPanel`, fills remaining space) — columnar table of audio files from the selected folder. Columns: #, File Name, Title, Album, Artist, Duration. Single-click = select/highlight track, double-click = play. Three visual states: playing (primary), selected (surfaceVariant), default.
3. **Now-Playing Panel** (`NowPlayingPanel`, default 280dp, resizable 150–450dp) — shows album art (square, rounded corners, placeholder music icon when missing) and track metadata (title, artist, album, genre + year) at full, unshrinkable size, then a divider, then a scrollable lyrics area filling whatever space is left below it (`weight(1f)`, no manual sizing — metadata is never squeezed to make room). Album art and lyrics read on-the-fly from audio files via `MetadataReader.readAlbumArt()` / `readLyrics()` when playback starts. Shows "No track playing" placeholder when idle.

Panel dividers are draggable (`DraggableDivider` in MainLayout.kt) with `E_RESIZE_CURSOR` hover icon. Widths persist to PreferencesManager (`panel.leftWidth`, `panel.rightWidth`).

The transport bar's seek control (`WaveformSeekBar`) draws the track's decoded waveform peaks
behind the seek position; peaks are extracted once per track (`WaveformExtractor`) and cached
in SQLite via `WaveformRepository` so repeat plays skip re-decoding.

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
- DB access: all Exposed ORM operations go through `TrackRepository` (and `WaveformRepository`, `PlaylistRepository`). Only `core:data` module files import Exposed. When adding new DB operations, add methods to the relevant repository — never use `transaction {}` directly in ViewModel or scanner code.
- DB location: `~/.local/share/mukk/library.db`
- Preferences file: `~/.local/share/mukk/preferences.properties`
- Logging: use `MukkLogger` (`object` singleton, NOT Koin-managed). Use `error`/`warn`/`debug` with `Throwable` to preserve stack traces. A JVM-fatal error (`OutOfMemoryError`, a native crash) never reaches `mukk.log` — it's thrown from an uncaught-exception handler or the AWT event thread, outside any `MukkLogger` call, and only shows up in the run command's own stdout/stderr (the `:composeApp:run` console, or its `tee`d log when launched via `run_in_background`).
- Adding new settings: field in `SettingsState` → update `_settingsState` in ViewModel → persist via `preferencesManager.set()` → restore in `restoreSettings()` → expose in `SettingsDialog.kt`
- Compose Desktop focus: global key events require `FocusRequester` + `.focusable()` + `LaunchedEffect` to request focus. Without this, `onPreviewKeyEvent` won't fire until the user clicks something.

## Build Commands
- Compile check: `./gradlew composeApp:jvmMainClasses` (NOT `composeApp:classes` — that task doesn't exist)
- Lint: `./gradlew detekt` — wired into `check` for every module. Pre-existing findings are grandfathered per-module in `config/detekt/baseline/*.xml`; only new findings fail the build. Regenerate a module's baseline after deliberately accepting new findings: `./gradlew :module:path:detektBaseline`.
- Tests: `./gradlew :core:data:jvmTest` — only `core:data` has a `jvmTest` source set so far (added for `PlaylistRepository`); other modules have none yet.
- Run the app: `./gradlew :composeApp:run`. In the background (`run_in_background`), the wrapper
  process exits quickly (code 0) once the app JVM has launched — that's not the app closing,
  it's a separate long-lived `java ... com.grappim.mukk.MainKt` process (check with `ps aux`).
  Kill that process, not the wrapper, to close the app from a script. `pkill -f
  "com.grappim.mukk.MainKt"` reliably reports exit code 144 in this sandbox even when the kill
  succeeded — it is not evidence of failure; check `ps aux` afterward instead of trusting the
  exit code.
- Before manually verifying a fix against an already-running `:composeApp:run` instance, confirm
  it actually has the new code loaded: compare the running process's start time (`ps -o lstart=
  -p <pid>`) against the relevant `build/classes/kotlin/jvm/main/**/*.class` file's mtime. A JVM
  doesn't hot-reload — an instance started before a later rebuild is silently running stale
  classes, and testing against it (re-launch failed, or a stray background instance survived from
  an earlier step) gives a false pass or a confusing false failure.
- GUI-driving the running app (e.g. `xdotool`, for manual verification of a click-driven bug):
  `wmctrl -l -G` or `xwininfo -root -tree | grep com-grappim-mukk-MainKt` to find the window —
  several unrelated windows share the title "Mukk", so match on the `com-grappim-mukk-MainKt`
  WM_CLASS instead, and target the specific hex window ID everywhere below, not the name.
  `import -window <hex-id> out.png` to screenshot (not `-window Mukk`, which is ambiguous and
  fails). `xdotool mousemove --window <hex-id> x y click 1` to click at coordinates relative to
  the window's own content — they map 1:1 to the screenshot's pixels since the window is
  undecorated. `jcmd <pid> GC.heap_info` reads a running JVM's heap usage without killing it —
  useful for confirming a fix actually bounds memory instead of just "didn't crash this time."
  `xdotool mousedown`/`mousemove`/`mouseup` sequences (simulating a drag) do **not** reliably
  trigger Compose Desktop's `detectDragGestures` callbacks, even with many small incremental
  moves and delays — confirmed against a panel-width `DraggableDivider`, which never responded
  to a simulated drag though clicks and scroll wheel worked fine. A screenshot-only check of a
  resize/drag fix proves the static geometry, not that the drag gesture itself still works —
  changing the relevant preference value directly and relaunching, or asking the user to try the
  actual drag, is the only way to verify that part.

## Gotchas & Import Paths

### Exposed ORM v1
- Critical imports differ from pre-v1: `deleteAll` → `org.jetbrains.exposed.v1.jdbc.deleteAll`, `eq` → `org.jetbrains.exposed.v1.core.eq`, `like` → `org.jetbrains.exposed.v1.core.like`, `deleteWhere` → `org.jetbrains.exposed.v1.jdbc.deleteWhere`, `transaction` → `org.jetbrains.exposed.v1.jdbc.transactions.transaction`
- `transaction()` return type inference can fail — may need explicit type parameter
- `SqlExpressionBuilder.eq`/`.like` need explicit import for `find()` queries; `deleteWhere` is the bulk-delete-by-predicate sibling of `deleteAll`, for deleting by a column condition instead of clearing a whole table
- `EntityClass.new {}` lambda properties should use `this.` prefix to disambiguate
- `core:data`'s `jvmMain` never declares `kotlinx-coroutines-core` itself — it arrives transitively via Exposed. `TrackRepository`/`WaveformRepository`/`PlaylistRepository`'s `withContext(Dispatchers.IO)` calls rely on that; swapping out Exposed would silently break coroutine usage until it's added explicitly

### Koin Imports
- `viewModel{}` DSL: `org.koin.core.module.dsl.viewModel`
- `koinViewModel()`: `org.koin.compose.viewmodel.koinViewModel`
- `koinInject()`: `org.koin.compose.koinInject`
- `startKoin`/`stopKoin`: `org.koin.core.context`

### Compose Desktop
- `Icons.Filled.VolumeUp` is deprecated — use `Icons.AutoMirrored.Filled.VolumeUp`
- `MenuAnchorType` is deprecated — use `ExposedDropdownMenuAnchorType`
- Material Icons Extended: `compose.materialIconsExtended` in JetBrains compose plugin DSL, add to `jvmMain.dependencies`
- Never key a custom `Modifier.pointerInput(key1, ...)` gesture detector on inline callback
  lambdas (`pointerInput(onClick, onDoubleClick) { ... }`). If any of those callbacks trigger a
  state change that recomposes the caller (e.g. a drag callback that updates drag-offset state),
  the recreated lambda changes the key and Compose restarts the gesture coroutine mid-gesture —
  silently swallowing the eventual pointer-up before an `onDragEnd`-style callback ever fires.
  Key on a stable value (`Unit`, or a stable id) instead, and read the latest callbacks inside the
  gesture block via `rememberUpdatedState`. Found in `PlaylistTabBar.kt`'s drag-to-reorder: it
  visually reordered tabs but the reorder was never persisted, because `onDragEnd` never ran.
- Don't nest two `Modifier.verticalScroll()` containers (a scrollable parent wrapping a
  scrollable child) to make an "outer scrolls if the inner content doesn't fit" fallback —
  mouse-wheel input over the inner scrollable gets captured by the outer one on Compose Desktop
  instead of scrolling the inner content first, dragging unrelated sibling content along with
  it. If one section of a `Column` must always render at its full natural size while a sibling
  absorbs any size deficit, give the protected section no explicit height and put it first
  (non-weighted children are measured in order, each getting whatever's left) — Compose's own
  layout clamps the sibling for free, no scroll wrapper or manual floor/clamp math needed.
  Found in `NowPlayingPanel.kt`: wrapping the whole panel in `verticalScroll` to keep album art
  visible on window-shrink instead made scrolling the lyrics text drag the whole panel (album
  art included) on every wheel tick.

### GStreamer Device API
- Device enumeration: `DeviceMonitor` from `org.freedesktop.gstreamer.device`
- Filter audio sinks: `monitor.addFilter("Audio/Sink", null)`, then `monitor.start()`, `monitor.devices`, `monitor.stop()`
- Create sink: `device.createElement("audio-sink")`, set on PlayBin: `playBin.set("audio-sink", element)`, reset to default: `playBin.set("audio-sink", null)`

### detekt
- This detekt version (`dev.detekt` 2.x line) has no `ignoreAnnotated` option for `FunctionNaming` — see the comment in `config/detekt/detekt.yml` for why `functionPattern` is widened instead of excluding `@Composable`.

### Coroutine cancellation
- `Job.cancel()` is cooperative and only takes effect at an actual suspension point. Two things
  in this codebase are atomic from cancellation's perspective and won't stop early no matter how
  fast `cancel()` is called: (1) a hot loop with no suspending call in its common branch — add
  `ensureActive()` (from `kotlinx.coroutines.ensureActive`) inside the loop, once per iteration,
  to make it observable; (2) a single suspend call that does one blocking unit of work start-to-
  finish (e.g. one Exposed `transaction { }` fetching thousands of rows via
  `withContext(Dispatchers.IO)`) — it must run to completion before a pending cancellation can
  even be thrown, so `cancel()` alone cannot prevent it from overlapping with a differently-
  triggered new call.
- When a rapid, repeated trigger (a fast click, a fast re-selection) must never have more than one
  such non-interruptible call in flight at once, `cancel()` isn't enough even paired with
  `ensureActive()` checkpoints — replace it with `previousJob?.cancelAndJoin()` as the *first*
  line of the new coroutine, before it does any of its own work. This suspends until the previous
  one has fully stopped, which strictly serializes the non-interruptible phases regardless of
  trigger rate, with no debounce window to tune and no regression on the common single-trigger
  case (nothing to wait for when nothing else is in flight). A fixed-time UI debounce alone bounds
  trigger *rate*, not call *overlap* — it fails once a single call can outlast the debounce
  window, which gets more likely, not less, exactly when the system is already under memory
  pressure. Found via `activatePlaylist()`'s playlist-switch OOM
  (`docs/issues/2026-09-15-playlist-switch-oom.md`) — `cancelAndJoin()` is the pattern used there.

## Android/Compose Rules

- Do not use early returns in Composable functions — use conditional wrapping
- Lambda parameters: present tense (`onClick` not `onClicked`)
- Prefer `kotlinx-collections-immutable` (`ImmutableList`, `persistentListOf()`) over `List`/`MutableList` in state classes and Composable parameters for stable recomposition

## Dependency Injection (Koin)
All dependencies are wired via Koin in `di/AppModule.kt`. `DatabaseInit`, `PreferencesManager`, `MetadataReader`, `TrackRepository`, `WaveformRepository`, `PlaylistRepository`, `FileScanner`, `AudioPlayer`, `FileSystemWatcher`, `WaveformExtractor` are `single{}` singletons. `MukkViewModel` is registered via `viewModel{}`. `main.kt` calls `startKoin` before the Compose window. `App.kt` retrieves `MukkViewModel` via `koinViewModel()` and `PreferencesManager` via `koinInject()`. When adding a new service: create the class → register in `appModule` → inject via constructor (for non-Compose code) or `koinInject()` (for composables).

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
| `trackList.columnWidths` | String | `""` | MukkViewModel (`\|`-delimited `ENUM=width` pairs) |
| `playback.repeatMode` | String | `"OFF"` | MukkViewModel (RepeatMode enum name) |
| `playback.shuffle` | String | `"false"` | MukkViewModel |
| `playback.resumeMode` | String | `"PAUSED"` | MukkViewModel (ResumeMode enum name) |
| `playback.positionMs` | Long | `0` | main.kt (saved on window close, for resume-on-startup) |
| `playback.durationMs` | Long | `0` | main.kt (saved on window close, for resume-on-startup) |
| `playback.wasPlaying` | Boolean | `false` | main.kt (saved on window close, for resume-on-startup) |
| `audio.device` | String | `"auto"` | MukkViewModel |
| `playlist.activeId` | Long | `0` | main.kt (`0` = none; set by the Default-playlist migration on first run after upgrade) |

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
- Waveform seek bar (peaks extracted per track, cached in SQLite via `WaveformRepository`)
- Resume playback across restarts (position/duration/was-playing persisted on window close; `ResumeMode` PAUSED/PLAYING setting controls whether it auto-resumes playing)
- Single-instance app lock: relaunching focuses the existing window instead of opening a second one (`SingleInstance.kt`)
- Multi-module split: non-UI logic lives in `core:model`/`core:data`/`core:player`/`core:scanner`; `composeApp` holds only UI + ViewModel + DI wiring

## Backlog

`docs/usability-gaps.md` — survey of features other desktop players have that Mukk lacks
(search, multi-root library, play queue, gapless/crossfade/ReplayGain, tag editing,
ratings, synced lyrics, multi-select, MPRIS, theming). Not yet prioritized; run any picked-up
item through `investigate-issue` first.

## What this file is not

**Point, don't copy.** Where another file owns a fact, link it and stop. A version number
restated here is a second copy free to drift from the first, and it will.

- Versions → `gradle/libs.versions.toml` (Gradle itself → `gradle/wrapper/gradle-wrapper.properties`)
- Status/progress of an in-flight feature or fix → that feature's own `docs/issues/*.md`
  `Status` field and its `### Part N` checkboxes (see "How work happens here" below) — never
  restated here or in chat as the record of how far something got.

**Not a growing catalogue, either.** A convention that fits in a sentence or two, with at most
one example, belongs here. The moment a rule starts accumulating dated, confirmed cases or a
worked-example script, it has become reference material earned by a specific investigation, not
a day-to-day rule every session needs to read — split it into its own doc under `docs/` and
leave a one-line pointer where the rule used to live.

## Shared skills and agents

Skills live in the **`agentic-grappim`** repo (a sibling checkout at `../agentic-grappim`) and
are symlinked into this repo's own `.claude/skills/` — **per clone, not per machine**, unlike
other `grappim` projects that wire them into `~/.claude/skills/` instead. Cloning Mukk elsewhere
needs `agentic-grappim` checked out as a sibling directory for the symlinks to resolve.

This project uses: `finalize`, `investigate-issue`, `update-gradle-wrapper`, `adversarial-review`,
`bro`, `compose-stability-audit`. All shared — `.claude/agents/` holds none of its own.

Consequences worth knowing before touching one:

- **An edit there changes behaviour in every project that symlinks it**, not just Mukk. That is
  the point of the repo, not a hazard — but it has to be committed in `agentic-grappim`, not here.
- **Never edit a shared skill for a fact about this project.** A Mukk-specific fact belongs in
  this file, not in the skill itself.
- A stale in-repo copy of a shared skill would silently shadow the real one — don't create one.

## How work happens here

Every non-trivial feature or fix goes through the **`investigate-issue`** skill first:
evidence-based findings, options with real tradeoffs, a recommendation — written to
`docs/issues/<date-or-issue>-<slug>.md`, not decided in chat. Implementation never starts
before that doc's `Status` says `Approved`.

Once approved, the doc carries an **Implementation plan**: numbered, independently-shippable
`### Part N` sections, each with its own `Verify:` line (a Gradle task, or an explicit manual
check in the running app when nothing else can prove it). Each part marks itself `[ ]` when
written and `[x]` once landed, with a one-line `Landed:` note for anything that deviated from
the plan — the same doc is both the plan and the only progress record; nothing is duplicated
here or in memory.

**One part per session.** A session that finishes a part does not chain into the next one —
say what landed and stop; the next part starts in a fresh session. This is deliberate, not a
context-budget accident: the doc is written so a session with zero memory of the planning
conversation can pick up any single part cold.

**When told to proceed with no more detail than that** ("let's proceed with the task", "do
the next part," etc.):

1. Find `docs/issues/*.md` doc(s) with `Status: Approved` or `Status: In progress` that still
   have an unchecked `### Part N`. Exactly one such part across all docs → do that one. More
   than one candidate → ask which, don't guess.
2. Do exactly what that part describes — nothing from a later part, nothing outside it.
3. Run its `Verify:` line. "Manual" means something only a human can observe — a visual or
   audio check in the running app's window — and for that, say so explicitly and ask the user
   to confirm rather than assuming it passed. It does not mean a shell command, SQL query, or
   file read the agent has tool access to run itself (e.g. checking a DB row landed correctly)
   — run those directly and report the result instead of asking the user to run them.
4. Tick the part, add a `Landed:` note if anything deviated, and update the doc's `Status`
   (`In progress`, or `Done` once the last part lands).
5. Close out per "Close-out" below.

Don't start a part whose earlier parts in the same doc aren't ticked yet, and don't widen a
part's scope because a nearby improvement is tempting — that's a new doc, or a note in this
one, not scope creep on the part in flight.

## Close-out

At the end of each session that changed code, without being asked:

1. Run the **`/finalize` skill** — the work almost always taught something the plan didn't know,
   and this is where it gets written down instead of dying with the context.
2. **Check the docs for claims the work just made false.** Grep for what changed rather than
   trusting a read-through.
3. One commit per logical change, with a plain-English subject line.

## Changing a check means saying so

The gate (`detekt`, CI) constrains the code a session writes; nothing constrains a session from
widening it so its own change passes. `.github/workflows/guardrails.yml` doesn't prevent that —
it makes it impossible to do quietly. A commit trips it by touching `.github/`, `build-logic/`,
or `config/detekt/`; by touching `gradle/libs.versions.toml`'s `detekt` or `composeRules` version
keys specifically (an ordinary dependency bump on `kotlin`, `koin`, `exposed`, etc. doesn't trip
it); or by adding an `@Ignore` or a `@Suppress`. Any of those needs a line in the commit message:

```
Gate-change: what was widened, and why
```

That is an opt-in, not a veto — widening a gate is often right. Run it before committing:
`.github/scripts/check-guardrails.sh HEAD~1..HEAD`.

`master` is protected by a GitHub ruleset (PRs required, `guardrails` + `build` required status
checks, no force-push/deletion) with the repo owner as a bypass actor — a direct push to `master`
still works for the owner, but the intended flow is a PR so the checks actually run before merge.

## Verification

**"Done" means the relevant check ran and passed.** If it didn't run, say that instead.

- **A narrow pass proves your change works, not that you broke nothing.** When a failure shows
  up alongside your change, A/B it against a clean tree (`git stash -u`) before assuming you
  caused it — or that you didn't.
- **A check that looks the same whether the thing worked or not is not a check.** Before
  trusting one, name what it would show if the change had done nothing.
- **Before/after comparisons need equally fresh runs.** A baseline taken from a partially cached
  build measures a different universe than the after-run.
- **Confirm the baseline shows the pre-change value before trusting it**, and copy each report to
  a distinct path immediately.

## Plain technical English

Write for a reader whose first language is not English.

- **One word per idea.** Pick a term and reuse it. No synonyms for variety.
- **Short sentences.** Around 20 words for an instruction, 25 for an explanation. One
  instruction per sentence.
- **Active voice.** "Run the task", not "the task should be run".
- **One topic per paragraph**, six sentences at most.
- **Domain terms are fine.** Identifiers, task names, library names and API names are technical
  names — use them exactly, don't paraphrase them into plain words.

Where it yields: **uncertainty and conditions win over brevity.** A short sentence that drops a
real caveat is wrong, not simple.

## Chat replies

Answer in chat as a tl;dr: short, plain, human, straightforward.

- This is about chat only. Docs, code, comments, commit messages: write them however the
  artifact and this file's other rules call for.
- If something genuinely doesn't compress — a real tradeoff, a caveat that changes the answer —
  explain it in full. Don't let that become the default excuse for length.

## Working agreements

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.
- **An answer is not an instruction to act.** If the user states a preference or decision that a
  later, not-yet-requested step will need, record it for when that step is asked for — don't
  treat it as authorization to run the step now.

### Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### Surgical Changes

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

### Don't Break Production in Favor of Tests

Production code must not be shaped by testing needs. If a code path is flaky or can't be
observed deterministically as written, fix or remove the *test* — don't add a seam, injectable
parameter, or abstraction to production code purely so a test can control it. Always ask before
adding any production-code testability seam, even a well-verified one.

### Determinism Over Process

If a task has one correct, computable answer, use a tool for it. Don't ask the agent to follow a
fixed procedure by hand.

- A checksum, a sort order, a date calculation, a schema check: write a script or a hook.
- An agent following prose steps can skip a step, or get one wrong. A script cannot.
- Reserve judgment for what needs judgment: ambiguous input, a plan, a choice between options.

### Goal-Driven Execution

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

### A Real Problem Outside the Task Goes in Writing

Write it into `docs/revisit.md` and keep going — not fixed inline (that makes the diff
unreviewable), not dropped, and not just mentioned in chat. Create the file the first time it's
needed; give the entry enough evidence (`file:line`) that a cold session can act on it.

### Friction Goes in Writing Too

The rule above is for problems in the **code**. This one is for friction in the **tooling**: a
guessed command that failed, an auth error, a check that confidently returned the wrong answer.
Add a line to `docs/frictions.md` (create it if it isn't there) before moving on — one line, past
tense, naming the tool and the surprise. The same friction three times is a fix, not a fourth
line; `/finalize` is where that promotion happens.

## Settled decisions

Weighed and declined — don't re-propose these.

| Not used | Instead | Why |
|---|---|---|
| Machine-wide skills (`~/.claude/skills/`) | Repo-local (`.claude/skills/`) | Solo desktop project; keeps the skill list self-documenting per clone instead of relying on machine state |
| ktlint | detekt only | Simpler footprint for a first lint pass; formatting/style enforcement can be added later if wanted |
| Porting wallosmobile's `CHECKLIST.md`/`Non-negotiables` rule-count tripwire | Path- and version-key-based tripwires only, plus `@Suppress`/`@Ignore` detection | Mukk has neither a `CHECKLIST.md` nor a `Non-negotiables` section; inventing them just to feed the tripwire script would be scope creep |

## Reference projects

Read these rather than guessing; the conventions in this file's process sections are ported
from them.

- `../agentic-grappim` — shared skills/agents source, and the `templates/CLAUDE.md.template`
  this section was ported from.
- `../wallosmobile` — the guardrails CI mechanism (`.github/workflows/guardrails.yml`,
  `.github/scripts/check-guardrails.sh`) this was adapted from, and the
  session-per-unit-of-work discipline its "How work happens here" section describes. Its
  `detekt.yml` and `Non-negotiables`/`CHECKLIST.md` tripwires are Android/multi-module-specific
  and were **not** ported wholesale — see Settled decisions above. Mukk has no single
  `CHECKLIST.md`; each `docs/issues/*.md` doc is its own self-contained plan and progress
  record instead (see "How work happens here" above), since work here is a series of
  independent features/fixes rather than one long numbered build-out.

**Trust their code over their docs.** Another project's `CLAUDE.md` can contradict its own
implementation. Note a drift here when you find one.