package com.grappim.mukk.core.model

data class MediaTrackData(
    val id: Long,
    val filePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val duration: Long,
    val fileSize: Long,
    val lastModified: Long,
    val addedAt: Long
)
