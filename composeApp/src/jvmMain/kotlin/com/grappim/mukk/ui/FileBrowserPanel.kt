package com.grappim.mukk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grappim.mukk.data.FileBrowserState
import com.grappim.mukk.data.FileEntry
import com.grappim.mukk.ui.components.formatTime

@Composable
fun FileBrowserPanel(
    state: FileBrowserState,
    currentTrackPath: String?,
    onNavigateToDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onFileClick: (FileEntry) -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.currentPath == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No folder opened. Use \"Open Folder\" to browse your music.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Breadcrumb bar
        BreadcrumbBar(
            segments = state.pathSegments,
            onSegmentClick = onBreadcrumbClick
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ".." back row when not at root
            if (state.currentPath != state.rootPath) {
                item(key = "..") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateUp)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Go up",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "..",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(state.entries, key = { it.file.absolutePath }) { entry ->
                if (entry.isDirectory) {
                    FolderRow(
                        entry = entry,
                        onClick = { onNavigateToDirectory(entry.file.absolutePath) }
                    )
                } else {
                    FileRow(
                        entry = entry,
                        isPlaying = entry.file.absolutePath == currentTrackPath,
                        onClick = { onFileClick(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbBar(
    segments: List<String>,
    onSegmentClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
            val isLast = index == segments.lastIndex
            Text(
                text = segment,
                style = MaterialTheme.typography.bodySmall,
                color = if (isLast) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (!isLast) {
                    Modifier.clickable { onSegmentClick(index) }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                } else {
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                }
            )
        }
    }
}

@Composable
private fun FolderRow(
    entry: FileEntry,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = "Folder",
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isPlaying) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.background
    }
    val textColor = if (isPlaying) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onBackground
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = "Audio file",
            tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )

        val track = entry.trackData
        if (track != null) {
            // Track number
            Text(
                text = if (track.trackNumber > 0) "${track.trackNumber}." else "",
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.6f),
                modifier = Modifier.padding(end = 4.dp)
            )
            // Title
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Artist
            if (track.artist.isNotEmpty()) {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Duration
            if (track.duration > 0) {
                Text(
                    text = formatTime(track.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.5f)
                )
            }
        } else {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
