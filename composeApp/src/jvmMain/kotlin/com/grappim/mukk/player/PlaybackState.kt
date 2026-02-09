package com.grappim.mukk.player

enum class Status {
    IDLE,
    PLAYING,
    PAUSED,
    STOPPED
}

data class PlaybackState(
    val status: Status = Status.IDLE,
    val currentTrackPath: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Double = 0.8
)
