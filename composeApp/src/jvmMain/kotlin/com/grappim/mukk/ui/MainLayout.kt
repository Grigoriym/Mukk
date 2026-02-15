package com.grappim.mukk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.grappim.mukk.core.model.FileEntry
import com.grappim.mukk.data.MukkUiState
import com.grappim.mukk.data.PreferencesManager
import com.grappim.mukk.core.model.TrackListColumn
import java.awt.Cursor
import java.io.File

private val MIN_PANEL_WIDTH = 150.dp
private val MAX_PANEL_WIDTH = 450.dp
private val DEFAULT_LEFT_WIDTH = 250.dp
private val DEFAULT_RIGHT_WIDTH = 280.dp

@Composable
fun MainLayout(
    uiState: MukkUiState,
    preferencesManager: PreferencesManager,
    onToggleColumn: (TrackListColumn) -> Unit,
    onToggleExpand: (String) -> Unit,
    onSelectFolder: (String) -> Unit,
    onOpenFolderClick: () -> Unit,
    onRescanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTrackClick: (FileEntry) -> Unit,
    onTrackDoubleClick: (FileEntry) -> Unit,
    getSubfolders: (String) -> List<Pair<File, Boolean>>,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Double) -> Unit
) {
    var leftPanelWidth by remember {
        mutableStateOf(preferencesManager.panelLeftWidth.dp)
    }
    var rightPanelWidth by remember {
        mutableStateOf(preferencesManager.panelRightWidth.dp)
    }

    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            FolderTreePanel(
                folderTreeState = uiState.folderTreeState,
                playingFolderPath = uiState.playingFolderPath,
                scanProgress = uiState.scanProgress,
                onToggleExpand = onToggleExpand,
                onSelectFolder = onSelectFolder,
                onOpenFolderClick = onOpenFolderClick,
                onRescanClick = onRescanClick,
                onSettingsClick = onSettingsClick,
                getSubfolders = getSubfolders,
                modifier = Modifier.width(leftPanelWidth)
            )

            DraggableDivider(
                onDrag = { deltaPx ->
                    val deltaDp = with(density) { deltaPx.toDp() }
                    leftPanelWidth = (leftPanelWidth + deltaDp).coerceIn(MIN_PANEL_WIDTH, MAX_PANEL_WIDTH)
                },
                onDragEnd = {
                    preferencesManager.panelLeftWidth = leftPanelWidth.value.toInt()
                }
            )

            TrackListPanel(
                entries = uiState.selectedFolderEntries,
                currentTrackPath = uiState.playbackState.currentTrackPath,
                selectedTrackPath = uiState.selectedTrackPath,
                columnConfig = uiState.columnConfig,
                onToggleColumn = onToggleColumn,
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
                    preferencesManager.panelRightWidth = rightPanelWidth.value.toInt()
                }
            )

            NowPlayingPanel(
                currentTrack = uiState.currentTrack,
                albumArt = uiState.currentAlbumArt,
                lyrics = uiState.currentLyrics,
                modifier = Modifier.width(rightPanelWidth)
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        TransportBar(
            playbackState = uiState.playbackState,
            currentTrackTitle = uiState.currentTrack?.title ?: "",
            currentTrackArtist = uiState.currentTrack?.artist ?: "",
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
