package com.grappim.mukk.core.model.scanner

import com.grappim.mukk.core.data.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileScanner(
    private val trackRepository: TrackRepository,
    private val metadataReader: MetadataReader
) {

    suspend fun scan(
        directory: File,
        onProgress: ((scanned: Int, total: Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        if (!directory.isDirectory) return@withContext 0

        val audioFiles = directory.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .toList()
        val total = audioFiles.size
        onProgress?.invoke(0, total)

        var count = 0
        audioFiles.forEachIndexed { index, file ->
            if (scanSingleFile(file)) count++
            onProgress?.invoke(index + 1, total)
        }
        count
    }

    suspend fun scanFolder(directory: File): Int = withContext(Dispatchers.IO) {
        if (!directory.isDirectory) return@withContext 0

        var count = 0
        val files = directory.listFiles() ?: return@withContext 0
        files.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .forEach { file ->
                if (scanSingleFile(file)) count++
            }
        count
    }

    suspend fun removeTrack(filePath: String): Boolean {
        return trackRepository.deleteByPath(filePath)
    }

    private suspend fun scanSingleFile(file: File): Boolean {
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
                return true
            }
            return false
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
        return inserted
    }

    companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "wav", "aac", "opus", "m4a")
    }
}
