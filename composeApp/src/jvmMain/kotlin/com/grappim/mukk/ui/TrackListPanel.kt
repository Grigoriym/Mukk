@file:OptIn(ExperimentalComposeUiApi::class)

package com.grappim.mukk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.grappim.mukk.data.ColumnConfig
import com.grappim.mukk.data.FileEntry
import com.grappim.mukk.data.MediaTrackData
import com.grappim.mukk.data.TrackListColumn
import com.grappim.mukk.ui.components.TrackContextDropdownMenu
import com.grappim.mukk.ui.components.formatTime
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.io.File

@Composable
fun TrackListPanel(
    entries: ImmutableList<FileEntry>,
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

@Composable
private fun TrackListHeader(
    columnConfig: ColumnConfig,
    onToggleColumn: (TrackListColumn) -> Unit
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }

    Box {
        PointerAwareRow(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            setContextMenuOffset = { value ->
                contextMenuOffset = value
            },
            showContextMenu = { value ->
                showContextMenu = value
            },
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
            expanded = showContextMenu,
            onDismiss = { showContextMenu = false },
            offset = contextMenuOffset,
            columnConfig = columnConfig,
            onToggleColumn = onToggleColumn
        )
    }
}

@Composable
private fun PointerAwareRow(
    setContextMenuOffset: (DpOffset) -> Unit,
    showContextMenu: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalDensity.current

    Row(
        modifier = modifier
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
                                setContextMenuOffset(
                                    DpOffset(
                                        x = position.x.toDp(),
                                        y = position.y.toDp() - size.height.toDp()
                                    )
                                )
                            }
                            showContextMenu(true)
                        }
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
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

    Box {

        PointerAwareRow(
            modifier = Modifier
                .background(bgColor)
                .combinedClickable(
                    onClick = onClick,
                    onDoubleClick = onDoubleClick
                ).padding(horizontal = 16.dp, vertical = 10.dp),
            setContextMenuOffset = { value ->
                contextMenuOffset = value
            },
            showContextMenu = { value ->
                showContextMenu = value
            },
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

        TrackContextDropdownMenu(
            entry = entry,
            showContextMenu = showContextMenu,
            contextMenuOffset = contextMenuOffset,
            setShowContextMenu = { newValue ->
                showContextMenu = newValue
            }
        )
    }
}

@Preview
@Composable
private fun TrackListPanelPreview() {
    MukkTheme {
        TrackListPanel(
            entries = persistentListOf(
                FileEntry(
                    file = File("name.mp3"),
                    isDirectory =false,
                    name = "name",
                    trackData = MediaTrackData(
                        id = 1974,
                        filePath = "efficiantur",
                        title = "pericula",
                        artist = "interpretaris",
                        album = "porttitor",
                        albumArtist = "natoque",
                        genre = "mandamus",
                        trackNumber = 2381,
                        discNumber = 5847,
                        year = 2003,
                        duration = 5641,
                        fileSize = 2344,
                        lastModified = 1578,
                        addedAt = 6999
                    )
                )
            ),
            currentTrackPath = "",
            selectedTrackPath = "",
            columnConfig = ColumnConfig(
                visibleColumns = persistentListOf(
                    TrackListColumn.TRACK_NUMBER
                )
            ),
            onToggleColumn = {},
            onTrackClick = {},
            onTrackDoubleClick = {}
        )
    }
}
