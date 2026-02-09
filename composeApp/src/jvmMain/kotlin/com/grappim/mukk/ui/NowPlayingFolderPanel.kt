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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grappim.mukk.data.FileEntry
import com.grappim.mukk.ui.components.formatTime

@Composable
fun NowPlayingFolderPanel(
    entries: List<FileEntry>,
    folderName: String?,
    currentTrackPath: String?,
    onFileClick: (FileEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Play a track to see its album folder",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val listState = rememberLazyListState()

    // Auto-scroll to playing track
    val playingIndex = entries.indexOfFirst { it.file.absolutePath == currentTrackPath }
    LaunchedEffect(currentTrackPath) {
        if (playingIndex >= 0) {
            listState.animateScrollToItem(playingIndex + 1) // +1 for header
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header showing folder name
        if (folderName != null) {
            Text(
                text = folderName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(entries, key = { it.file.absolutePath }) { entry ->
                val isPlaying = entry.file.absolutePath == currentTrackPath
                NowPlayingTrackRow(
                    entry = entry,
                    isPlaying = isPlaying,
                    onClick = { onFileClick(entry) }
                )
            }
        }
    }
}

@Composable
private fun NowPlayingTrackRow(
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

    val track = entry.trackData

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Track number
        Text(
            text = if (track != null && track.trackNumber > 0) {
                track.trackNumber.toString()
            } else {
                "-"
            },
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 4.dp)
        )

        // Title
        Text(
            text = track?.title ?: entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Duration
        val duration = track?.duration ?: 0L
        if (duration > 0) {
            Text(
                text = formatTime(duration),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.5f)
            )
        }
    }
}
