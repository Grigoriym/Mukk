package com.grappim.mukk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.grappim.mukk.player.PlaybackState
import com.grappim.mukk.player.Status
import com.grappim.mukk.ui.components.SeekBar
import com.grappim.mukk.ui.components.VolumeControl

@Composable
fun TransportBar(
    playbackState: PlaybackState,
    currentTrackTitle: String,
    currentTrackArtist: String,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SeekBar(
            positionMs = playbackState.positionMs,
            durationMs = playbackState.durationMs,
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (playbackState.status == Status.PLAYING) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = "Play/Pause",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentTrackTitle.ifEmpty { "No track" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (currentTrackArtist.isNotEmpty()) {
                    Text(
                        text = currentTrackArtist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            VolumeControl(
                volume = playbackState.volume.toFloat(),
                onVolumeChange = onVolumeChange
            )
        }
    }
}

@Composable
@Preview
private fun TransportBarPreview() {
    MukkTheme {
        TransportBar(
            playbackState = PlaybackState(),
            currentTrackTitle = "Test",
            currentTrackArtist = "TestArtist",
            onPlayPause = {},
            onStop = {},
            onPrevious = {},
            onNext = {},
            onSeek = {},
            onVolumeChange = {}
        )
    }
}
