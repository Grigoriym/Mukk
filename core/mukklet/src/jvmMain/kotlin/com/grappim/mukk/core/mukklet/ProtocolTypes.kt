package com.grappim.mukk.core.mukklet

import com.grappim.mukk.core.model.PlaybackStatus
import com.grappim.mukk.core.model.RepeatMode

/** The `track` object of a `track` message. See `../esp32-mukklet/docs/PROTOCOL.md`. */
data class DisplayTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val year: Int,
    val genre: String,
    val trackNo: Int,
    val durationMs: Long,
    val format: String,
    val hasCover: Boolean
)

/** The `next` object of a `track` message. */
data class NextTrack(
    val title: String,
    val artist: String
)

/** The body of a `state` message. `volume` is 0–100. */
data class DisplayState(
    val status: PlaybackStatus,
    val positionMs: Long,
    val volume: Int,
    val repeat: RepeatMode,
    val shuffle: Boolean
)

enum class CoverFormat { MONO1, RGB565, NONE }

data class CoverSpec(
    val w: Int,
    val h: Int,
    val format: CoverFormat
)

/** A message from the display to Mukk. */
sealed interface IncomingMessage {
    data class Hello(
        val v: Int,
        val device: String,
        val cover: CoverSpec,
        val maxChunk: Int
    ) : IncomingMessage

    data class Command(val command: DisplayCommand) : IncomingMessage
}

/** A `cmd` from the display's knob. */
sealed interface DisplayCommand {
    data object PlayPause : DisplayCommand
    data object Next : DisplayCommand
    data object Prev : DisplayCommand

    /** Percentage points, may be negative. */
    data class Volume(val delta: Int) : DisplayCommand

    /** Relative to the current position, may be negative. */
    data class Seek(val deltaMs: Long) : DisplayCommand
}
