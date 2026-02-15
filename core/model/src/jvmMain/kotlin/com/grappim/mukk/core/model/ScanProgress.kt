package com.grappim.mukk.core.model

data class ScanProgress(
    val isScanning: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0
)