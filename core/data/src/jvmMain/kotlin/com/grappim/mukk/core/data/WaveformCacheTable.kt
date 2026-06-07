package com.grappim.mukk.core.data

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

object WaveformCacheTable : LongIdTable("waveform_cache") {
    val filePath = varchar("file_path", 1024).uniqueIndex()
    val peaks = text("peaks")            // base64-encoded little-endian floats
    val fileLastModified = long("file_last_modified").default(0L)
}
