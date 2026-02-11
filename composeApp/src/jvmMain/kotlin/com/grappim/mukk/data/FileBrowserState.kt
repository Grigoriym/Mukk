package com.grappim.mukk.data

import java.io.File

data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val name: String,
    val trackData: MediaTrackData?
)

data class FolderTreeState(
    val rootPath: String? = null,
    val expandedPaths: Set<String> = emptySet(),
    val selectedPath: String? = null,
    val version: Long = 0L
)
