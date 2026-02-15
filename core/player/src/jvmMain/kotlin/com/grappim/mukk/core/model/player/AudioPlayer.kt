package com.grappim.mukk.core.model.player

import com.grappim.mukk.core.model.AudioDeviceInfo
import com.grappim.mukk.core.model.MukkLogger
import com.grappim.mukk.core.model.PlaybackState
import com.grappim.mukk.core.model.PlaybackStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.Version
import org.freedesktop.gstreamer.device.Device
import org.freedesktop.gstreamer.device.DeviceMonitor
import org.freedesktop.gstreamer.elements.PlayBin
import java.io.File
import java.util.concurrent.TimeUnit

class AudioPlayer {

    private lateinit var playBin: PlayBin
    private val scope = CoroutineScope(Dispatchers.Default)
    private var positionJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    var onTrackFinished: (() -> Unit)? = null

    fun init() {
        Gst.init(Version.BASELINE, "Mukk")
        playBin = PlayBin("MukkPlayer")

        playBin.bus.connect(Bus.EOS { _ ->
            _state.update { it.copy(playbackStatus = PlaybackStatus.STOPPED, positionMs = 0L) }
            stopPositionPolling()
            onTrackFinished?.invoke()
        })

        playBin.bus.connect(Bus.ERROR { _, _, message ->
            MukkLogger.error("AudioPlayer", "GStreamer error: $message")
            playBin.stop()
            _state.update {
                it.copy(
                    playbackStatus = PlaybackStatus.STOPPED,
                    currentTrackPath = null,
                    positionMs = 0L
                )
            }
            stopPositionPolling()
        })

        playBin.bus.connect { _, _, newState, _ ->
            if (newState == State.PLAYING || newState == State.PAUSED) {
                val durationMs = playBin.queryDuration(TimeUnit.MILLISECONDS)
                if (durationMs > 0) {
                    _state.update { it.copy(durationMs = durationMs) }
                }
            }
        }

        playBin.volume = _state.value.volume
    }

    fun play(filePath: String, startPositionMs: Long = 0L, startDurationMs: Long = 0L) {
        val file = File(filePath)
        if (!file.exists()) {
            MukkLogger.error("AudioPlayer", "File not found: $filePath")
            return
        }
        playBin.stop()
        playBin.setURI(file.toURI())
        playBin.play()
        _state.update {
            it.copy(
                playbackStatus = PlaybackStatus.PLAYING,
                currentTrackPath = filePath,
                positionMs = startPositionMs,
                durationMs = startDurationMs
            )
        }
        if (startPositionMs > 0L) {
            playBin.seek(startPositionMs, TimeUnit.MILLISECONDS)
        }
        startPositionPolling()
    }

    fun playPaused(filePath: String, positionMs: Long, durationMs: Long = 0L) {
        val file = File(filePath)
        if (!file.exists()) {
            MukkLogger.error("AudioPlayer", "File not found: $filePath")
            return
        }
        playBin.stop()
        playBin.setURI(file.toURI())
        playBin.pause()
        _state.update {
            it.copy(
                playbackStatus = PlaybackStatus.PAUSED,
                currentTrackPath = filePath,
                positionMs = positionMs,
                durationMs = durationMs
            )
        }
        scope.launch {
            // GStreamer needs time to transition to PAUSED before seeking is possible
            delay(100)
            playBin.seek(positionMs, TimeUnit.MILLISECONDS)
            _state.update { it.copy(positionMs = positionMs) }
        }
    }

    fun pause() {
        playBin.pause()
        _state.update { it.copy(playbackStatus = PlaybackStatus.PAUSED) }
        stopPositionPolling()
    }

    fun resume() {
        playBin.play()
        _state.update { it.copy(playbackStatus = PlaybackStatus.PLAYING) }
        startPositionPolling()
    }

    fun stop() {
        playBin.stop()
        _state.update { it.copy(playbackStatus = PlaybackStatus.STOPPED, currentTrackPath = null, positionMs = 0L) }
        stopPositionPolling()
    }

    fun seekTo(positionMs: Long) {
        playBin.seek(positionMs, TimeUnit.MILLISECONDS)
        _state.update { it.copy(positionMs = positionMs) }
    }

    fun setVolume(volume: Double) {
        val clamped = volume.coerceIn(0.0, 1.0)
        playBin.volume = clamped
        _state.update { it.copy(volume = clamped) }
    }

    /**
     * Sets the track path in state without loading into GStreamer.
     * Used to restore the last-played track on startup without auto-playing.
     */
    fun setRestoredTrackPath(filePath: String) {
        _state.update { it.copy(currentTrackPath = filePath) }
    }

    fun getAvailableAudioDevices(): List<AudioDeviceInfo> {
        val devices = mutableListOf(AudioDeviceInfo("auto", "Automatic (default)"))
        withAudioSinkDevices("enumerate audio devices") { monitorDevices ->
            for (device in monitorDevices) {
                devices.add(AudioDeviceInfo(name = device.displayName, displayName = device.displayName))
            }
        }
        return devices
    }

    fun setAudioDevice(deviceName: String) {
        if (deviceName == "auto") {
            playBin.set("audio-sink", null)
            return
        }
        withAudioSinkDevices("set audio device '$deviceName'") { devices ->
            val device = devices.firstOrNull { it.displayName == deviceName }
            if (device != null) {
                playBin.set("audio-sink", device.createElement("audio-sink"))
            }
        }
    }

    private fun withAudioSinkDevices(action: String, block: (List<Device>) -> Unit) {
        try {
            val monitor = DeviceMonitor()
            monitor.addFilter("Audio/Sink", null)
            if (monitor.start()) {
                block(monitor.devices)
                monitor.stop()
            }
        } catch (e: Exception) {
            MukkLogger.error("AudioPlayer", "Failed to $action", e)
        }
    }

    fun dispose() {
        stopPositionPolling()
        scope.cancel()
        playBin.stop()
        playBin.dispose()
    }

    private fun startPositionPolling() {
        stopPositionPolling()
        positionJob = scope.launch {
            while (isActive) {
                val pos = playBin.queryPosition(TimeUnit.MILLISECONDS)
                val dur = if (_state.value.durationMs <= 0L) {
                    playBin.queryDuration(TimeUnit.MILLISECONDS).takeIf { it > 0 }
                } else {
                    null
                }
                _state.update { it.copy(positionMs = pos, durationMs = dur ?: it.durationMs) }
                delay(200)
            }
        }
    }

    private fun stopPositionPolling() {
        positionJob?.cancel()
        positionJob = null
    }
}
