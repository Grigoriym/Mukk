package com.grappim.mukk.data

import com.grappim.mukk.core.model.MediaTrackData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class TrackRepository(
    private val databaseInit: DatabaseInit
) {

    suspend fun getAllTracks(): List<MediaTrackData> = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            MediaTrackEntity.all().map { it.toData() }
        }
    }

    suspend fun findByPath(filePath: String): MediaTrackData? = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            MediaTrackEntity.find(MediaTracks.filePath eq filePath)
                .firstOrNull()
                ?.toData()
        }
    }

    suspend fun existsByPath(filePath: String): Boolean = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            MediaTrackEntity.find(MediaTracks.filePath eq filePath)
                .firstOrNull() != null
        }
    }

    suspend fun insertIfAbsent(
        filePath: String,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        genre: String,
        trackNumber: Int,
        discNumber: Int,
        year: Int,
        durationMs: Long,
        fileSize: Long,
        lastModified: Long
    ): Boolean = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            val stillMissing = MediaTrackEntity.find(
                MediaTracks.filePath eq filePath
            ).firstOrNull() == null

            if (stillMissing) {
                MediaTrackEntity.new {
                    this.filePath = filePath
                    this.title = title
                    this.artist = artist
                    this.album = album
                    this.albumArtist = albumArtist
                    this.genre = genre
                    this.trackNumber = trackNumber
                    this.discNumber = discNumber
                    this.year = year
                    this.duration = durationMs
                    this.fileSize = fileSize
                    this.lastModified = lastModified
                    this.addedAt = System.currentTimeMillis()
                }
                true
            } else {
                false
            }
        }
    }

    suspend fun updateByPath(
        filePath: String,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        genre: String,
        trackNumber: Int,
        discNumber: Int,
        year: Int,
        durationMs: Long,
        fileSize: Long,
        lastModified: Long
    ): Boolean = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            val entity = MediaTrackEntity.find(
                MediaTracks.filePath eq filePath
            ).firstOrNull()
            if (entity != null) {
                entity.title = title
                entity.artist = artist
                entity.album = album
                entity.albumArtist = albumArtist
                entity.genre = genre
                entity.trackNumber = trackNumber
                entity.discNumber = discNumber
                entity.year = year
                entity.duration = durationMs
                entity.fileSize = fileSize
                entity.lastModified = lastModified
                true
            } else {
                false
            }
        }
    }

    suspend fun deleteAll(): Int = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            MediaTracks.deleteAll()
        }
    }

    suspend fun deleteByPath(filePath: String): Boolean = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            val entity = MediaTrackEntity.find(
                MediaTracks.filePath eq filePath
            ).firstOrNull()
            if (entity != null) {
                entity.delete()
                true
            } else {
                false
            }
        }
    }
}
