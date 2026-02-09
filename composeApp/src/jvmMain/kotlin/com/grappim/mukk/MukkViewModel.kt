package com.grappim.mukk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grappim.mukk.data.DatabaseInit
import com.grappim.mukk.data.FileBrowserState
import com.grappim.mukk.data.FileEntry
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

    private val _libraryBrowserState = MutableStateFlow(FileBrowserState())
    val libraryBrowserState: StateFlow<FileBrowserState> = _libraryBrowserState.asStateFlow()

    private val _nowPlayingFolderEntries = MutableStateFlow<List<FileEntry>>(emptyList())
    val nowPlayingFolderEntries: StateFlow<List<FileEntry>> = _nowPlayingFolderEntries.asStateFlow()

    private val _nowPlayingFolderName = MutableStateFlow<String?>(null)
    val nowPlayingFolderName: StateFlow<String?> = _nowPlayingFolderName.asStateFlow()

    private var currentTrackIndex: Int = -1

    init {
        audioPlayer.onTrackFinished = { nextTrack() }
        loadTracks()
    }

    fun scanDirectory(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            FileScanner.scan(File(path))
            loadTracks()
            _libraryBrowserState.value = _libraryBrowserState.value.copy(rootPath = path)
            navigateToDirectory(path)
        }
    }

    fun navigateToDirectory(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = listDirectoryEntries(path)
            val root = _libraryBrowserState.value.rootPath ?: path
            val segments = buildPathSegments(root, path)
            _libraryBrowserState.value = _libraryBrowserState.value.copy(
                currentPath = path,
                entries = entries,
                pathSegments = segments
            )
        }
    }

    fun navigateUp() {
        val current = _libraryBrowserState.value.currentPath ?: return
        val root = _libraryBrowserState.value.rootPath ?: return
        if (current == root) return
        val parent = File(current).parent ?: return
        if (!parent.startsWith(root)) return
        navigateToDirectory(parent)
    }

    fun navigateToRoot() {
        val root = _libraryBrowserState.value.rootPath ?: return
        navigateToDirectory(root)
    }

    fun playFile(entry: FileEntry) {
        if (entry.isDirectory) return
        val trackData = entry.trackData
        if (trackData != null) {
            playTrack(trackData)
        } else {
            audioPlayer.play(entry.file.absolutePath)
            updateNowPlayingFolder(entry.file.absolutePath)
        }
    }

    fun playTrack(track: MediaTrackData) {
        currentTrackIndex = _nowPlayingFolderEntries.value
            .indexOfFirst { it.file.absolutePath == track.filePath }
        audioPlayer.play(track.filePath)
        updateNowPlayingFolder(track.filePath)
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
        val folderEntries = _nowPlayingFolderEntries.value.filter { !it.isDirectory }
        if (folderEntries.isEmpty()) return
        val currentPath = playbackState.value.currentTrackPath
        val currentIdx = folderEntries.indexOfFirst { it.file.absolutePath == currentPath }
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % folderEntries.size
        val next = folderEntries[nextIdx]
        currentTrackIndex = nextIdx
        audioPlayer.play(next.file.absolutePath)
        // No need to updateNowPlayingFolder — we're already in the same folder
    }

    fun previousTrack() {
        val folderEntries = _nowPlayingFolderEntries.value.filter { !it.isDirectory }
        if (folderEntries.isEmpty()) return
        val currentPath = playbackState.value.currentTrackPath
        val currentIdx = folderEntries.indexOfFirst { it.file.absolutePath == currentPath }
        val prevIdx = if (currentIdx <= 0) folderEntries.lastIndex else currentIdx - 1
        val prev = folderEntries[prevIdx]
        currentTrackIndex = prevIdx
        audioPlayer.play(prev.file.absolutePath)
    }

    private fun updateNowPlayingFolder(trackPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val parentDir = File(trackPath).parentFile ?: return@launch
            _nowPlayingFolderName.value = parentDir.name
            val entries = listDirectoryEntries(parentDir.absolutePath)
                .filter { !it.isDirectory }
            _nowPlayingFolderEntries.value = entries
            // Update currentTrackIndex within now-playing entries
            currentTrackIndex = entries.indexOfFirst { it.file.absolutePath == trackPath }
        }
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

    private fun buildPathSegments(root: String, current: String): List<String> {
        if (current == root) return listOf(File(root).name)
        val relative = File(current).toRelativeString(File(root))
        return listOf(File(root).name) + relative.split(File.separator)
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
