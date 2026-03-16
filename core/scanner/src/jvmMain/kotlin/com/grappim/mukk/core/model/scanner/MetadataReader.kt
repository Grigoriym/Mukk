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

data class NowPlayingExtras(
    val albumArt: ImageBitmap?,
    val lyrics: String?
)

class MetadataReader {

    init {
        // Suppress jaudiotagger's verbose logging
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    suspend fun readNowPlayingExtras(file: File): NowPlayingExtras = withContext(Dispatchers.IO) {
        try {
            val audioFile = AudioFileIO.read(file)
            NowPlayingExtras(
                albumArt = audioFile.tag?.firstArtwork?.binaryData?.toImageBitmap(),
                lyrics = audioFile.tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            MukkLogger.warn("MetadataReader", "Failed to read now-playing extras for ${file.name}", e)
            NowPlayingExtras(albumArt = null, lyrics = null)
        }
    }

    suspend fun read(file: File): AudioMetadata? = withContext(Dispatchers.IO) {
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag
            val header = audioFile.audioHeader

            val title = tag?.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            val artist = tag?.getFirst(FieldKey.ARTIST).orEmpty()
            val album = tag?.getFirst(FieldKey.ALBUM).orEmpty()
            val albumArtist = tag?.getFirst(FieldKey.ALBUM_ARTIST).orEmpty()
            val genre = tag?.getFirst(FieldKey.GENRE).orEmpty()
            val trackNumber = tag?.getFirst(FieldKey.TRACK)?.toIntOrNull() ?: 0
            val discNumber = tag?.getFirst(FieldKey.DISC_NO)?.toIntOrNull() ?: 0
            val year = tag?.getFirst(FieldKey.YEAR)?.take(4)?.toIntOrNull() ?: 0
            val durationMs = header.trackLength.toLong() * 1000L

            MukkLogger.debug(
                "MetadataReader",
                "Read [${file.name}]: tagType=${tag?.javaClass?.simpleName} title='$title'" +
                    " artist='$artist' album='$album' albumArtist='$albumArtist'" +
                    " genre='$genre' track=$trackNumber disc=$discNumber year=$year durationMs=$durationMs"
            )

            AudioMetadata(
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                genre = genre,
                trackNumber = trackNumber,
                discNumber = discNumber,
                year = year,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            MukkLogger.warn("MetadataReader", "Failed to read metadata for ${file.name}", e)
            null
        }
    }

    private fun ByteArray.toImageBitmap(): ImageBitmap? = try {
        org.jetbrains.skia.Image.makeFromEncoded(this).toComposeImageBitmap()
    } catch (e: Exception) {
        MukkLogger.warn("MetadataReader", "Failed to decode album art image", e)
        null
    }
}
