package com.grappim.mukk.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.grappim.mukk.data.ColumnConfig
import com.grappim.mukk.data.FileEntry
import com.grappim.mukk.data.TrackListColumn
import com.grappim.mukk.ui.components.formatTime
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun TrackListPanel(
    entries: List<FileEntry>,
    currentTrackPath: String?,
    selectedTrackPath: String?,
    columnConfig: ColumnConfig,
    onToggleColumn: (TrackListColumn) -> Unit,
    onTrackClick: (FileEntry) -> Unit,
    onTrackDoubleClick: (FileEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Select a folder to see its tracks",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            item {
                TrackListHeader(
                    columnConfig = columnConfig,
                    onToggleColumn = onToggleColumn
                )
            }
            items(entries, key = { it.file.absolutePath }) { entry ->
                val isPlaying = entry.file.absolutePath == currentTrackPath
                val isSelected = entry.file.absolutePath == selectedTrackPath
                TrackRow(
                    entry = entry,
                    isPlaying = isPlaying,
                    isSelected = isSelected,
                    columnConfig = columnConfig,
                    onClick = { onTrackClick(entry) },
                    onDoubleClick = { onTrackDoubleClick(entry) }
                )
            }
        }
    }
}

private fun getColumnValue(column: TrackListColumn, entry: FileEntry): String {
    val track = entry.trackData
    return when (column) {
        TrackListColumn.TRACK_NUMBER ->
            if (track != null && track.trackNumber > 0) track.trackNumber.toString() else "-"
        TrackListColumn.FILE_NAME -> entry.file.name
        TrackListColumn.TITLE -> track?.title ?: "-"
        TrackListColumn.ALBUM -> track?.album?.ifEmpty { "-" } ?: "-"
        TrackListColumn.ARTIST -> track?.artist?.ifEmpty { "-" } ?: "-"
        TrackListColumn.DURATION ->
            if (track != null && track.duration > 0) formatTime(track.duration) else "-"
        TrackListColumn.ALBUM_ARTIST -> track?.albumArtist?.ifEmpty { "-" } ?: "-"
        TrackListColumn.GENRE -> track?.genre?.ifEmpty { "-" } ?: "-"
        TrackListColumn.YEAR ->
            if (track != null && track.year > 0) track.year.toString() else "-"
        TrackListColumn.DISC_NUMBER ->
            if (track != null && track.discNumber > 0) track.discNumber.toString() else "-"
        TrackListColumn.FILE_SIZE ->
            if (track != null && track.fileSize > 0) formatFileSize(track.fileSize) else "-"
    }
}

@Composable
private fun getColumnTextStyle(column: TrackListColumn): TextStyle {
    return when (column) {
        TrackListColumn.TITLE -> MaterialTheme.typography.bodyMedium
        else -> MaterialTheme.typography.bodySmall
    }
}

private fun getColumnAlpha(column: TrackListColumn): Float {
    return when (column) {
        TrackListColumn.TITLE -> 1.0f
        TrackListColumn.TRACK_NUMBER, TrackListColumn.DURATION, TrackListColumn.DISC_NUMBER -> 0.7f
        else -> 0.8f
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TrackListHeader(
    columnConfig: ColumnConfig,
    onToggleColumn: (TrackListColumn) -> Unit
) {
    var showColumnMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press &&
                                event.button == PointerButton.Secondary
                            ) {
                                val position = event.changes.first().position
                                with(density) {
                                    menuOffset = DpOffset(
                                        x = position.x.toDp(),
                                        y = position.y.toDp() - size.height.toDp()
                                    )
                                }
                                showColumnMenu = true
                            }
                        }
                    }
                }
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            columnConfig.visibleColumns.forEach { column ->
                Text(
                    text = column.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(column.defaultWeight)
                )
            }
        }

        ColumnVisibilityMenu(
            expanded = showColumnMenu,
            onDismiss = { showColumnMenu = false },
            offset = menuOffset,
            columnConfig = columnConfig,
            onToggleColumn = onToggleColumn
        )
    }
}

@Composable
private fun ColumnVisibilityMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    offset: DpOffset,
    columnConfig: ColumnConfig,
    onToggleColumn: (TrackListColumn) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset
    ) {
        TrackListColumn.entries.forEach { column ->
            val isVisible = column in columnConfig.visibleColumns
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = isVisible,
                            onCheckedChange = null
                        )
                        Text(column.label)
                    }
                },
                onClick = { onToggleColumn(column) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun TrackRow(
    entry: FileEntry,
    isPlaying: Boolean,
    isSelected: Boolean,
    columnConfig: ColumnConfig,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val bgColor = when {
        isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        isSelected -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.background
    }
    val textColor = when {
        isPlaying -> MaterialTheme.colorScheme.primary
        isSelected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onBackground
    }

    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press &&
                                event.button == PointerButton.Secondary
                            ) {
                                val position = event.changes.first().position
                                with(density) {
                                    contextMenuOffset = DpOffset(
                                        x = position.x.toDp(),
                                        y = position.y.toDp() - size.height.toDp()
                                    )
                                }
                                showContextMenu = true
                            }
                        }
                    }
                }
                .combinedClickable(
                    onClick = onClick,
                    onDoubleClick = onDoubleClick
                )
                .background(bgColor)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            columnConfig.visibleColumns.forEach { column ->
                Text(
                    text = getColumnValue(column, entry),
                    style = getColumnTextStyle(column),
                    color = textColor.copy(alpha = getColumnAlpha(column)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(column.defaultWeight)
                )
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = contextMenuOffset
        ) {
            DropdownMenuItem(
                text = { Text("Copy file path") },
                onClick = {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(StringSelection(entry.file.absolutePath), null)
                    showContextMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text("Open file location") },
                onClick = {
                    ProcessBuilder("xdg-open", entry.file.parentFile.absolutePath).start()
                    showContextMenu = false
                }
            )
        }
    }
}
