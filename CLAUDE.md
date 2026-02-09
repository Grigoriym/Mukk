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
│   └── FileBrowserState.kt  # FileEntry + FolderTreeState data classes
├── player/
│   ├── AudioPlayer.kt       # GStreamer PlayBin wrapper with position polling
│   └── PlaybackState.kt     # PlaybackState data class + Status enum
├── scanner/
│   ├── FileScanner.kt       # Recursive directory scanner, stores tracks in DB
│   └── MetadataReader.kt    # JAudioTagger wrapper returning AudioMetadata
└── ui/
    ├── MukkTheme.kt         # Material3 dark color scheme
    ├── MainLayout.kt        # Top-level layout: FolderTree | TrackList / TransportBar
    ├── FolderTreePanel.kt   # Expandable folder tree with "Mukk" header + open folder button
    ├── TrackListPanel.kt    # Columnar track list (#, File Name, Title, Album, Artist, Duration)
    ├── TransportBar.kt      # Play/pause/stop/skip, seek bar, volume, track info
    └── components/
        ├── SeekBar.kt       # Seek slider with time labels + formatTime() helper
        └── VolumeControl.kt # Volume slider with icon
```

## UI Architecture — AIMP-Style Folder Tree + Track List
Two panels side by side:

1. **Folder Tree** (`FolderTreePanel`, fixed 250dp) — expandable tree showing only folders that contain audio files (recursively). Header has "Mukk" title + open folder button. Clicking a folder selects it (shows tracks in right panel). Clicking the expand arrow toggles children.
2. **Track List** (`TrackListPanel`, fills remaining space) — columnar table of audio files from the selected folder. Columns: #, File Name, Title, Album, Artist, Duration. Playing track is highlighted.

```
┌─────────────────────┬──────────────────────────────────────┐
│ Mukk          [+ ]  │  #  File Name   Title  Album  Artist │
│                     │  01 01-Song..  Cheated Time.. Prayi. │
│ ▾ 📁 music         │  01 01.Can..   Can't.. Preda. Prayi. │
│   ▸ 📁 Megadeth    │  01 01-Rise..  Rise A. A Cry. Prayi. │
│   ▸ 📁 Slayer      │  02 02-AllD..  All Da. Time.. Prayi. │
│   ▾ 📁 Praying M.◄ │  02 02.She'..  She's.. Preda. Prayi. │
│     📁 Album1      │  ...                                 │
│     📁 Album2      │                                      │
├─────────────────────┴──────────────────────────────────────┤
│  ◄◄  ▶/❚❚  ►►  |  ━━━●━━━  |  🔊 ────                    │
└────────────────────────────────────────────────────────────┘
```

## Key State Models
- **`FolderTreeState`** — `rootPath`, `expandedPaths: Set<String>`, `selectedPath`
- **`FileEntry`** — `file: File`, `isDirectory`, `name`, `trackData: MediaTrackData?`
- **`selectedFolderEntries`** — audio-only `FileEntry` list for the selected folder (no directories)
- Next/Previous track cycles within `selectedFolderEntries`

## Key Patterns
- Audio file extensions: `mp3, flac, ogg, wav, aac, opus, m4a` (defined in both `FileScanner` and `MukkViewModel`)
- Folder tree hides folders with no audio files (recursive check via `containsAudioFiles()` using `walkTopDown()`)
- Track list enriches audio files with DB metadata (title, artist, duration) when available; shows filename + "-" for unscanned files
- `getSubfolders()` is passed as a callback to FolderTreePanel and called inside `remember{}` — it's synchronous file I/O, works because tree builds are memoized on `expandedPaths` changes
- Native file picker: tries zenity → kdialog → Swing JFileChooser fallback
- DB location: `~/.local/share/mukk/library.db`

## Implementation Notes
- **Deleted files** (replaced by folder tree approach): `Sidebar.kt`, `FileBrowserPanel.kt`, `NowPlayingFolderPanel.kt`, `FileBrowserState` data class
- The old breadcrumb navigation (`navigateToDirectory`, `navigateUp`, `navigateToRoot`, `buildPathSegments`) was removed from ViewModel
- `containsAudioFiles()` uses `walkTopDown()` which can be slow on very large directory trees — may need caching if performance is an issue
- Pre-existing deprecation: `Icons.Filled.VolumeUp` in TransportBar.kt — should use `Icons.AutoMirrored.Filled.VolumeUp`

## MVP Features
1. **Media library scanner** — scan directories recursively, read tags with JAudioTagger, store in SQLite ✅
2. **Folder tree UI** — AIMP-style expandable folder tree, hides empty folders ✅
3. **Track list UI** — columnar table with file name, title, album, artist, duration ✅
4. **Audio playback** — play/pause/stop/seek via GStreamer, next/prev within selected folder ✅
5. **Playback controls UI** — transport bar with seek, volume, track info ✅
6. **Playlist support** — basic playlist management (planned)
