package com.grappim.mukk.scanner

import com.grappim.mukk.data.DatabaseInit
import com.grappim.mukk.data.MediaTrackEntity
import com.grappim.mukk.data.MediaTracks
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

class FileScanner(
    private val databaseInit: DatabaseInit,
    private val metadataReader: MetadataReader
) {

    private val audioExtensions = setOf("mp3", "flac", "ogg", "wav", "aac", "opus", "m4a")

    fun scan(directory: File): Int {
        if (!directory.isDirectory) return 0

        var count = 0
        directory.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in audioExtensions }
            .forEach { file ->
                transaction(databaseInit.database) {
                    val existing = MediaTrackEntity.find(
                        MediaTracks.filePath eq file.absolutePath
                    ).firstOrNull()

                    if (existing == null) {
                        val metadata = metadataReader.read(file)
                        MediaTrackEntity.new {
                            this.filePath = file.absolutePath
                            this.title = metadata?.title ?: file.nameWithoutExtension
                            this.artist = metadata?.artist.orEmpty()
                            this.album = metadata?.album.orEmpty()
                            this.albumArtist = metadata?.albumArtist.orEmpty()
                            this.genre = metadata?.genre.orEmpty()
                            this.trackNumber = metadata?.trackNumber ?: 0
                            this.discNumber = metadata?.discNumber ?: 0
                            this.year = metadata?.year ?: 0
                            this.duration = metadata?.durationMs ?: 0L
                            this.fileSize = file.length()
                            this.lastModified = file.lastModified()
                            this.addedAt = System.currentTimeMillis()
                        }
                        count++
                    }
                }
            }
        return count
    }
}
