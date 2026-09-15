package com.grappim.mukk.core.data

import com.grappim.mukk.core.model.Playlist
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class PlaylistEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<PlaylistEntity>(PlaylistsTable)

    var name by PlaylistsTable.name
    var folderPath by PlaylistsTable.folderPath
    var sortOrder by PlaylistsTable.sortOrder
}

fun PlaylistEntity.toData() = Playlist(
    id = id.value,
    name = name,
    folderPath = folderPath,
    sortOrder = sortOrder
)
