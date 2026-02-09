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
import com.grappim.mukk.data.FileBrowserState
import com.grappim.mukk.data.FileEntry
import com.grappim.mukk.data.MediaTrackData
import com.grappim.mukk.player.PlaybackState

@Composable
fun MainLayout(
    browserState: FileBrowserState,
    nowPlayingEntries: List<FileEntry>,
    nowPlayingFolderName: String?,
    playbackState: PlaybackState,
    currentTrack: MediaTrackData?,
    onLibraryClick: () -> Unit,
    onOpenFolderClick: () -> Unit,
    onNavigateToDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onFileClick: (FileEntry) -> Unit,
    onNowPlayingFileClick: (FileEntry) -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Double) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Sidebar(
                onLibraryClick = onLibraryClick,
                onOpenFolderClick = onOpenFolderClick
            )

            FileBrowserPanel(
                state = browserState,
                currentTrackPath = playbackState.currentTrackPath,
                onNavigateToDirectory = onNavigateToDirectory,
                onNavigateUp = onNavigateUp,
                onFileClick = onFileClick,
                onBreadcrumbClick = onBreadcrumbClick,
                modifier = Modifier.weight(1f)
            )

            VerticalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            NowPlayingFolderPanel(
                entries = nowPlayingEntries,
                folderName = nowPlayingFolderName,
                currentTrackPath = playbackState.currentTrackPath,
                onFileClick = onNowPlayingFileClick,
                modifier = Modifier.weight(0.4f)
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
