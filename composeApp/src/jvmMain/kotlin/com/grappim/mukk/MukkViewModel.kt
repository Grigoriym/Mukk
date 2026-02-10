package com.grappim.mukk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grappim.mukk.data.ColumnConfig
import com.grappim.mukk.data.DEFAULT_COLUMN_CONFIG
import com.grappim.mukk.data.DatabaseInit
import com.grappim.mukk.data.FileEntry
import com.grappim.mukk.data.FolderTreeState
import com.grappim.mukk.data.MediaTrackData
import com.grappim.mukk.data.MediaTrackEntity
import com.grappim.mukk.data.MukkUiState
import com.grappim.mukk.data.MediaTracks
import com.grappim.mukk.data.PreferencesManager
import com.grappim.mukk.data.TrackListColumn
import com.grappim.mukk.data.toData
import com.grappim.mukk.player.AudioPlayer
import com.grappim.mukk.player.PlaybackState
import com.grappim.mukk.player.Status
import com.grappim.mukk.scanner.FileScanner
import com.grappim.mukk.scanner.MetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

class MukkViewModel(
    private val audioPlayer: AudioPlayer
) : ViewModel() {

    private val _tracks = MutableStateFlow<List<MediaTrackData>>(emptyList())
    private val _folderTreeState = MutableStateFlow(FolderTreeState())
    private val _selectedFolderEntries = MutableStateFlow<List<FileEntry>>(emptyList())
    private val _selectedTrackPath = MutableStateFlow<String?>(null)
    private val _currentAlbumArt = MutableStateFlow<ByteArray?>(null)
    private val _currentLyrics = MutableStateFlow<String?>(null)
    private val _isScanning = MutableStateFlow(false)
    private val _columnConfig = MutableStateFlow(DEFAULT_COLUMN_CONFIG)

    val uiState: StateFlow<MukkUiState> = combine(
        combine(_folderTreeState, _selectedFolderEntries, _selectedTrackPath, _isScanning, _columnConfig) { fts, sfe, stp, scan, cc ->
            PrimaryState(fts, sfe, stp, scan, cc)
        },
        combine(audioPlayer.state, _tracks, _currentAlbumArt, _currentLyrics) { ps, tracks, art, lyrics ->
            PlaybackBundle(ps, tracks, art, lyrics)
        }
    ) { primary, playback ->
        val currentTrack = playback.tracks.firstOrNull { it.filePath == playback.playbackState.currentTrackPath }
        val playingFolderPath = playback.playbackState.currentTrackPath?.let { File(it).parent }
        MukkUiState(
            folderTreeState = primary.folderTreeState,
            selectedFolderEntries = primary.selectedFolderEntries,
            selectedTrackPath = primary.selectedTrackPath,
            isScanning = primary.isScanning,
            columnConfig = primary.columnConfig,
            playbackState = playback.playbackState,
            currentTrack = currentTrack,
            playingFolderPath = playingFolderPath,
            currentAlbumArt = playback.albumArt,
            currentLyrics = playback.lyrics
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MukkUiState())

    private var currentTrackIndex: Int = -1

    init {
        audioPlayer.onTrackFinished = { nextTrack() }

        val savedVolume = PreferencesManager.getDouble("volume", 0.8)
        audioPlayer.setVolume(savedVolume)

        loadTracks()
        restoreFolderTreeState()
        restorePlayingTrack()
        restoreColumnConfig()
    }

    fun scanDirectory(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            try {
                FileScanner.scan(File(path))
                loadTracks()
                _folderTreeState.value = FolderTreeState(
                    rootPath = path,
                    expandedPaths = setOf(path),
                    selectedPath = path
                )
                loadSelectedFolderEntries(path)
                saveFolderTreeState()
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun rescan() {
        val rootPath = _folderTreeState.value.rootPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            try {
                FileScanner.scan(File(rootPath))
                loadTracks()
                val selectedPath = _folderTreeState.value.selectedPath
                if (selectedPath != null) {
                    loadSelectedFolderEntries(selectedPath)
                }
            } finally {
                _isScanning.value = false
            }
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
        savePlayingTrack(filePath)
    }

    fun playTrack(track: MediaTrackData) {
        currentTrackIndex = _selectedFolderEntries.value
            .indexOfFirst { it.file.absolutePath == track.filePath }
        _selectedTrackPath.value = track.filePath
        audioPlayer.play(track.filePath)
        loadNowPlayingExtras(track.filePath)
        savePlayingTrack(track.filePath)
    }

    fun pause() {
        audioPlayer.pause()
    }

    fun resume() {
        audioPlayer.resume()
    }

    fun togglePlayPause() {
        when (audioPlayer.state.value.status) {
            Status.PLAYING -> pause()
            Status.PAUSED -> resume()
            Status.STOPPED, Status.IDLE -> {
                val currentPath = audioPlayer.state.value.currentTrackPath
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
        clearPlayingTrack()
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
        val currentPath = audioPlayer.state.value.currentTrackPath
        val currentIdx = entries.indexOfFirst { it.file.absolutePath == currentPath }
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % entries.size
        val next = entries[nextIdx]
        currentTrackIndex = nextIdx
        _selectedTrackPath.value = next.file.absolutePath
        audioPlayer.play(next.file.absolutePath)
        loadNowPlayingExtras(next.file.absolutePath)
        savePlayingTrack(next.file.absolutePath)
    }

    fun previousTrack() {
        val entries = _selectedFolderEntries.value
        if (entries.isEmpty()) return
        val currentPath = audioPlayer.state.value.currentTrackPath
        val currentIdx = entries.indexOfFirst { it.file.absolutePath == currentPath }
        val prevIdx = if (currentIdx <= 0) entries.lastIndex else currentIdx - 1
        val prev = entries[prevIdx]
        currentTrackIndex = prevIdx
        _selectedTrackPath.value = prev.file.absolutePath
        audioPlayer.play(prev.file.absolutePath)
        loadNowPlayingExtras(prev.file.absolutePath)
        savePlayingTrack(prev.file.absolutePath)
    }

    fun toggleColumnVisibility(column: TrackListColumn) {
        val current = _columnConfig.value.visibleColumns
        if (column in current) {
            if (current.size <= 1) return
            _columnConfig.value = ColumnConfig(current - column)
        } else {
            val newList = (current + column).sortedBy { it.ordinal }
            _columnConfig.value = ColumnConfig(newList)
        }
        saveColumnConfig()
    }

    private fun saveColumnConfig() {
        val serialized = _columnConfig.value.visibleColumns.joinToString("|") { it.name }
        PreferencesManager.set("trackList.columns", serialized)
    }

    private fun restoreColumnConfig() {
        val saved = PreferencesManager.getString("trackList.columns", "").takeIf { it.isNotEmpty() }
            ?: return
        val columns = saved.split("|").mapNotNull { name ->
            try {
                TrackListColumn.valueOf(name)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        if (columns.isNotEmpty()) {
            _columnConfig.value = ColumnConfig(columns)
        }
    }

    private fun savePlayingTrack(filePath: String) {
        PreferencesManager.set("playingTrack", filePath)
    }

    private fun clearPlayingTrack() {
        PreferencesManager.set("playingTrack", "")
    }

    private fun restorePlayingTrack() {
        val path = PreferencesManager.getString("playingTrack", "").takeIf { it.isNotEmpty() }
            ?: return
        if (!File(path).exists()) return

        audioPlayer.setCurrentTrackPath(path)
        _selectedTrackPath.value = path

        val entries = _selectedFolderEntries.value
        currentTrackIndex = entries.indexOfFirst { it.file.absolutePath == path }

        loadNowPlayingExtras(path)
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

    private data class PrimaryState(
        val folderTreeState: FolderTreeState,
        val selectedFolderEntries: List<FileEntry>,
        val selectedTrackPath: String?,
        val isScanning: Boolean,
        val columnConfig: ColumnConfig
    )

    private data class PlaybackBundle(
        val playbackState: PlaybackState,
        val tracks: List<MediaTrackData>,
        val albumArt: ByteArray?,
        val lyrics: String?
    )

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "wav", "aac", "opus", "m4a")
    }
}
