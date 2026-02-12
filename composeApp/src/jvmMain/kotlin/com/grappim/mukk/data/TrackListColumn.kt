package com.grappim.mukk.data

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

enum class TrackListColumn(
    val label: String,
    val defaultWeight: Float,
    val visibleByDefault: Boolean
) {
    TRACK_NUMBER("#", 0.4f, true),
    FILE_NAME("File Name", 2f, true),
    TITLE("Title", 2f, true),
    ALBUM("Album", 2f, true),
    ARTIST("Artist", 2f, true),
    DURATION("Duration", 1f, true),
    ALBUM_ARTIST("Album Artist", 2f, false),
    GENRE("Genre", 1.5f, false),
    YEAR("Year", 0.6f, false),
    DISC_NUMBER("Disc #", 0.4f, false),
    FILE_SIZE("File Size", 1f, false);
}

data class ColumnConfig(
    val visibleColumns: PersistentList<TrackListColumn>
)

val DEFAULT_COLUMN_CONFIG = ColumnConfig(
    visibleColumns = TrackListColumn.entries.filter { it.visibleByDefault }.toPersistentList()
)
