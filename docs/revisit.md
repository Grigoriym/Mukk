# Revisit

Real problems noticed outside the task at hand — not fixed inline, not dropped.

## FolderTreePanel's "Open Folder" button bypasses the playlist model

`FolderTreePanel.kt:159-166` (the `CreateNewFolder` icon in `HeaderRow`) still calls
`onOpenFolderClick` → `App.kt:102-109` → `viewModel.scanDirectory(path)`
(`MukkViewModel.kt:112-133`), which sets `_folderTreeState` directly and never touches
`_activePlaylistId` or the `Playlists` table.

Since the playlist tab strip (`docs/issues/2026-09-15-playlists-and-queue.md` Part 4) shipped, this
is now a second, inconsistent way to open a folder: clicking it changes the visible folder tree and
track list without changing which tab is highlighted as active, or creating a `Playlists` row for
the newly opened folder at all. A user who clicks it would see content that doesn't match any tab,
and the folder they opened isn't remembered as a playlist across restarts the way one created via
the tab strip's `+` button is.

Not fixed as part of Part 4 because the implementation plan's checklist for that part didn't call
for touching `FolderTreePanel.kt`, and doing so would have widened that part's diff beyond what was
approved. Worth a decision: either remove the button (the tab strip's `+` now covers folder-adding),
or reroute it through `onCreatePlaylist` so it participates in the same model.

## Rapid playlist-tab switching OOMs the app

Reproduced 2026-09-15 while manually verifying
`docs/issues/2026-09-15-playlist-switch-lag.md` Part 1: clicking between two playlist tabs several
times in quick succession (one of them the ~50k-file "Default" library) crashed the whole JVM.
`~/.local/share/mukk/mukk.log` and the `:composeApp:run` console both end in
`java.lang.OutOfMemoryError: Java heap space`, thrown from the AWT event thread, a `TimerQueue`
uncaught-exception handler, and a `DefaultDispatcher-worker` running
`FileScanner.scan()` (`FileScanner.kt:29`) — i.e. it happened mid-scan, not on startup.

`MukkViewModel.activatePlaylist()` (`MukkViewModel.kt:548-572`) does
`viewModelScope.launch { ... trackRepository.findByPathPrefix(...); fileScanner.scan(...); ... }`
on every call with no job stored or cancelled. Contrast `waveformJob`/`watcherCollectionJob`
(`MukkViewModel.kt:93, 231-232, 638-640`), which are explicitly cancelled before starting a
replacement. Each rapid tab click starts a fully independent coroutine that walks the folder,
holds its own `List<FileEntry>` (or `List<MediaTrackData>` for large folders), and runs
`fileScanner.scan()` concurrently with the others — clicking through several tabs in a few seconds
can have multiple ~50k-entry scans alive in the heap at once, on a JVM started with `-Xmx512m`
(`composeApp/build.gradle.kts` run config). Not introduced by Part 1's change (which only swaps
what the *second* walk does inside that same unguarded coroutine) — this is pre-existing in
`activatePlaylist()`'s launch itself.

Worth a decision: store the returned `Job` from `activatePlaylist()`'s `launch` and `cancel()` any
in-flight one before starting a new one (same pattern as `waveformJob`), so a rapid run of clicks
only keeps the latest switch's work alive.

## Active playlist tab reads as the inactive one

Reported 2026-09-15 by the user while manually verifying
`docs/issues/2026-09-15-playlist-switch-lag.md` Part 1, with two playlist tabs visible: the active
tab was harder to pick out than the inactive one — visually backwards from what "selected" should
look like.

`PlaylistTab` (`composeApp/src/jvmMain/kotlin/com/grappim/mukk/ui/PlaylistTabBar.kt:160-165`) gives
the active tab `background = MaterialTheme.colorScheme.surface` and the inactive tab
`MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)`, sitting on a row background that's
also `surfaceVariant` (`:78`, alpha 0.3f). In the app's dark palette
(`MukkTheme.kt:16,18`: `surface = 0xFF16213E`, `surfaceVariant = 0xFF0F3460`), `surfaceVariant` is
the visually *lighter* of the two (perceived luminance ~47 vs. ~33 on a 0-255 scale) — so the
inactive tabs, blended over the equally-`surfaceVariant` row, come out brighter/more prominent than
the active one, which only gets the darker `surface` plus a colored (`primary`) text label to stand
out. The text-color difference is there but reads as too subtle against the background swap working
against it.

Not fixed inline — a color/contrast tweak to `PlaylistTab`'s active/inactive styling, out of scope
for the perf fix in flight. Worth a decision: give the active tab a lighter/raised background than
its neighbors (the conventional direction), e.g. swap which role uses `surface` vs.
`surfaceVariant`, or add a visible accent underline/border instead of relying on background alone.
