package com.grappim.mukk

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grappim.mukk.core.data.PreferencesManager
import com.grappim.mukk.core.data.TrackRepository
import com.grappim.mukk.core.model.*
import com.grappim.mukk.core.model.player.AudioPlayer
import com.grappim.mukk.core.model.scanner.FileScanner
import com.grappim.mukk.core.model.scanner.FileSystemEvent
import com.grappim.mukk.core.model.scanner.FileSystemWatcher
import com.grappim.mukk.core.model.scanner.MetadataReader
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
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
    private val _selectedFolderEntries = MutableStateFlow<ImmutableList<FileEntry>>(persistentListOf())
    private val _selectedTrackPath = MutableStateFlow<String?>(null)
    private val _currentAlbumArt = MutableStateFlow<ImageBitmap?>(null)
    private val _currentLyrics = MutableStateFlow<String?>(null)
    private val _scanProgress = MutableStateFlow(ScanProgress())
    private val _columnConfig = MutableStateFlow(DEFAULT_COLUMN_CONFIG)
    private val _settingsState = MutableStateFlow(SettingsState())

    val uiState: StateFlow<MukkUiState> = combine(
        combine(
            _folderTreeState,
            _selectedFolderEntries,
            _selectedTrackPath,
            _scanProgress,
            _columnConfig
        ) { fts, sfe, stp, sp, cc ->
            PrimaryState(fts, sfe, stp, sp, cc)
        },
        combine(audioPlayer.state, _tracks, _currentAlbumArt, _currentLyrics) { ps, tracks, art, lyrics ->
            PlaybackBundle(ps, tracks, art, lyrics)
        },
        _settingsState
    ) { primary, playback, settings ->
        val currentTrack = playback.tracks.firstOrNull { it.filePath == playback.playbackState.currentTrackPath }
        val playingFolderPath = playback.playbackState.currentTrackPath?.let { File(it).parent }
        MukkUiState(
            folderTreeState = primary.folderTreeState,
            selectedFolderEntries = primary.selectedFolderEntries,
            selectedTrackPath = primary.selectedTrackPath,
            scanProgress = primary.scanProgress,
            columnConfig = primary.columnConfig,
            playbackState = playback.playbackState,
            currentTrack = currentTrack,
            playingFolderPath = playingFolderPath,
            currentAlbumArt = playback.albumArt,
            currentLyrics = playback.lyrics,
            settingsState = settings
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MukkUiState())

    private var currentTrackIndex: Int = -1
    private var watcherCollectionJob: Job? = null

    init {
        audioPlayer.onTrackFinished = { nextTrack() }

        val savedVolume = preferencesManager.volume
        audioPlayer.setVolume(savedVolume)

        loadTracks()
        restoreFolderTreeState()
        restoreColumnConfig()
        restoreSettings()
        restorePlayingTrack()
        loadAudioDevices()
    }

    fun scanDirectory(path: String) {
        viewModelScope.launch {
            _scanProgress.value = ScanProgress(isScanning = true)
            try {
                fileScanner.scan(File(path)) { scanned, total ->
                    _scanProgress.value = ScanProgress(true, scanned, total)
                }
                loadTracksSync()
                _folderTreeState.value = FolderTreeState(
                    rootPath = path,
                    expandedPaths = setOf(path),
                    selectedPath = path
                )
                loadSelectedFolderEntries(path)
                saveFolderTreeState()
                startWatching(path)
                updateSettingsLibraryInfo()
            } finally {
                _scanProgress.value = ScanProgress()
            }
        }
    }

    fun rescan() {
        val rootPath = _folderTreeState.value.rootPath ?: return
        viewModelScope.launch {
            _scanProgress.value = ScanProgress(isScanning = true)
            try {
                fileScanner.scan(File(rootPath)) { scanned, total ->
                    _scanProgress.value = ScanProgress(true, scanned, total)
                }
                loadTracksSync()
                val selectedPath = _folderTreeState.value.selectedPath
                if (selectedPath != null) {
                    loadSelectedFolderEntries(selectedPath)
                }
            } finally {
                _scanProgress.value = ScanProgress()
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

    fun pause() {
        audioPlayer.pause()
    }

    fun resume() {
        audioPlayer.resume()
    }

    fun togglePlayPause() {
        when (audioPlayer.state.value.playbackStatus) {
            PlaybackStatus.PLAYING -> pause()
            PlaybackStatus.PAUSED -> resume()
            PlaybackStatus.STOPPED, PlaybackStatus.IDLE -> {
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
        preferencesManager.volume = volume
    }

    fun nextTrack() {
        val entries = _selectedFolderEntries.value
        if (entries.isEmpty()) return

        val settings = _settingsState.value
        val currentPath = audioPlayer.state.value.currentTrackPath
        val currentIdx = entries.indexOfFirst { it.file.absolutePath == currentPath }

        if (settings.repeatMode == RepeatMode.ONE) {
            if (currentIdx >= 0) {
                val entry = entries[currentIdx]
                audioPlayer.play(entry.file.absolutePath)
                loadNowPlayingExtras(entry.file.absolutePath)
                savePlayingTrack(entry.file.absolutePath)
            }
            return
        }

        val nextIdx = when {
            settings.shuffleEnabled -> {
                if (entries.size <= 1) 0
                else {
                    var rand: Int
                    do {
                        rand = entries.indices.random()
                    } while (rand == currentIdx)
                    rand
                }
            }

            currentIdx < 0 -> 0
            currentIdx + 1 >= entries.size -> {
                if (settings.repeatMode == RepeatMode.ALL) 0
                else {
                    stop()
                    return
                }
            }

            else -> currentIdx + 1
        }

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

    fun setRepeatMode(mode: RepeatMode) {
        _settingsState.update { it.copy(repeatMode = mode) }
        preferencesManager.repeatMode = mode
    }

    fun toggleShuffle() {
        _settingsState.update { it.copy(shuffleEnabled = !it.shuffleEnabled) }
        preferencesManager.shuffleEnabled = _settingsState.value.shuffleEnabled
    }

    fun setResumeMode(mode: ResumeMode) {
        _settingsState.update { it.copy(resumeMode = mode) }
        preferencesManager.resumeMode = mode
    }

    fun setAudioDevice(deviceName: String) {
        val wasPlaying = audioPlayer.state.value.playbackStatus == PlaybackStatus.PLAYING
        val currentPath = audioPlayer.state.value.currentTrackPath
        val currentPosition = audioPlayer.state.value.positionMs

        if (wasPlaying) audioPlayer.pause()

        audioPlayer.setAudioDevice(deviceName)
        _settingsState.update { it.copy(selectedAudioDevice = deviceName) }
        preferencesManager.audioDevice = deviceName

        if (wasPlaying && currentPath != null) {
            audioPlayer.play(currentPath, currentPosition)
        }
    }

    fun clearLibrary() {
        viewModelScope.launch {
            stop()
            trackRepository.deleteAll()
            _tracks.value = emptyList()
            _selectedFolderEntries.value = persistentListOf()
            _settingsState.update { it.copy(trackCount = 0) }
        }
    }

    fun resetPreferences() {
        preferencesManager.clear()
        audioPlayer.setVolume(0.8)
        _settingsState.update {
            it.copy(
                repeatMode = RepeatMode.OFF,
                shuffleEnabled = false,
                selectedAudioDevice = "auto",
                resumeMode = ResumeMode.PAUSED
            )
        }
        audioPlayer.setAudioDevice("auto")
        _columnConfig.value = DEFAULT_COLUMN_CONFIG
    }

    fun toggleColumnVisibility(column: TrackListColumn) {
        val current = _columnConfig.value.visibleColumns
        if (column in current) {
            if (current.size <= 1) return
            _columnConfig.value = ColumnConfig(current.remove(column))
        } else {
            val newList = (current.add(column)).sortedBy { it.ordinal }.toPersistentList()
            _columnConfig.value = ColumnConfig(newList)
        }
        saveColumnConfig()
    }

    private fun saveColumnConfig() {
        preferencesManager.trackListColumns = _columnConfig.value.visibleColumns
    }

    private fun restoreColumnConfig() {
        val columns = preferencesManager.trackListColumns
        if (columns.isNotEmpty()) {
            _columnConfig.value = ColumnConfig(columns.toPersistentList())
        }
    }

    private fun savePlayingTrack(filePath: String) {
        preferencesManager.playingTrack = filePath
    }

    private fun clearPlayingTrack() {
        preferencesManager.playingTrack = ""
        preferencesManager.playbackPositionMs = 0L
        preferencesManager.playbackDurationMs = 0L
        preferencesManager.playbackWasPlaying = false
    }

    private fun restorePlayingTrack() {
        val path = preferencesManager.playingTrack.takeIf { it.isNotEmpty() }
            ?: return
        if (!File(path).exists()) return

        _selectedTrackPath.value = path

        val entries = _selectedFolderEntries.value
        currentTrackIndex = entries.indexOfFirst { it.file.absolutePath == path }

        loadNowPlayingExtras(path)

        val savedPosition = preferencesManager.playbackPositionMs
        val savedDuration = preferencesManager.playbackDurationMs
        val resumeMode = _settingsState.value.resumeMode
        val wasPlaying = preferencesManager.playbackWasPlaying

        if (resumeMode == ResumeMode.PLAYING && wasPlaying) {
            audioPlayer.play(path, savedPosition, savedDuration)
        } else if (savedPosition > 0L) {
            audioPlayer.playPaused(path, savedPosition, savedDuration)
        } else {
            audioPlayer.setCurrentTrackPath(path)
        }
    }

    private fun saveFolderTreeState() {
        val state = _folderTreeState.value
        preferencesManager.folderTreeRootPath = state.rootPath ?: ""
        preferencesManager.folderTreeExpandedPaths = state.expandedPaths
        preferencesManager.folderTreeSelectedPath = state.selectedPath ?: ""
    }

    private fun restoreFolderTreeState() {
        val rootPath = preferencesManager.folderTreeRootPath.takeIf { it.isNotEmpty() }
            ?: return
        if (!File(rootPath).isDirectory) return

        val expandedPaths = preferencesManager.folderTreeExpandedPaths
            .filter { File(it).isDirectory }
            .toSet()
        val selectedPath = preferencesManager.folderTreeSelectedPath.takeIf { it.isNotEmpty() }

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

    private fun restoreSettings() {
        val repeatMode = preferencesManager.repeatMode
        val shuffle = preferencesManager.shuffleEnabled
        val device = preferencesManager.audioDevice
        val resumeMode = preferencesManager.resumeMode

        _settingsState.update {
            it.copy(
                repeatMode = repeatMode,
                shuffleEnabled = shuffle,
                selectedAudioDevice = device,
                resumeMode = resumeMode
            )
        }

        if (device != "auto") {
            audioPlayer.setAudioDevice(device)
        }
    }

    private fun loadAudioDevices() {
        viewModelScope.launch {
            val devices = audioPlayer.getAvailableAudioDevices()
            _settingsState.update { it.copy(availableAudioDevices = devices) }
            updateSettingsLibraryInfo()
        }
    }

    private fun updateSettingsLibraryInfo() {
        _settingsState.update {
            it.copy(
                libraryPath = _folderTreeState.value.rootPath,
                trackCount = _tracks.value.size
            )
        }
    }

    private fun loadNowPlayingExtras(filePath: String) {
        viewModelScope.launch {
            _currentAlbumArt.value = metadataReader.readAlbumArt(filePath)
            _currentLyrics.value = metadataReader.readLyrics(filePath)
        }
    }

    private suspend fun loadSelectedFolderEntries(path: String) {
        val entries = listDirectoryEntries(path).filter { !it.isDirectory }.toImmutableList()
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
        updateSettingsLibraryInfo()
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
                val newSelected =
                    if (current.selectedPath?.startsWith(deletedPath) == true) null else current.selectedPath
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
            updateSettingsLibraryInfo()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Cleanup is handled by main.kt onCloseRequest
    }

    private data class PrimaryState(
        val folderTreeState: FolderTreeState,
        val selectedFolderEntries: ImmutableList<FileEntry>,
        val selectedTrackPath: String?,
        val scanProgress: ScanProgress,
        val columnConfig: ColumnConfig
    )

    private data class PlaybackBundle(
        val playbackState: PlaybackState,
        val tracks: List<MediaTrackData>,
        val albumArt: ImageBitmap?,
        val lyrics: String?
    )

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "wav", "aac", "opus", "m4a")
    }
}
