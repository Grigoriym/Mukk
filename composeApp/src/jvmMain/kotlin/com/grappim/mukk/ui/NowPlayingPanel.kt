package com.grappim.mukk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.grappim.mukk.data.MediaTrackData

@Composable
fun NowPlayingPanel(
    currentTrack: MediaTrackData?,
    albumArt: ImageBitmap?,
    lyrics: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        if (currentTrack == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No track playing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (albumArt != null) {
                    Image(
                        bitmap = albumArt,
                        contentDescription = "Album art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = "No album art",
                        modifier = Modifier.fillMaxSize(0.4f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Metadata
            Text(
                text = currentTrack.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (currentTrack.artist.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = currentTrack.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (currentTrack.album.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = currentTrack.album,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val genreYear = buildList {
                if (currentTrack.genre.isNotEmpty()) add(currentTrack.genre)
                if (currentTrack.year > 0) add(currentTrack.year.toString())
            }.joinToString(" · ")

            if (genreYear.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = genreYear,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Lyrics
            val scrollState = rememberScrollState()
            if (!lyrics.isNullOrEmpty()) {
                Text(
                    text = lyrics,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No lyrics available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
@Preview
private fun NowPlayingPanelPreview() {
    MukkTheme {
        NowPlayingPanel(
            currentTrack = MediaTrackData(
                id = 4878,
                filePath = "nostra",
                title = "tota",
                artist = "voluptatum",
                album = "noster",
                albumArtist = "patrioque",
                genre = "falli",
                trackNumber = 4185,
                discNumber = 6520,
                year = 2019,
                duration = 2189,
                fileSize = 9373,
                lastModified = 7858,
                addedAt = 4570
            ),
            albumArt = null,
            lyrics = "erroribus",
        )
    }
}
