package com.grappim.mukk.core.model

import java.io.File

data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val name: String,
    val trackData: MediaTrackData?
)
