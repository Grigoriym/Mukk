# Mukk — Music Player for Linux

## Project Context
- A desktop music player built with **Kotlin Multiplatform + Compose Desktop**
- Motivated by dissatisfaction with existing Linux players (AIMP broken on Linux, DeaDBeeF has political issues)
- Goal: clean media library viewer + player, no bloat
- Key differentiator: **file-system-based browsing** — two-panel file browser, not a database-driven flat track list

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
├── MukkViewModel.kt         # Central ViewModel: file browser state, playback, navigation
├── data/
│   ├── DatabaseInit.kt      # SQLite connection + schema creation (~/.local/share/mukk/library.db)
│   ├── MediaTracks.kt       # Exposed table definition
│   ├── MediaTrackEntity.kt  # Exposed entity + MediaTrackData data class + toData()
│   └── FileBrowserState.kt  # FileEntry + FileBrowserState data classes
├── player/
│   ├── AudioPlayer.kt       # GStreamer PlayBin wrapper with position polling
│   └── PlaybackState.kt     # PlaybackState data class + Status enum
├── scanner/
│   ├── FileScanner.kt       # Recursive directory scanner, stores tracks in DB
│   └── MetadataReader.kt    # JAudioTagger wrapper returning AudioMetadata
└── ui/
    ├── MukkTheme.kt         # Material3 dark color scheme
    ├── MainLayout.kt        # Top-level layout: Sidebar | FileBrowser | NowPlaying / TransportBar
    ├── Sidebar.kt           # "Library" and "Open Folder" buttons
    ├── FileBrowserPanel.kt  # Library file browser: breadcrumbs, folder/file rows, navigation
    ├── NowPlayingFolderPanel.kt  # Album folder panel: shows sibling tracks, auto-scrolls
    ├── TrackListPanel.kt    # (Legacy) flat track list — not currently wired
    ├── TransportBar.kt      # Play/pause/stop/skip, seek bar, volume, track info
    └── components/
        ├── SeekBar.kt       # Seek slider with time labels
        └── VolumeControl.kt # Volume slider with icon
```

## UI Architecture — Two-Panel File Browser
The main content area has two panels side by side:

1. **Library Browser** (`FileBrowserPanel`) — file/folder tree from the scanned music root. Navigate folders, click files to play. Breadcrumb bar for path navigation.
2. **Now-Playing Folder** (`NowPlayingFolderPanel`) — shows all audio files in the folder of the currently playing track (i.e. the album). Next/Previous cycle within this folder.

```
┌──────────┬──────────────────────┬─────────────────┐
│ Sidebar  │ Library Browser      │ Now Playing      │
│          │ music > Artist > Alb │ (Album folder)   │
│ [Library]│ ..                   │                  │
│ [Open    │ 📁 SubFolder        │ 01 - Track One ◄ │
│  Folder] │ 🎵 01 - Song.flac   │ 02 - Track Two   │
│          │ 🎵 02 - Song.flac   │ 03 - Track Three │
├──────────┴──────────────────────┴─────────────────┤
│  ◄◄  ▶/❚❚  ►►  |  ━━━●━━━  |  🔊 ────           │
└───────────────────────────────────────────────────┘
```

## Key Patterns
- Audio file extensions: `mp3, flac, ogg, wav, aac, opus, m4a` (defined in both `FileScanner` and `MukkViewModel`)
- Directory listing sorts: directories first, then audio files by track number, then by name
- File browser enriches audio files with DB metadata (title, artist, duration) when available
- Native file picker: tries zenity → kdialog → Swing JFileChooser fallback
- DB location: `~/.local/share/mukk/library.db`

## MVP Features
1. **Media library scanner** — scan directories recursively, read tags with JAudioTagger, store in SQLite ✅
2. **File browser UI** — two-panel file-system-based browsing with breadcrumbs ✅
3. **Audio playback** — play/pause/stop/seek via GStreamer, next/prev within album folder ✅
4. **Playback controls UI** — transport bar with seek, volume, track info ✅
5. **Playlist support** — basic playlist management (planned)
