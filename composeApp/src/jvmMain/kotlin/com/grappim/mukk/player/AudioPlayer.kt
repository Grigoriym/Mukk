package com.grappim.mukk.player

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.Version
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
            System.err.println("GStreamer error: $message")
            playBin.stop()
            _state.update { it.copy(status = Status.STOPPED, positionMs = 0L) }
            stopPositionPolling()
        })

        playBin.bus.connect { _, _, newState, _ ->
            if (newState == State.PLAYING) {
                val durationNs = playBin.queryDuration(TimeUnit.MILLISECONDS)
                _state.update { it.copy(durationMs = durationNs) }
            }
        }

        playBin.volume = _state.value.volume
    }

    fun play(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            System.err.println("File not found: $filePath")
            return
        }
        playBin.stop()
        playBin.setURI(file.toURI())
        playBin.play()
        _state.update {
            it.copy(
                status = Status.PLAYING,
                currentTrackPath = filePath,
                positionMs = 0L
            )
        }
        startPositionPolling()
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
        _state.update { it.copy(status = Status.STOPPED, positionMs = 0L) }
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

    fun dispose() {
        stopPositionPolling()
        playBin.stop()
        playBin.dispose()
    }

    private fun startPositionPolling() {
        stopPositionPolling()
        positionJob = scope.launch {
            while (isActive) {
                val pos = playBin.queryPosition(TimeUnit.MILLISECONDS)
                _state.update { it.copy(positionMs = pos) }
                delay(200)
            }
        }
    }

    private fun stopPositionPolling() {
        positionJob?.cancel()
        positionJob = null
    }
}
