package com.grappim.mukk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grappim.mukk.data.DatabaseInit
import com.grappim.mukk.data.FileEntry
import com.grappim.mukk.data.FolderTreeState
import com.grappim.mukk.data.MediaTrackData
import com.grappim.mukk.data.MediaTrackEntity
import com.grappim.mukk.data.MediaTracks
import com.grappim.mukk.data.PreferencesManager
import com.grappim.mukk.data.toData
import com.grappim.mukk.player.AudioPlayer
import com.grappim.mukk.player.PlaybackState
import com.grappim.mukk.player.Status
import com.grappim.mukk.scanner.FileScanner
import com.grappim.mukk.scanner.MetadataReader
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

    private val _selectedTrackPath = MutableStateFlow<String?>(null)
    val selectedTrackPath: StateFlow<String?> = _selectedTrackPath.asStateFlow()

    private val _currentAlbumArt = MutableStateFlow<ByteArray?>(null)
    val currentAlbumArt: StateFlow<ByteArray?> = _currentAlbumArt.asStateFlow()

    private val _currentLyrics = MutableStateFlow<String?>(null)
    val currentLyrics: StateFlow<String?> = _currentLyrics.asStateFlow()

    private var currentTrackIndex: Int = -1

    init {
        audioPlayer.onTrackFinished = { nextTrack() }

        val savedVolume = PreferencesManager.getDouble("volume", 0.8)
        audioPlayer.setVolume(savedVolume)

        loadTracks()
        restoreFolderTreeState()
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
            saveFolderTreeState()
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
        saveFolderTreeState()
    }

    fun selectFolder(path: String) {
        _folderTreeState.value = _folderTreeState.value.copy(selectedPath = path)
        _selectedTrackPath.value = null
        saveFolderTreeState()
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

    fun selectTrack(path: String) {
        _selectedTrackPath.value = path
    }

    fun playFile(entry: FileEntry) {
        if (entry.isDirectory) return
        val entries = _selectedFolderEntries.value
        currentTrackIndex = entries.indexOfFirst { it.file.absolutePath == entry.file.absolutePath }
        _selectedTrackPath.value = entry.file.absolutePath
        val filePath = entry.trackData?.filePath ?: entry.file.absolutePath
        audioPlayer.play(filePath)
        loadNowPlayingExtras(filePath)
    }

    fun playTrack(track: MediaTrackData) {
        currentTrackIndex = _selectedFolderEntries.value
            .indexOfFirst { it.file.absolutePath == track.filePath }
        _selectedTrackPath.value = track.filePath
        audioPlayer.play(track.filePath)
        loadNowPlayingExtras(track.filePath)
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
        _currentAlbumArt.value = null
        _currentLyrics.value = null
    }

    fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    fun setVolume(volume: Double) {
        audioPlayer.setVolume(volume)
        PreferencesManager.set("volume", volume)
    }

    fun nextTrack() {
        val entries = _selectedFolderEntries.value
        if (entries.isEmpty()) return
        val currentPath = playbackState.value.currentTrackPath
        val currentIdx = entries.indexOfFirst { it.file.absolutePath == currentPath }
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % entries.size
        val next = entries[nextIdx]
        currentTrackIndex = nextIdx
        _selectedTrackPath.value = next.file.absolutePath
        audioPlayer.play(next.file.absolutePath)
        loadNowPlayingExtras(next.file.absolutePath)
    }

    fun previousTrack() {
        val entries = _selectedFolderEntries.value
        if (entries.isEmpty()) return
        val currentPath = playbackState.value.currentTrackPath
        val currentIdx = entries.indexOfFirst { it.file.absolutePath == currentPath }
        val prevIdx = if (currentIdx <= 0) entries.lastIndex else currentIdx - 1
        val prev = entries[prevIdx]
        currentTrackIndex = prevIdx
        _selectedTrackPath.value = prev.file.absolutePath
        audioPlayer.play(prev.file.absolutePath)
        loadNowPlayingExtras(prev.file.absolutePath)
    }

    private fun saveFolderTreeState() {
        val state = _folderTreeState.value
        PreferencesManager.set("folderTree.rootPath", state.rootPath ?: "")
        PreferencesManager.set("folderTree.expandedPaths", state.expandedPaths.joinToString("|"))
        PreferencesManager.set("folderTree.selectedPath", state.selectedPath ?: "")
    }

    private fun restoreFolderTreeState() {
        val rootPath = PreferencesManager.getString("folderTree.rootPath", "").takeIf { it.isNotEmpty() }
            ?: return
        if (!File(rootPath).isDirectory) return

        val expandedPaths = PreferencesManager.getString("folderTree.expandedPaths", "")
            .split("|")
            .filter { it.isNotEmpty() && File(it).isDirectory }
            .toSet()
        val selectedPath = PreferencesManager.getString("folderTree.selectedPath", "").takeIf { it.isNotEmpty() }

        _folderTreeState.value = FolderTreeState(
            rootPath = rootPath,
            expandedPaths = expandedPaths,
            selectedPath = selectedPath
        )

        viewModelScope.launch(Dispatchers.IO) {
            if (selectedPath != null && File(selectedPath).isDirectory) {
                loadSelectedFolderEntries(selectedPath)
            }
        }
    }

    private fun loadNowPlayingExtras(filePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _currentAlbumArt.value = MetadataReader.readAlbumArt(filePath)
            _currentLyrics.value = MetadataReader.readLyrics(filePath)
        }
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
