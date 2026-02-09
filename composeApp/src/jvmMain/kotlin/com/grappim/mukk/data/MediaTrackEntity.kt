package com.grappim.mukk.data

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class MediaTrackEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<MediaTrackEntity>(MediaTracks)

    var filePath by MediaTracks.filePath
    var title by MediaTracks.title
    var artist by MediaTracks.artist
    var album by MediaTracks.album
    var albumArtist by MediaTracks.albumArtist
    var genre by MediaTracks.genre
    var trackNumber by MediaTracks.trackNumber
    var discNumber by MediaTracks.discNumber
    var year by MediaTracks.year
    var duration by MediaTracks.duration
    var fileSize by MediaTracks.fileSize
    var lastModified by MediaTracks.lastModified
    var addedAt by MediaTracks.addedAt
}

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

fun MediaTrackEntity.toData() = MediaTrackData(
    id = id.value,
    filePath = filePath,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    genre = genre,
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    duration = duration,
    fileSize = fileSize,
    lastModified = lastModified,
    addedAt = addedAt
)
