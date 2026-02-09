package com.grappim.mukk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grappim.mukk.data.DatabaseInit
import com.grappim.mukk.data.FileEntry
import com.grappim.mukk.data.FolderTreeState
import com.grappim.mukk.data.MediaTrackData
import com.grappim.mukk.data.MediaTrackEntity
import com.grappim.mukk.data.MediaTracks
import com.grappim.mukk.data.toData
import com.grappim.mukk.player.AudioPlayer
import com.grappim.mukk.player.PlaybackState
import com.grappim.mukk.player.Status
import com.grappim.mukk.scanner.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

class MukkViewModel(
    private val audioPlayer: AudioPlayer
) : ViewModel() {

    private val _tracks = MutableStateFlow<List<MediaTrackData>>(emptyList())
    val tracks: StateFlow<List<MediaTrackData>> = _tracks.asStateFlow()

    val playbackState: StateFlow<PlaybackState> = audioPlayer.state

    private val _folderTreeState = MutableStateFlow(FolderTreeState())
    val folderTreeState: StateFlow<FolderTreeState> = _folderTreeState.asStateFlow()

    private val _selectedFolderEntries = MutableStateFlow<List<FileEntry>>(emptyList())
    val selectedFolderEntries: StateFlow<List<FileEntry>> = _selectedFolderEntries.asStateFlow()

    private var currentTrackIndex: Int = -1

    init {
        audioPlayer.onTrackFinished = { nextTrack() }
        loadTracks()
    }

    fun scanDirectory(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            FileScanner.scan(File(path))
            loadTracks()
            _folderTreeState.value = FolderTreeState(
                rootPath = path,
                expandedPaths = setOf(path),
                selectedPath = path
            )
            loadSelectedFolderEntries(path)
        }
    }

    fun toggleFolderExpanded(path: String) {
        val current = _folderTreeState.value
        val newExpanded = if (path in current.expandedPaths) {
            current.expandedPaths - path
        } else {
            current.expandedPaths + path
        }
        _folderTreeState.value = current.copy(expandedPaths = newExpanded)
    }

    fun selectFolder(path: String) {
        _folderTreeState.value = _folderTreeState.value.copy(selectedPath = path)
        viewModelScope.launch(Dispatchers.IO) {
            loadSelectedFolderEntries(path)
        }
    }

    fun getSubfolders(path: String): List<Pair<File, Boolean>> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filter { it.isDirectory && containsAudioFiles(it) }
            .sortedBy { it.name.lowercase() }
            .map { child ->
                val hasChildren = child.listFiles()
                    ?.any { it.isDirectory && containsAudioFiles(it) }
                    ?: false
                child to hasChildren
            }
    }

    fun playFile(entry: FileEntry) {
        if (entry.isDirectory) return
        val entries = _selectedFolderEntries.value
        currentTrackIndex = entries.indexOfFirst { it.file.absolutePath == entry.file.absolutePath }
        val trackData = entry.trackData
        if (trackData != null) {
            audioPlayer.play(trackData.filePath)
        } else {
            audioPlayer.play(entry.file.absolutePath)
        }
    }

    fun playTrack(track: MediaTrackData) {
        currentTrackIndex = _selectedFolderEntries.value
            .indexOfFirst { it.file.absolutePath == track.filePath }
        audioPlayer.play(track.filePath)
    }

    fun pause() {
        audioPlayer.pause()
    }

    fun resume() {
        audioPlayer.resume()
    }

    fun togglePlayPause() {
        when (playbackState.value.status) {
            Status.PLAYING -> pause()
            Status.PAUSED -> resume()
            Status.STOPPED, Status.IDLE -> {
                val currentPath = playbackState.value.currentTrackPath
                if (currentPath != null) {
                    audioPlayer.play(currentPath)
                }
            }
        }
    }

    fun stop() {
        audioPlayer.stop()
    }

    fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    fun setVolume(volume: Double) {
        audioPlayer.setVolume(volume)
    }

    fun nextTrack() {
        val entries = _selectedFolderEntries.value
        if (entries.isEmpty()) return
        val currentPath = playbackState.value.currentTrackPath
        val currentIdx = entries.indexOfFirst { it.file.absolutePath == currentPath }
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % entries.size
        val next = entries[nextIdx]
        currentTrackIndex = nextIdx
        audioPlayer.play(next.file.absolutePath)
    }

    fun previousTrack() {
        val entries = _selectedFolderEntries.value
        if (entries.isEmpty()) return
        val currentPath = playbackState.value.currentTrackPath
        val currentIdx = entries.indexOfFirst { it.file.absolutePath == currentPath }
        val prevIdx = if (currentIdx <= 0) entries.lastIndex else currentIdx - 1
        val prev = entries[prevIdx]
        currentTrackIndex = prevIdx
        audioPlayer.play(prev.file.absolutePath)
    }

    private fun loadSelectedFolderEntries(path: String) {
        val entries = listDirectoryEntries(path).filter { !it.isDirectory }
        _selectedFolderEntries.value = entries
    }

    private fun containsAudioFiles(dir: File): Boolean {
        return dir.walkTopDown().any { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
    }

    private fun listDirectoryEntries(path: String): List<FileEntry> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()

        val children = dir.listFiles() ?: return emptyList()

        return children
            .filter { it.isDirectory || it.extension.lowercase() in AUDIO_EXTENSIONS }
            .map { file ->
                val trackData = if (!file.isDirectory) {
                    lookupTrackData(file.absolutePath)
                } else null

                FileEntry(
                    file = file,
                    isDirectory = file.isDirectory,
                    name = if (!file.isDirectory && trackData != null) {
                        trackData.title
                    } else {
                        file.name
                    },
                    trackData = trackData
                )
            }
            .sortedWith(compareBy<FileEntry> { !it.isDirectory }
                .thenBy { if (!it.isDirectory) it.trackData?.trackNumber ?: Int.MAX_VALUE else 0 }
                .thenBy { it.name.lowercase() })
    }

    private fun lookupTrackData(filePath: String): MediaTrackData? {
        return transaction(DatabaseInit.database) {
            MediaTrackEntity.find(MediaTracks.filePath eq filePath)
                .firstOrNull()
                ?.toData()
        }
    }

    private fun loadTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            val data = transaction(DatabaseInit.database) {
                MediaTrackEntity.all().map { it.toData() }
            }
            _tracks.value = data
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.dispose()
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "wav", "aac", "opus", "m4a")
    }
}
