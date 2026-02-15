package com.grappim.mukk

import androidx.compose.ui.graphics.ImageBitmap
import com.grappim.mukk.core.model.ColumnConfig
import com.grappim.mukk.core.model.DEFAULT_COLUMN_CONFIG
import com.grappim.mukk.core.model.FileEntry
import com.grappim.mukk.core.model.FolderTreeState
import com.grappim.mukk.core.model.MediaTrackData
import com.grappim.mukk.core.model.PlaybackState
import com.grappim.mukk.core.model.ScanProgress
import com.grappim.mukk.core.model.SettingsState
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