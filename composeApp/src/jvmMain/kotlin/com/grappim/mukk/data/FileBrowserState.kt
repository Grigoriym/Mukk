package com.grappim.mukk.data

import java.io.File

data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val name: String,
    val trackData: MediaTrackData?
)

data class FileBrowserState(
    val rootPath: String? = null,
    val currentPath: String? = null,
    val entries: List<FileEntry> = emptyList(),
    val pathSegments: List<String> = emptyList()
)
