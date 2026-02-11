package com.grappim.mukk.data

enum class RepeatMode { OFF, ONE, ALL }

data class AudioDeviceInfo(
    val name: String,
    val displayName: String
)

data class SettingsState(
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val availableAudioDevices: List<AudioDeviceInfo> = emptyList(),
    val selectedAudioDevice: String = "auto",
    val libraryPath: String? = null,
    val trackCount: Int = 0
)
