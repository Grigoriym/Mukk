package com.grappim.mukk.data

import com.grappim.mukk.player.PlaybackState

data class MukkUiState(
    val folderTreeState: FolderTreeState = FolderTreeState(),
    val selectedFolderEntries: List<FileEntry> = emptyList(),
    val selectedTrackPath: String? = null,
    val playbackState: PlaybackState = PlaybackState(),
    val currentTrack: MediaTrackData? = null,
    val playingFolderPath: String? = null,
    val currentAlbumArt: ByteArray? = null,
    val currentLyrics: String? = null,
    val isScanning: Boolean = false,
    val columnConfig: ColumnConfig = DEFAULT_COLUMN_CONFIG
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MukkUiState) return false
        return folderTreeState == other.folderTreeState &&
            selectedFolderEntries == other.selectedFolderEntries &&
            selectedTrackPath == other.selectedTrackPath &&
            playbackState == other.playbackState &&
            currentTrack == other.currentTrack &&
            playingFolderPath == other.playingFolderPath &&
            currentAlbumArt.contentEquals(other.currentAlbumArt) &&
            currentLyrics == other.currentLyrics &&
            isScanning == other.isScanning &&
            columnConfig == other.columnConfig
    }

    override fun hashCode(): Int {
        var result = folderTreeState.hashCode()
        result = 31 * result + selectedFolderEntries.hashCode()
        result = 31 * result + (selectedTrackPath?.hashCode() ?: 0)
        result = 31 * result + playbackState.hashCode()
        result = 31 * result + (currentTrack?.hashCode() ?: 0)
        result = 31 * result + (playingFolderPath?.hashCode() ?: 0)
        result = 31 * result + (currentAlbumArt?.contentHashCode() ?: 0)
        result = 31 * result + (currentLyrics?.hashCode() ?: 0)
        result = 31 * result + isScanning.hashCode()
        result = 31 * result + columnConfig.hashCode()
        return result
    }
}
