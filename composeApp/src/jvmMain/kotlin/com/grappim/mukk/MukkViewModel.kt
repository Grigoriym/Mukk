package com.grappim.mukk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grappim.mukk.data.ColumnConfig
import com.grappim.mukk.data.DEFAULT_COLUMN_CONFIG
import com.grappim.mukk.data.FileEntry
import com.grappim.mukk.data.FolderTreeState
import com.grappim.mukk.data.MediaTrackData
import com.grappim.mukk.data.MukkUiState
import com.grappim.mukk.data.PreferencesManager
import com.grappim.mukk.data.TrackListColumn
import com.grappim.mukk.data.TrackRepository
import com.grappim.mukk.player.AudioPlayer
import com.grappim.mukk.player.PlaybackState
import com.grappim.mukk.player.Status
import com.grappim.mukk.scanner.FileScanner
import com.grappim.mukk.scanner.FileSystemEvent
import com.grappim.mukk.scanner.FileSystemWatcher
import com.grappim.mukk.scanner.MetadataReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class MukkViewModel(
    private val audioPlayer: AudioPlayer,
    private val trackRepository: TrackRepository,
    private val preferencesManager: PreferencesManager,
    private val fileScanner: FileScanner,
    private val metadataReader: MetadataReader,
    private val fileSystemWatcher: FileSystemWatcher
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
    private var watcherCollectionJob: Job? = null

    init {
        audioPlayer.onTrackFinished = { nextTrack() }

        val savedVolume = preferencesManager.getDouble("volume", 0.8)
        audioPlayer.setVolume(savedVolume)

        loadTracks()
        restoreFolderTreeState()
        restorePlayingTrack()
        restoreColumnConfig()
    }

    fun scanDirectory(path: String) {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                fileScanner.scan(File(path))
                loadTracksSync()
                _folderTreeState.value = FolderTreeState(
                    rootPath = path,
                    expandedPaths = setOf(path),
                    selectedPath = path
                )
                loadSelectedFolderEntries(path)
                saveFolderTreeState()
                startWatching(path)
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun rescan() {
        val rootPath = _folderTreeState.value.rootPath ?: return
        viewModelScope.launch {
            _isScanning.value = true
            try {
                fileScanner.scan(File(rootPath))
                loadTracksSync()
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
        viewModelScope.launch {
            val scanned = fileScanner.scanFolder(File(path))
            if (scanned > 0) loadTracksSync()
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
        preferencesManager.set("volume", volume)
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
        preferencesManager.set("trackList.columns", serialized)
    }

    private fun restoreColumnConfig() {
        val saved = preferencesManager.getString("trackList.columns", "").takeIf { it.isNotEmpty() }
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
        preferencesManager.set("playingTrack", filePath)
    }

    private fun clearPlayingTrack() {
        preferencesManager.set("playingTrack", "")
    }

    private fun restorePlayingTrack() {
        val path = preferencesManager.getString("playingTrack", "").takeIf { it.isNotEmpty() }
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
        preferencesManager.set("folderTree.rootPath", state.rootPath ?: "")
        preferencesManager.set("folderTree.expandedPaths", state.expandedPaths.joinToString("|"))
        preferencesManager.set("folderTree.selectedPath", state.selectedPath ?: "")
    }

    private fun restoreFolderTreeState() {
        val rootPath = preferencesManager.getString("folderTree.rootPath", "").takeIf { it.isNotEmpty() }
            ?: return
        if (!File(rootPath).isDirectory) return

        val expandedPaths = preferencesManager.getString("folderTree.expandedPaths", "")
            .split("|")
            .filter { it.isNotEmpty() && File(it).isDirectory }
            .toSet()
        val selectedPath = preferencesManager.getString("folderTree.selectedPath", "").takeIf { it.isNotEmpty() }

        _folderTreeState.value = FolderTreeState(
            rootPath = rootPath,
            expandedPaths = expandedPaths,
            selectedPath = selectedPath
        )

        viewModelScope.launch {
            if (selectedPath != null && File(selectedPath).isDirectory) {
                loadSelectedFolderEntries(selectedPath)
            }
        }
        startWatching(rootPath)
    }

    private fun loadNowPlayingExtras(filePath: String) {
        viewModelScope.launch {
            _currentAlbumArt.value = metadataReader.readAlbumArt(filePath)
            _currentLyrics.value = metadataReader.readLyrics(filePath)
        }
    }

    private suspend fun loadSelectedFolderEntries(path: String) {
        val entries = listDirectoryEntries(path).filter { !it.isDirectory }
        _selectedFolderEntries.value = entries
    }

    private fun containsAudioFiles(dir: File): Boolean {
        return dir.walkTopDown().any { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
    }

    private suspend fun listDirectoryEntries(path: String): List<FileEntry> {
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

    private suspend fun lookupTrackData(filePath: String): MediaTrackData? =
        trackRepository.findByPath(filePath)

    private suspend fun loadTracksSync() {
        _tracks.value = trackRepository.getAllTracks()
    }

    private fun startWatching(rootPath: String) {
        watcherCollectionJob?.cancel()
        fileSystemWatcher.watch(File(rootPath))
        watcherCollectionJob = viewModelScope.launch {
            fileSystemWatcher.events.collect { event ->
                handleFileSystemEvent(event)
            }
        }
    }

    private suspend fun handleFileSystemEvent(event: FileSystemEvent) {
        when (event) {
            is FileSystemEvent.AudioFileChanged -> {
                fileScanner.scanFolder(File(event.directory))
                loadTracksSync()
                val selectedPath = _folderTreeState.value.selectedPath
                if (selectedPath == event.directory) {
                    loadSelectedFolderEntries(selectedPath)
                }
            }

            is FileSystemEvent.AudioFileDeleted -> {
                fileScanner.removeTrack(event.filePath)
                loadTracksSync()
                val selectedPath = _folderTreeState.value.selectedPath
                if (selectedPath == event.directory) {
                    loadSelectedFolderEntries(selectedPath)
                }
                val currentPath = audioPlayer.state.value.currentTrackPath
                if (currentPath == event.filePath) {
                    stop()
                }
            }

            is FileSystemEvent.DirectoryCreated -> {
                bumpFolderTreeVersion()
            }

            is FileSystemEvent.DirectoryDeleted -> {
                val deletedPath = event.directoryPath
                val current = _folderTreeState.value
                val newExpanded = current.expandedPaths.filter { !it.startsWith(deletedPath) }.toSet()
                val newSelected = if (current.selectedPath?.startsWith(deletedPath) == true) null else current.selectedPath
                _folderTreeState.value = current.copy(
                    expandedPaths = newExpanded,
                    selectedPath = newSelected,
                    version = current.version + 1
                )
                if (newSelected != current.selectedPath) {
                    saveFolderTreeState()
                }
            }
        }
    }

    private fun bumpFolderTreeVersion() {
        _folderTreeState.update { it.copy(version = it.version + 1) }
    }

    private fun loadTracks() {
        viewModelScope.launch {
            _tracks.value = trackRepository.getAllTracks()
        }
    }

    override fun onCleared() {
        super.onCleared()
        fileSystemWatcher.stop()
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
