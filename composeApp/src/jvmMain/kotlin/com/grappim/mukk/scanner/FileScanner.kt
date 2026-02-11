package com.grappim.mukk.scanner

import com.grappim.mukk.data.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileScanner(
    private val trackRepository: TrackRepository,
    private val metadataReader: MetadataReader
) {

    suspend fun scan(directory: File): Int = withContext(Dispatchers.IO) {
        if (!directory.isDirectory) return@withContext 0

        var count = 0
        directory.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .forEach { file ->
                count += scanSingleFile(file)
            }
        count
    }

    suspend fun scanFolder(directory: File): Int = withContext(Dispatchers.IO) {
        if (!directory.isDirectory) return@withContext 0

        var count = 0
        val files = directory.listFiles() ?: return@withContext 0
        files.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .forEach { file ->
                count += scanSingleFile(file)
            }
        count
    }

    suspend fun removeTrack(filePath: String): Boolean {
        return trackRepository.deleteByPath(filePath)
    }

    private suspend fun scanSingleFile(file: File): Int {
        val existing = trackRepository.findByPath(file.absolutePath)

        if (existing != null) {
            if (file.lastModified() > existing.lastModified) {
                val metadata = metadataReader.read(file)
                trackRepository.updateByPath(
                    filePath = file.absolutePath,
                    title = metadata?.title ?: file.nameWithoutExtension,
                    artist = metadata?.artist.orEmpty(),
                    album = metadata?.album.orEmpty(),
                    albumArtist = metadata?.albumArtist.orEmpty(),
                    genre = metadata?.genre.orEmpty(),
                    trackNumber = metadata?.trackNumber ?: 0,
                    discNumber = metadata?.discNumber ?: 0,
                    year = metadata?.year ?: 0,
                    durationMs = metadata?.durationMs ?: 0L,
                    fileSize = file.length(),
                    lastModified = file.lastModified()
                )
                return 1
            }
            return 0
        }

        val metadata = metadataReader.read(file)
        val inserted = trackRepository.insertIfAbsent(
            filePath = file.absolutePath,
            title = metadata?.title ?: file.nameWithoutExtension,
            artist = metadata?.artist.orEmpty(),
            album = metadata?.album.orEmpty(),
            albumArtist = metadata?.albumArtist.orEmpty(),
            genre = metadata?.genre.orEmpty(),
            trackNumber = metadata?.trackNumber ?: 0,
            discNumber = metadata?.discNumber ?: 0,
            year = metadata?.year ?: 0,
            durationMs = metadata?.durationMs ?: 0L,
            fileSize = file.length(),
            lastModified = file.lastModified()
        )
        return if (inserted) 1 else 0
    }

    companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "wav", "aac", "opus", "m4a")
    }
}
