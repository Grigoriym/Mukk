package com.grappim.mukk.player

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.grappim.mukk.MukkLogger
import com.grappim.mukk.data.AudioDeviceInfo
import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.Version
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
            _state.update { it.copy(status = Status.STOPPED, positionMs = 0L) }
            stopPositionPolling()
            onTrackFinished?.invoke()
        })

        playBin.bus.connect(Bus.ERROR { _, _, message ->
            MukkLogger.error("AudioPlayer", "GStreamer error: $message")
            playBin.stop()
            _state.update { it.copy(status = Status.STOPPED, positionMs = 0L) }
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
                status = Status.PLAYING,
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
                status = Status.PAUSED,
                currentTrackPath = filePath,
                positionMs = positionMs,
                durationMs = durationMs
            )
        }
        scope.launch {
            delay(100)
            playBin.seek(positionMs, TimeUnit.MILLISECONDS)
            _state.update { it.copy(positionMs = positionMs) }
        }
    }

    fun pause() {
        playBin.pause()
        _state.update { it.copy(status = Status.PAUSED) }
        stopPositionPolling()
    }

    fun resume() {
        playBin.play()
        _state.update { it.copy(status = Status.PLAYING) }
        startPositionPolling()
    }

    fun stop() {
        playBin.stop()
        _state.update { it.copy(status = Status.STOPPED, currentTrackPath = null, positionMs = 0L) }
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

    fun setCurrentTrackPath(filePath: String) {
        _state.update { it.copy(currentTrackPath = filePath) }
    }

    fun getAvailableAudioDevices(): List<AudioDeviceInfo> {
        val devices = mutableListOf(AudioDeviceInfo("auto", "Automatic (default)"))
        try {
            val monitor = DeviceMonitor()
            monitor.addFilter("Audio/Sink", null)
            if (monitor.start()) {
                for (device in monitor.devices) {
                    devices.add(
                        AudioDeviceInfo(
                            name = device.displayName,
                            displayName = device.displayName
                        )
                    )
                }
                monitor.stop()
            }
        } catch (e: Exception) {
            MukkLogger.error("AudioPlayer", "Failed to enumerate audio devices", e)
        }
        return devices
    }

    fun setAudioDevice(deviceName: String) {
        if (deviceName == "auto") {
            playBin.set("audio-sink", null)
            return
        }
        try {
            val monitor = DeviceMonitor()
            monitor.addFilter("Audio/Sink", null)
            if (monitor.start()) {
                val device = monitor.devices.firstOrNull { it.displayName == deviceName }
                if (device != null) {
                    val element = device.createElement("audio-sink")
                    playBin.set("audio-sink", element)
                }
                monitor.stop()
            }
        } catch (e: Exception) {
            MukkLogger.error("AudioPlayer", "Failed to set audio device '$deviceName'", e)
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
                val currentDuration = _state.value.durationMs
                if (currentDuration <= 0L) {
                    val dur = playBin.queryDuration(TimeUnit.MILLISECONDS)
                    if (dur > 0) {
                        _state.update { it.copy(positionMs = pos, durationMs = dur) }
                    } else {
                        _state.update { it.copy(positionMs = pos) }
                    }
                } else {
                    _state.update { it.copy(positionMs = pos) }
                }
                delay(200)
            }
        }
    }

    private fun stopPositionPolling() {
        positionJob?.cancel()
        positionJob = null
    }
}
