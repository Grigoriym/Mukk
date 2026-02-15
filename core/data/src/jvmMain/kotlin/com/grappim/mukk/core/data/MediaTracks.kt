package com.grappim.mukk.core.data

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

object MediaTracks : LongIdTable("media_tracks") {
    val filePath = varchar("file_path", 1024).uniqueIndex()
    val title = varchar("title", 512)
    val artist = varchar("artist", 512).default("")
    val album = varchar("album", 512).default("")
    val albumArtist = varchar("album_artist", 512).default("")
    val genre = varchar("genre", 256).default("")
    val trackNumber = integer("track_number").default(0)
    val discNumber = integer("disc_number").default(0)
    val year = integer("year").default(0)
    val duration = long("duration").default(0L)
    val fileSize = long("file_size").default(0L)
    val lastModified = long("last_modified").default(0L)
    val addedAt = long("added_at").default(0L)
}
