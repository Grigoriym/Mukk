package com.grappim.mukk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grappim.mukk.core.model.MediaTrackData
import java.awt.Cursor

private val MIN_METADATA_HEIGHT = 80.dp
private val MIN_LYRICS_HEIGHT = 60.dp
private val DIVIDER_AND_SPACERS = 28.dp // 12 + 4 + 12

@Composable
fun NowPlayingPanel(
    currentTrack: MediaTrackData?,
    albumArt: ImageBitmap?,
    lyrics: String?,
    lyricsHeight: Dp,
    onLyricsHeightDrag: (Float) -> Unit,
    onLyricsHeightDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
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
            val totalHeight = maxHeight
            val clampedLyricsHeight = lyricsHeight.coerceIn(
                MIN_LYRICS_HEIGHT,
                (totalHeight - DIVIDER_AND_SPACERS - MIN_METADATA_HEIGHT).coerceAtLeast(MIN_LYRICS_HEIGHT)
            )
            val metadataHeight = (totalHeight - DIVIDER_AND_SPACERS - clampedLyricsHeight)
                .coerceAtLeast(MIN_METADATA_HEIGHT)

            Column(modifier = Modifier.fillMaxSize()) {
                // Metadata section — height adjusts inversely with lyrics height
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(metadataHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
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

                        if (currentTrack.year > 0) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = currentTrack.year.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (currentTrack.genre.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = currentTrack.genre,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                DraggableHorizontalDivider(
                    onDrag = onLyricsHeightDrag,
                    onDragEnd = onLyricsHeightDragEnd
                )

                Spacer(modifier = Modifier.height(12.dp))

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
}

@Composable
private fun DraggableHorizontalDivider(
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(4.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.outlineVariant)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.S_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                }
            }
    )
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
            lyricsHeight = 200.dp,
            onLyricsHeightDrag = {},
            onLyricsHeightDragEnd = {}
        )
    }
}
