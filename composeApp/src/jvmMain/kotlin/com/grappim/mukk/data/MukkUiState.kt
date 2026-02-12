package com.grappim.mukk.data

import androidx.compose.ui.graphics.ImageBitmap
import com.grappim.mukk.player.PlaybackState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class MukkUiState(
    val folderTreeState: FolderTreeState = FolderTreeState(),
    val selectedFolderEntries: ImmutableList<FileEntry> = persistentListOf(),
    val selectedTrackPath: String? = null,
    val playbackState: PlaybackState = PlaybackState(),
    val currentTrack: MediaTrackData? = null,
    val playingFolderPath: String? = null,
    val currentAlbumArt: ImageBitmap? = null,
    val currentLyrics: String? = null,
    val scanProgress: ScanProgress = ScanProgress(),
    val columnConfig: ColumnConfig = DEFAULT_COLUMN_CONFIG,
    val settingsState: SettingsState = SettingsState()
)
