package com.grappim.mukk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grappim.mukk.data.FileEntry
import com.grappim.mukk.data.FolderTreeState
import com.grappim.mukk.data.MediaTrackData
import com.grappim.mukk.data.PreferencesManager
import com.grappim.mukk.player.PlaybackState
import java.awt.Cursor
import java.io.File

private val MIN_PANEL_WIDTH = 150.dp
private val MAX_PANEL_WIDTH = 450.dp
private val DEFAULT_LEFT_WIDTH = 250.dp
private val DEFAULT_RIGHT_WIDTH = 280.dp

@Composable
fun MainLayout(
    folderTreeState: FolderTreeState,
    selectedFolderEntries: List<FileEntry>,
    playbackState: PlaybackState,
    currentTrack: MediaTrackData?,
    selectedTrackPath: String?,
    playingFolderPath: String?,
    isScanning: Boolean,
    onToggleExpand: (String) -> Unit,
    onSelectFolder: (String) -> Unit,
    onOpenFolderClick: () -> Unit,
    onRescanClick: () -> Unit,
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
    var leftPanelWidth by remember {
        mutableStateOf(
            PreferencesManager.getInt("panel.leftWidth", DEFAULT_LEFT_WIDTH.value.toInt()).dp
        )
    }
    var rightPanelWidth by remember {
        mutableStateOf(
            PreferencesManager.getInt("panel.rightWidth", DEFAULT_RIGHT_WIDTH.value.toInt()).dp
        )
    }

    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            FolderTreePanel(
                folderTreeState = folderTreeState,
                playingFolderPath = playingFolderPath,
                isScanning = isScanning,
                onToggleExpand = onToggleExpand,
                onSelectFolder = onSelectFolder,
                onOpenFolderClick = onOpenFolderClick,
                onRescanClick = onRescanClick,
                getSubfolders = getSubfolders,
                modifier = Modifier.width(leftPanelWidth)
            )

            DraggableDivider(
                onDrag = { deltaPx ->
                    val deltaDp = with(density) { deltaPx.toDp() }
                    leftPanelWidth = (leftPanelWidth + deltaDp).coerceIn(MIN_PANEL_WIDTH, MAX_PANEL_WIDTH)
                },
                onDragEnd = {
                    PreferencesManager.set("panel.leftWidth", leftPanelWidth.value.toInt())
                }
            )

            TrackListPanel(
                entries = selectedFolderEntries,
                currentTrackPath = playbackState.currentTrackPath,
                selectedTrackPath = selectedTrackPath,
                onTrackClick = onTrackClick,
                onTrackDoubleClick = onTrackDoubleClick,
                modifier = Modifier.weight(1f)
            )

            DraggableDivider(
                onDrag = { deltaPx ->
                    val deltaDp = with(density) { deltaPx.toDp() }
                    rightPanelWidth = (rightPanelWidth - deltaDp).coerceIn(MIN_PANEL_WIDTH, MAX_PANEL_WIDTH)
                },
                onDragEnd = {
                    PreferencesManager.set("panel.rightWidth", rightPanelWidth.value.toInt())
                }
            )

            NowPlayingPanel(
                currentTrack = currentTrack,
                albumArt = albumArt,
                lyrics = lyrics,
                modifier = Modifier.width(rightPanelWidth)
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

@Composable
private fun DraggableDivider(
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(4.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x)
                }
            }
    )
}
