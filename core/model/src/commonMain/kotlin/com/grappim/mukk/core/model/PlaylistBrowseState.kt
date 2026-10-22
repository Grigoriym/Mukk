package com.grappim.mukk.core.model

/** Where the folder tree was inside a playlist, restored when the playlist is selected again. */
data class PlaylistBrowseState(
    val selectedPath: String,
    val expandedPaths: Set<String>
)
