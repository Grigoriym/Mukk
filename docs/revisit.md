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
