package com.grappim.mukk.core.model

enum class RepeatMode { OFF, ONE, ALL }

enum class ResumeMode { PAUSED, PLAYING }

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
    val trackCount: Int = 0,
    val resumeMode: ResumeMode = ResumeMode.PAUSED,
    val mukkletEnabled: Boolean = false,
    val mukkletHost: String = DEFAULT_MUKKLET_HOST
)

const val DEFAULT_MUKKLET_HOST = "mukklet.local"
