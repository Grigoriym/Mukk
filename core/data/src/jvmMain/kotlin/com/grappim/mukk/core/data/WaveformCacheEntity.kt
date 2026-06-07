package com.grappim.mukk.core.data

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class WaveformCacheEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<WaveformCacheEntity>(WaveformCacheTable)

    var filePath by WaveformCacheTable.filePath
    var peaks by WaveformCacheTable.peaks
    var fileLastModified by WaveformCacheTable.fileLastModified
}
