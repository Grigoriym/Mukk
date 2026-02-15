package com.grappim.mukk.model

internal data class TreeItem(
    val path: String,
    val name: String,
    val depth: Int,
    val isExpanded: Boolean,
    val isSelected: Boolean,
    val isPlaying: Boolean,
    val hasChildren: Boolean
)
