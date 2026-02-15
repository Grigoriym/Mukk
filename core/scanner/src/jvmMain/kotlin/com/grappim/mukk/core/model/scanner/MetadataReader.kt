package com.grappim.mukk.core.model.scanner

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.grappim.mukk.core.model.MukkLogger
import com.grappim.mukk.core.model.AudioMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

class MetadataReader {

    init {
        // Suppress jaudiotagger's verbose logging
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    suspend fun readAlbumArt(filePath: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val audioFile = AudioFileIO.read(File(filePath))
            audioFile.tag?.firstArtwork?.binaryData?.toImageBitmap()
        } catch (e: Exception) {
            MukkLogger.warn("MetadataReader", "Failed to read album art for $filePath", e)
            null
        }
    }

    suspend fun readLyrics(filePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val audioFile = AudioFileIO.read(File(filePath))
            audioFile.tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            MukkLogger.warn("MetadataReader", "Failed to read lyrics for $filePath", e)
            null
        }
    }

    suspend fun read(file: File): AudioMetadata? = withContext(Dispatchers.IO) {
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag
            val header = audioFile.audioHeader

            AudioMetadata(
                title = tag?.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() }
                    ?: file.nameWithoutExtension,
                artist = tag?.getFirst(FieldKey.ARTIST).orEmpty(),
                album = tag?.getFirst(FieldKey.ALBUM).orEmpty(),
                albumArtist = tag?.getFirst(FieldKey.ALBUM_ARTIST).orEmpty(),
                genre = tag?.getFirst(FieldKey.GENRE).orEmpty(),
                trackNumber = tag?.getFirst(FieldKey.TRACK)?.toIntOrNull() ?: 0,
                discNumber = tag?.getFirst(FieldKey.DISC_NO)?.toIntOrNull() ?: 0,
                year = tag?.getFirst(FieldKey.YEAR)?.take(4)?.toIntOrNull() ?: 0,
                durationMs = header.trackLength.toLong() * 1000L
            )
        } catch (e: Exception) {
            MukkLogger.warn("MetadataReader", "Failed to read metadata for ${file.name}", e)
            null
        }
    }

    private fun ByteArray.toImageBitmap(): ImageBitmap? = try {
        org.jetbrains.skia.Image.makeFromEncoded(this).toComposeImageBitmap()
    } catch (e: Exception) {
        MukkLogger.warn("NowPlayingPanel", "Failed to decode album art image", e)
        null
    }
}
