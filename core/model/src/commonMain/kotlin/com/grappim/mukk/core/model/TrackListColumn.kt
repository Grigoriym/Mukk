package com.grappim.mukk.core.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

enum class TrackListColumn(
    val label: String,
    val defaultWidthDp: Int,
    val visibleByDefault: Boolean
) {
    TRACK_NUMBER("#", 40, true),
    FILE_NAME("File Name", 180, true),
    TITLE("Title", 200, true),
    ALBUM("Album", 180, true),
    ARTIST("Artist", 160, true),
    DURATION("Duration", 70, true),
    ALBUM_ARTIST("Album Artist", 160, false),
    GENRE("Genre", 120, false),
    YEAR("Year", 60, false),
    DISC_NUMBER("Disc #", 50, false),
    FILE_SIZE("File Size", 90, false);
}

data class ColumnConfig(
    val visibleColumns: PersistentList<TrackListColumn>,
    val columnWidths: Map<TrackListColumn, Int> = emptyMap()
)

val DEFAULT_COLUMN_CONFIG = ColumnConfig(
    visibleColumns = TrackListColumn.entries.filter { it.visibleByDefault }.toPersistentList()
)
