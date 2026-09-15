package com.grappim.mukk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.grappim.mukk.core.model.Playlist
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.roundToInt

private const val DOUBLE_CLICK_WINDOW_MS = 300L
private const val SELECT_DEBOUNCE_MS = 300L
private const val DRAG_START_THRESHOLD_PX = 8f
private val TAB_MAX_WIDTH = 160.dp

@Composable
fun PlaylistTabBar(
    playlists: ImmutableList<Playlist>,
    activePlaylistId: Long?,
    onSelectPlaylist: (Long) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onReorderPlaylists: (List<Long>) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var order by remember { mutableStateOf<List<Playlist>>(playlists) }
    LaunchedEffect(playlists) { order = playlists }

    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val tabWidths = remember { mutableStateMapOf<Long, Int>() }
    // Debounced: each switch triggers a full folder scan, so rapid clicks would otherwise stack
    // up multiple concurrent scans of a large playlist's folder.
    var lastSelectTime by remember { mutableLongStateOf(0L) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        order.forEach { playlist ->
            PlaylistTab(
                playlist = playlist,
                isActive = playlist.id == activePlaylistId,
                dragOffsetX = if (playlist.id == draggingId) dragOffsetX else 0f,
                onClick = {
                    val now = System.currentTimeMillis()
                    if (playlist.id != activePlaylistId && now - lastSelectTime >= SELECT_DEBOUNCE_MS) {
                        lastSelectTime = now
                        onSelectPlaylist(playlist.id)
                    }
                },
                onRename = { newName -> onRenamePlaylist(playlist.id, newName) },
                onClose = { onDeletePlaylist(playlist.id) },
                onWidthChange = { widthPx -> tabWidths[playlist.id] = widthPx },
                onDragStart = {
                    draggingId = playlist.id
                    dragOffsetX = 0f
                },
                onDrag = { delta ->
                    dragOffsetX += delta
                    val result = reorderedAfterDrag(order, playlist.id, dragOffsetX, tabWidths)
                    order = result.order
                    dragOffsetX = result.offsetX
                },
                onDragEnd = {
                    draggingId = null
                    dragOffsetX = 0f
                    if (order.map { it.id } != playlists.map { it.id }) {
                        onReorderPlaylists(order.map { it.id })
                    }
                }
            )
        }

        IconButton(onClick = onCreatePlaylist, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = "New playlist",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private data class DragReorderResult(val order: List<Playlist>, val offsetX: Float)

/** Swaps the dragged tab past a neighbor once it's dragged past half the neighbor's width. */
private fun reorderedAfterDrag(
    order: List<Playlist>,
    draggedId: Long,
    dragOffsetX: Float,
    tabWidths: Map<Long, Int>
): DragReorderResult {
    val currentIndex = order.indexOfFirst { it.id == draggedId }
    val swapIndex = if (dragOffsetX > 0) currentIndex + 1 else currentIndex - 1
    val swapWidth = order.getOrNull(swapIndex)?.let { tabWidths[it.id] }

    return if (swapIndex in order.indices && swapWidth != null && kotlin.math.abs(dragOffsetX) > swapWidth / 2f) {
        val newOrder = order.toMutableList().apply { add(currentIndex, removeAt(swapIndex)) }
        val consumed = if (dragOffsetX > 0) swapWidth.toFloat() else -swapWidth.toFloat()
        DragReorderResult(newOrder, dragOffsetX - consumed)
    } else {
        DragReorderResult(order, dragOffsetX)
    }
}

@Composable
private fun PlaylistTab(
    playlist: Playlist,
    isActive: Boolean,
    dragOffsetX: Float,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onClose: () -> Unit,
    onWidthChange: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    var isEditing by remember(playlist.id) { mutableStateOf(false) }
    val backgroundColor = if (isActive) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
    }
    val textColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .onSizeChanged { onWidthChange(it.width) }
            .offset { IntOffset(dragOffsetX.roundToInt(), 0) }
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(backgroundColor)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isEditing) {
            PlaylistNameField(
                initialName = playlist.name,
                textColor = textColor,
                onCommit = { name ->
                    isEditing = false
                    commitRename(name, playlist, onRename)
                }
            )
        } else {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .playlistTabGestures(
                        onClick = onClick,
                        onDoubleClick = { isEditing = true },
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd
                    )
                    .widthIn(max = TAB_MAX_WIDTH)
            )
        }

        Spacer(Modifier.width(6.dp))

        IconButton(onClick = onClose, modifier = Modifier.size(18.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close playlist \"${playlist.name}\"",
                tint = textColor.copy(alpha = 0.6f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun PlaylistNameField(
    initialName: String,
    textColor: Color,
    onCommit: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = textColor),
        cursorBrush = SolidColor(textColor),
        modifier = Modifier
            .widthIn(min = 40.dp, max = TAB_MAX_WIDTH)
            .focusRequester(focusRequester)
            .onFocusChanged { state -> if (!state.isFocused) onCommit(text) }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    onCommit(text)
                    true
                } else {
                    false
                }
            }
    )
}

private fun commitRename(text: String, playlist: Playlist, onRename: (String) -> Unit) {
    val trimmed = text.trim()
    if (trimmed.isNotEmpty() && trimmed != playlist.name) onRename(trimmed)
}

/**
 * Single gesture detector distinguishing click / double-click / drag on one region, since
 * a plain drag detector and a tap detector both want first claim on the same pointer events.
 *
 * Keyed on `Unit`, not on the callbacks: `onDrag` itself causes a recomposition (it drives the
 * dragged tab's offset), which would otherwise recreate these inline lambdas and restart
 * `pointerInput`'s coroutine mid-gesture — silently swallowing the eventual pointer-up before
 * `onDragEnd` ever fires. `rememberUpdatedState` keeps each callback current without that.
 */
@Composable
private fun Modifier.playlistTabGestures(
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    return this.pointerInput(Unit) {
        var lastClickTime = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragging = false
            var accumulatedDrag = 0f
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) {
                    if (dragging) {
                        currentOnDragEnd()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastClickTime < DOUBLE_CLICK_WINDOW_MS) {
                            currentOnDoubleClick()
                            lastClickTime = 0L
                        } else {
                            currentOnClick()
                            lastClickTime = now
                        }
                    }
                    return@awaitEachGesture
                }
                val dx = change.positionChange().x
                if (!dragging) {
                    accumulatedDrag += dx
                    if (kotlin.math.abs(accumulatedDrag) > DRAG_START_THRESHOLD_PX) {
                        dragging = true
                        change.consume()
                        currentOnDragStart()
                        currentOnDrag(accumulatedDrag)
                    }
                } else {
                    change.consume()
                    currentOnDrag(dx)
                }
            }
        }
    }
}
