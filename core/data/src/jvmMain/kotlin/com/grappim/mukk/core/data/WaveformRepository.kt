package com.grappim.mukk.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class WaveformRepository(private val databaseInit: DatabaseInit) {

    suspend fun get(filePath: String): FloatArray? = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            val entity = WaveformCacheEntity
                .find(WaveformCacheTable.filePath eq filePath)
                .firstOrNull() ?: return@transaction null
            if (entity.fileLastModified < File(filePath).lastModified()) return@transaction null
            deserialize(entity.peaks)
        }
    }

    suspend fun put(filePath: String, peaks: FloatArray) = withContext(Dispatchers.IO) {
        val lastModified = File(filePath).lastModified()
        val encoded = serialize(peaks)
        transaction(databaseInit.database) {
            val existing = WaveformCacheEntity
                .find(WaveformCacheTable.filePath eq filePath)
                .firstOrNull()
            if (existing != null) {
                existing.peaks = encoded
                existing.fileLastModified = lastModified
            } else {
                WaveformCacheEntity.new {
                    this.filePath = filePath
                    this.peaks = encoded
                    this.fileLastModified = lastModified
                }
            }
        }
    }

    private fun serialize(peaks: FloatArray): String {
        val bb = ByteBuffer.allocate(peaks.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        peaks.forEach { bb.putFloat(it) }
        return Base64.getEncoder().encodeToString(bb.array())
    }

    private fun deserialize(encoded: String): FloatArray {
        val bytes = Base64.getDecoder().decode(encoded)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { bb.float }
    }
}
