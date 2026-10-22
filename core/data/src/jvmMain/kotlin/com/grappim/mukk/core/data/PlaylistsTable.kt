package com.grappim.mukk.core.data

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

object PlaylistsTable : LongIdTable("playlists") {
    val name = varchar("name", 256)
    val folderPath = varchar("folder_path", 1024).uniqueIndex()
    val sortOrder = integer("sort_order").default(0)
    val selectedPath = varchar("selected_path", 1024).default("")
    val expandedPaths = text("expanded_paths").default("")
}
