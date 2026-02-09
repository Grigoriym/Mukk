package com.grappim.mukk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.grappim.mukk.data.FileEntry
import com.grappim.mukk.data.FolderTreeState
import com.grappim.mukk.data.MediaTrackData
import com.grappim.mukk.player.PlaybackState
import java.io.File

@Composable
fun MainLayout(
    folderTreeState: FolderTreeState,
    selectedFolderEntries: List<FileEntry>,
    playbackState: PlaybackState,
    currentTrack: MediaTrackData?,
    selectedTrackPath: String?,
    playingFolderPath: String?,
    onToggleExpand: (String) -> Unit,
    onSelectFolder: (String) -> Unit,
    onOpenFolderClick: () -> Unit,
    onTrackClick: (FileEntry) -> Unit,
    onTrackDoubleClick: (FileEntry) -> Unit,
    getSubfolders: (String) -> List<Pair<File, Boolean>>,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    albumArt: ByteArray?,
    lyrics: String?,
    onVolumeChange: (Double) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            FolderTreePanel(
                folderTreeState = folderTreeState,
                playingFolderPath = playingFolderPath,
                onToggleExpand = onToggleExpand,
                onSelectFolder = onSelectFolder,
                onOpenFolderClick = onOpenFolderClick,
                getSubfolders = getSubfolders
            )

            VerticalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            TrackListPanel(
                entries = selectedFolderEntries,
                currentTrackPath = playbackState.currentTrackPath,
                selectedTrackPath = selectedTrackPath,
                onTrackClick = onTrackClick,
                onTrackDoubleClick = onTrackDoubleClick,
                modifier = Modifier.weight(1f)
            )

            VerticalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            NowPlayingPanel(
                currentTrack = currentTrack,
                albumArt = albumArt,
                lyrics = lyrics
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        TransportBar(
            playbackState = playbackState,
            currentTrackTitle = currentTrack?.title ?: "",
            currentTrackArtist = currentTrack?.artist ?: "",
            onPlayPause = onPlayPause,
            onStop = onStop,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeek = onSeek,
            onVolumeChange = onVolumeChange
        )
    }
}
