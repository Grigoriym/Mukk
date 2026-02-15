package com.grappim.mukk.core.model

data class AudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val durationMs: Long
)
