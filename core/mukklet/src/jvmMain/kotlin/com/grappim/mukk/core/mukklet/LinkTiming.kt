package com.grappim.mukk.core.mukklet

import com.grappim.mukk.core.model.PlaybackStatus
import kotlin.math.abs
import kotlin.math.min

internal const val HEARTBEAT_MS = 5_000L
internal const val SEEK_TOLERANCE_MS = 1_500L

private const val FIRST_RETRY_MS = 2_000L
private const val MAX_RETRY_MS = 30_000L
private const val MAX_DOUBLINGS = 4

/** Wait before reconnect attempt number [attempt] (0-based): 2, 4, 8, 16, 30, 30… s. */
internal fun reconnectDelayMs(attempt: Int): Long =
    min(FIRST_RETRY_MS shl attempt.coerceIn(0, MAX_DOUBLINGS), MAX_RETRY_MS)

/** The last `state` sent to the display, and when (monotonic ms). */
internal data class SentState(val state: DisplayState, val atMs: Long)

/**
 * Whether a `state` message is due. The position changes 5 times per second, so a position
 * change alone is not a reason to send. A `state` is due when status, volume, repeat or
 * shuffle changed, when the position left the display's extrapolation by more than
 * [SEEK_TOLERANCE_MS] (a seek or a restart), or when [HEARTBEAT_MS] passed.
 */
internal fun isStateDue(sent: SentState?, current: DisplayState, nowMs: Long): Boolean {
    if (sent == null) return true
    val last = sent.state
    val elapsed = nowMs - sent.atMs
    val expectedPosition = if (last.status == PlaybackStatus.PLAYING) last.positionMs + elapsed else last.positionMs
    val otherFieldsChanged = current.copy(positionMs = 0) != last.copy(positionMs = 0)
    return elapsed >= HEARTBEAT_MS ||
        otherFieldsChanged ||
        abs(current.positionMs - expectedPosition) > SEEK_TOLERANCE_MS
}

/** The `cover` last sent to the display: for which track, which art, and whether it encoded. */
internal data class SentCover(val trackId: String?, val cover: CoverArt?, val encoded: Boolean)

/**
 * Whether a `cover` message is due. The display blanks its only cover buffer on every
 * `cover`, so a cover goes out only for a new track id or new art — not when `next` alone
 * changed, and not when the same art was read again.
 */
internal fun isCoverDue(sent: SentCover?, snapshot: NowPlayingSnapshot): Boolean =
    sent == null || sent.trackId != snapshot.track?.id || sent.cover != snapshot.cover
