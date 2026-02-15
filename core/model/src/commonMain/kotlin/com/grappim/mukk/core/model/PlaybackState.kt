package com.grappim.mukk.core.model

data class PlaybackState(
    val playbackStatus: PlaybackStatus = PlaybackStatus.IDLE,
    val currentTrackPath: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Double = 0.8
)
