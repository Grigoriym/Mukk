package com.grappim.mukk.core.model

data class FolderTreeState(
    val rootPath: String? = null,
    val expandedPaths: Set<String> = emptySet(),
    val selectedPath: String? = null,
    val version: Long = 0L
)
