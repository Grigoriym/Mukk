@file:OptIn(ExperimentalComposeUiApi::class)

package com.grappim.mukk.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.grappim.mukk.utils.formatTime

@Composable
fun WaveformSeekBar(
    peaks: FloatArray?,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val unplayed = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatTime(positionMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(48.dp)
        )

        if (peaks != null && peaks.isNotEmpty() && durationMs > 0) {
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .padding(horizontal = 8.dp)
                    .pointerInput(durationMs) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                val isPress = event.type == PointerEventType.Press
                                val isDrag = event.type == PointerEventType.Move && change.pressed
                                if ((isPress || isDrag) && size.width > 0) {
                                    val fraction = (change.position.x / size.width)
                                        .coerceIn(0f, 1f)
                                    onSeek((fraction * durationMs).toLong())
                                    change.consume()
                                }
                            }
                        }
                    }
            ) {
                val progress = positionMs.toFloat() / durationMs
                val count = peaks.size
                val gapPx = 1.5f
                val barWidth = ((size.width - gapPx * (count - 1)) / count).coerceAtLeast(1f)
                val centerY = size.height / 2f
                val minBarH = 2f

                peaks.forEachIndexed { i, amplitude ->
                    val x = i * (barWidth + gapPx)
                    val h = (amplitude * size.height).coerceAtLeast(minBarH)
                    val color = if (i.toFloat() / count <= progress) primary else unplayed
                    drawRect(
                        color = color,
                        topLeft = Offset(x, centerY - h / 2f),
                        size = Size(barWidth, h)
                    )
                }

                // playhead
                val px = progress * size.width
                drawLine(
                    color = primary,
                    start = Offset(px, 0f),
                    end = Offset(px, size.height),
                    strokeWidth = 2f
                )
            }
        } else {
            var isSeeking by remember { mutableStateOf(false) }
            var seekValue by remember { mutableStateOf(0f) }
            val sliderValue = if (isSeeking) seekValue
            else if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

            Slider(
                value = sliderValue,
                onValueChange = { isSeeking = true; seekValue = it },
                onValueChangeFinished = {
                    isSeeking = false
                    onSeek((seekValue * durationMs).toLong())
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = primary,
                    activeTrackColor = primary,
                    inactiveTrackColor = surfaceVariant
                )
            )
        }

        Text(
            text = formatTime(durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(48.dp)
        )
    }
}
