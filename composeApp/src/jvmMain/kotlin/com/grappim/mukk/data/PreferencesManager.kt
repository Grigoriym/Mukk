package com.grappim.mukk.data

import com.grappim.mukk.MukkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Properties

class PreferencesManager {

    private val prefsFile = File(
        System.getProperty("user.home"),
        ".local/share/mukk/preferences.properties"
    )

    private val properties = Properties()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeMutex = Mutex()

    init {
        if (prefsFile.exists()) {
            prefsFile.inputStream().use { properties.load(it) }
        }
    }

    // --- Typed properties ---

    var volume: Double
        get() = getDouble("volume", 0.8)
        set(value) = set("volume", value)

    var windowWidth: Int
        get() = getInt("window.width", 1024)
        set(value) = set("window.width", value)

    var windowHeight: Int
        get() = getInt("window.height", 700)
        set(value) = set("window.height", value)

    var panelLeftWidth: Int
        get() = getInt("panel.leftWidth", 250)
        set(value) = set("panel.leftWidth", value)

    var panelRightWidth: Int
        get() = getInt("panel.rightWidth", 280)
        set(value) = set("panel.rightWidth", value)

    var folderTreeRootPath: String
        get() = getString("folderTree.rootPath", "")
        set(value) = set("folderTree.rootPath", value)

    var folderTreeExpandedPaths: Set<String>
        get() = getString("folderTree.expandedPaths", "")
            .split("|").filter { it.isNotEmpty() }.toSet()
        set(value) = set("folderTree.expandedPaths", value.joinToString("|"))

    var folderTreeSelectedPath: String
        get() = getString("folderTree.selectedPath", "")
        set(value) = set("folderTree.selectedPath", value)

    var playingTrack: String
        get() = getString("playingTrack", "")
        set(value) = set("playingTrack", value)

    var trackListColumns: List<TrackListColumn>
        get() {
            val saved = getString("trackList.columns", "").takeIf { it.isNotEmpty() }
                ?: return emptyList()
            return saved.split("|").mapNotNull { name ->
                try {
                    TrackListColumn.valueOf(name)
                } catch (e: IllegalArgumentException) {
                    MukkLogger.warn("PreferencesManager", "Unknown column name: $name", e)
                    null
                }
            }
        }
        set(value) = set("trackList.columns", value.joinToString("|") { it.name })

    var repeatMode: RepeatMode
        get() = try {
            RepeatMode.valueOf(getString("playback.repeatMode", "OFF"))
        } catch (e: IllegalArgumentException) {
            MukkLogger.warn("PreferencesManager", "Invalid saved repeat mode, defaulting to OFF", e)
            RepeatMode.OFF
        }
        set(value) = set("playback.repeatMode", value.name)

    var shuffleEnabled: Boolean
        get() = getBoolean("playback.shuffle", false)
        set(value) = set("playback.shuffle", value)

    var resumeMode: ResumeMode
        get() = try {
            ResumeMode.valueOf(getString("playback.resumeMode", "PAUSED"))
        } catch (e: IllegalArgumentException) {
            MukkLogger.warn("PreferencesManager", "Invalid saved resume mode, defaulting to PAUSED", e)
            ResumeMode.PAUSED
        }
        set(value) = set("playback.resumeMode", value.name)

    var playbackPositionMs: Long
        get() = getLong("playback.positionMs", 0L)
        set(value) = set("playback.positionMs", value)

    var playbackDurationMs: Long
        get() = getLong("playback.durationMs", 0L)
        set(value) = set("playback.durationMs", value)

    var playbackWasPlaying: Boolean
        get() = getBoolean("playback.wasPlaying", false)
        set(value) = set("playback.wasPlaying", value)

    var audioDevice: String
        get() = getString("audio.device", "auto")
        set(value) = set("audio.device", value)

    // --- Public utility ---

    fun clear() {
        properties.clear()
        scope.launch {
            writeMutex.withLock {
                save()
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    // --- Private generic access ---

    private fun getString(key: String, default: String): String =
        properties.getProperty(key, default)

    private fun getDouble(key: String, default: Double): Double =
        properties.getProperty(key)?.toDoubleOrNull() ?: default

    private fun getInt(key: String, default: Int): Int =
        properties.getProperty(key)?.toIntOrNull() ?: default

    private fun getLong(key: String, default: Long): Long =
        properties.getProperty(key)?.toLongOrNull() ?: default

    private fun getBoolean(key: String, default: Boolean): Boolean =
        properties.getProperty(key)?.toBooleanStrictOrNull() ?: default

    private fun set(key: String, value: Any) {
        properties.setProperty(key, value.toString())
        scope.launch {
            writeMutex.withLock {
                save()
            }
        }
    }

    private fun save() {
        prefsFile.parentFile.mkdirs()
        prefsFile.outputStream().use { properties.store(it, null) }
    }
}
