package com.grappim.mukk

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grappim.mukk.core.data.PlaylistRepository
import com.grappim.mukk.core.data.PreferencesManager
import com.grappim.mukk.core.data.TrackRepository
import com.grappim.mukk.core.data.WaveformRepository
import com.grappim.mukk.core.model.*
import com.grappim.mukk.core.model.player.AudioPlayer
import com.grappim.mukk.core.model.player.WaveformExtractor
import com.grappim.mukk.core.model.scanner.FileScanner
import com.grappim.mukk.core.model.scanner.FileSystemEvent
import com.grappim.mukk.core.model.scanner.FileSystemWatcher
import com.grappim.mukk.core.model.scanner.MetadataReader
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class MukkViewModel(
    private val audioPlayer: AudioPlayer,
    private val trackRepository: TrackRepository,
    private val preferencesManager: PreferencesManager,
    private val fileScanner: FileScanner,
    private val metadataReader: MetadataReader,
    private val fileSystemWatcher: FileSystemWatcher,
    private val waveformExtractor: WaveformExtractor,
    private val waveformRepository: WaveformRepository,
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    private val _tracks = MutableStateFlow<List<MediaTrackData>>(emptyList())
    private val _folderTreeState = MutableStateFlow(FolderTreeState())
    private val _selectedFolderEntries = MutableStateFlow<ImmutableList<FileEntry>>(persistentListOf())
    private val _selectedTrackPath = MutableStateFlow<String?>(null)
    private val _currentAlbumArt = MutableStateFlow<ImageBitmap?>(null)
    private val _currentLyrics = MutableStateFlow<String?>(null)
    private val _waveformPeaks = MutableStateFlow<FloatArray?>(null)
    private val _scanProgress = MutableStateFlow(ScanProgress())
    private val _columnConfig = MutableStateFlow(DEFAULT_COLUMN_CONFIG)
    private val _settingsState = MutableStateFlow(SettingsState())
    private val _playlists = MutableStateFlow<ImmutableList<Playlist>>(persistentListOf())
    private val _activePlaylistId = MutableStateFlow<Long?>(null)

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
        combine(audioPlayer.state, _tracks, _currentAlbumArt, _currentLyrics, _waveformPeaks) { ps, tracks, art, lyrics, peaks ->
            PlaybackBundle(ps, tracks, art, lyrics, peaks)
        },
        _settingsState,
        combine(_playlists, _activePlaylistId) { playlists, activeId ->
            PlaylistBundle(playlists, activeId)
        }
    ) { primary, playback, settings, playlistBundle ->
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
            waveformPeaks = playback.waveformPeaks,
            settingsState = settings,
            playlists = playlistBundle.playlists,
            activePlaylistId = playlistBundle.activePlaylistId
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MukkUiState())

    private var currentTrackIndex: Int = -1
    private var watcherCollectionJob: Job? = null
    private var waveformJob: Job? = null
    private var activatePlaylistJob: Job? = null
    private val pendingChangedDirs = mutableMapOf<String, Job>()
    private val pendingDeletedPaths = mutableSetOf<String>()
    private var pendingDeleteJob: Job? = null

    init {
        audioPlayer.onTrackFinished = { nextTrack() }

        val savedVolume = preferencesManager.volume
        audioPlayer.setVolume(savedVolume)

        loadTracks()
        restoreActivePlaylist()
        restoreColumnConfig()
        restoreSettings()
        restorePlayingTrack()
        loadAudioDevices()
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
            val scanned = fileScanner.scan(File(path))
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
        waveformJob?.cancel()
        waveformJob = null
        _waveformPeaks.value = null
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

    fun saveColumnWidth(column: TrackListColumn, widthDp: Int) {
        val current = _columnConfig.value
        _columnConfig.value = current.copy(columnWidths = current.columnWidths + (column to widthDp))
        preferencesManager.trackListColumnWidths = _columnConfig.value.columnWidths
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
        preferencesManager.trackListColumnWidths = _columnConfig.value.columnWidths
    }

    private fun restoreColumnConfig() {
        val columns = preferencesManager.trackListColumns
        val widths = preferencesManager.trackListColumnWidths
        val visibleColumns = if (columns.isNotEmpty()) columns.toPersistentList() else DEFAULT_COLUMN_CONFIG.visibleColumns
        _columnConfig.value = ColumnConfig(visibleColumns = visibleColumns, columnWidths = widths)
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
            audioPlayer.setRestoredTrackPath(path)
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

    private fun restoreActivePlaylist() {
        viewModelScope.launch {
            val playlists = playlistRepository.getAll().toImmutableList()
            _playlists.value = playlists

            val savedId = preferencesManager.playlistActiveId.takeIf { it != 0L }
            val active = playlists.firstOrNull { it.id == savedId }
            if (active == null) {
                restoreFolderTreeState()
                return@launch
            }

            val expandedPaths = preferencesManager.folderTreeExpandedPaths
                .filter { File(it).isDirectory }
                .toSet()
                .ifEmpty { setOf(active.folderPath) }
            val browsePath = preferencesManager.folderTreeSelectedPath
                .takeIf { it.isNotEmpty() && File(it).isDirectory }
                ?: active.folderPath

            activatePlaylist(active, browsePath = browsePath, expandedPaths = expandedPaths)
        }
    }

    fun selectPlaylist(id: Long) {
        val playlist = _playlists.value.firstOrNull { it.id == id } ?: return
        activatePlaylist(playlist, browsePath = playlist.folderPath, expandedPaths = setOf(playlist.folderPath))
    }

    fun createPlaylist(folderPath: String) {
        viewModelScope.launch {
            val playlist = playlistRepository.create(File(folderPath).name, folderPath)
            _playlists.value = playlistRepository.getAll().toImmutableList()
            activatePlaylist(playlist, browsePath = playlist.folderPath, expandedPaths = setOf(playlist.folderPath))
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch {
            playlistRepository.rename(id, name)
            _playlists.value = playlistRepository.getAll().toImmutableList()
        }
    }

    fun reorderPlaylists(idsInNewOrder: List<Long>) {
        viewModelScope.launch {
            playlistRepository.reorder(idsInNewOrder)
            _playlists.value = playlistRepository.getAll().toImmutableList()
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            val playlists = _playlists.value
            val index = playlists.indexOfFirst { it.id == id }
            if (index < 0) return@launch
            val deleted = playlists[index]

            val currentPath = audioPlayer.state.value.currentTrackPath
            if (currentPath != null && isPathUnderFolder(currentPath, deleted.folderPath)) {
                stop()
            }

            playlistRepository.delete(id)
            val remaining = playlistRepository.getAll().toImmutableList()
            _playlists.value = remaining

            if (_activePlaylistId.value != id) return@launch

            if (remaining.isEmpty()) {
                _activePlaylistId.value = null
                preferencesManager.playlistActiveId = 0L
                _folderTreeState.value = FolderTreeState()
                _selectedFolderEntries.value = persistentListOf()
                watcherCollectionJob?.cancel()
                fileSystemWatcher.stop()
                saveFolderTreeState()
            } else {
                val neighbor = remaining[index.coerceAtMost(remaining.size - 1)]
                activatePlaylist(neighbor, browsePath = neighbor.folderPath, expandedPaths = setOf(neighbor.folderPath))
            }
        }
    }

    private fun activatePlaylist(playlist: Playlist, browsePath: String, expandedPaths: Set<String>) {
        _activePlaylistId.value = playlist.id
        preferencesManager.playlistActiveId = playlist.id
        _folderTreeState.value = FolderTreeState(
            rootPath = playlist.folderPath,
            expandedPaths = expandedPaths,
            selectedPath = browsePath
        )
        _selectedTrackPath.value = null
        saveFolderTreeState()

        val previousJob = activatePlaylistJob
        activatePlaylistJob = viewModelScope.launch {
            // Wait for the previous switch to fully stop, not just request cancellation, so its
            // non-interruptible bulk DB fetch (findByPathPrefix) never overlaps with this one's.
            previousJob?.cancelAndJoin()
            _selectedFolderEntries.value = buildCachedEntries(trackRepository.findByPathPrefix(browsePath))

            _scanProgress.value = ScanProgress(isScanning = true)
            try {
                fileScanner.scan(File(playlist.folderPath)) { scanned, total ->
                    _scanProgress.value = ScanProgress(true, scanned, total)
                }
                loadTracksSync()
                _selectedFolderEntries.value = buildCachedEntries(trackRepository.findByPathPrefix(browsePath))
            } finally {
                _scanProgress.value = ScanProgress()
            }
            startWatching(playlist.folderPath)
        }
    }

    private fun buildCachedEntries(tracks: List<MediaTrackData>): ImmutableList<FileEntry> =
        tracks
            .map { track ->
                FileEntry(file = File(track.filePath), isDirectory = false, name = track.title, trackData = track)
            }
            .sortedWith(
                compareBy<FileEntry> { it.file.parent }
                    .thenBy { it.trackData?.trackNumber ?: Int.MAX_VALUE }
                    .thenBy { it.name.lowercase() }
            )
            .toImmutableList()

    private fun isPathUnderFolder(filePath: String, folderPath: String): Boolean =
        filePath.startsWith(folderPath.trimEnd('/') + "/")

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
            val extras = metadataReader.readNowPlayingExtras(File(filePath))
            _currentAlbumArt.value = extras.albumArt
            _currentLyrics.value = extras.lyrics
        }
        loadWaveform(filePath)
    }

    private fun loadWaveform(filePath: String) {
        waveformJob?.cancel()
        _waveformPeaks.value = null
        waveformJob = viewModelScope.launch {
            try {
                val cached = waveformRepository.get(filePath)
                if (cached != null) {
                    _waveformPeaks.value = cached
                    return@launch
                }
                val peaks = waveformExtractor.extract(File(filePath))
                if (peaks != null) {
                    _waveformPeaks.value = peaks
                    waveformRepository.put(filePath, peaks)
                }
            } catch (e: Exception) {
                MukkLogger.warn("MukkViewModel", "Waveform load failed: $filePath", e)
            }
        }
    }

    private suspend fun loadSelectedFolderEntries(path: String) {
        val dir = File(path)
        if (!dir.isDirectory) {
            _selectedFolderEntries.value = persistentListOf()
            return
        }
        val audioFiles = dir.walkTopDown()
            .filter { it.isFile && FileScanner.isAudioFile(it) }
            .toList()
        val entries = audioFiles
            .map { file ->
                val trackData = lookupTrackData(file.absolutePath)
                FileEntry(
                    file = file,
                    isDirectory = false,
                    name = trackData?.title ?: file.name,
                    trackData = trackData
                )
            }
            .sortedWith(
                compareBy<FileEntry> { it.file.parent }
                    .thenBy { it.trackData?.trackNumber ?: Int.MAX_VALUE }
                    .thenBy { it.name.lowercase() }
            )
            .toImmutableList()
        _selectedFolderEntries.value = entries
    }

    private fun containsAudioFiles(dir: File): Boolean {
        return dir.walkTopDown().any { it.isFile && FileScanner.isAudioFile(it) }
    }

    private suspend fun listDirectoryEntries(path: String): List<FileEntry> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()

        val children = dir.listFiles() ?: return emptyList()

        return children
            .filter { it.isDirectory || FileScanner.isAudioFile(it) }
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
        pendingChangedDirs.values.forEach { it.cancel() }
        pendingChangedDirs.clear()
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeletedPaths.clear()
        fileSystemWatcher.watch(File(rootPath))
        watcherCollectionJob = viewModelScope.launch {
            fileSystemWatcher.events.collect { event ->
                handleFileSystemEvent(event)
            }
        }
    }

    private fun handleFileSystemEvent(event: FileSystemEvent) {
        when (event) {
            is FileSystemEvent.AudioFileChanged -> {
                // Debounce per directory: a burst of tag-update events (e.g. a tagger script
                // rewriting an entire album) collapses into a single scan after the burst ends.
                pendingChangedDirs[event.directory]?.cancel()
                pendingChangedDirs[event.directory] = viewModelScope.launch {
                    delay(1500L.milliseconds)
                    pendingChangedDirs.remove(event.directory)
                    fileScanner.scanFolder(File(event.directory))
                    loadTracksSync()
                    val selectedPath = _folderTreeState.value.selectedPath
                    if (selectedPath == event.directory) {
                        loadSelectedFolderEntries(selectedPath)
                    }
                }
            }

            is FileSystemEvent.AudioFileDeleted -> {
                // Batch deletes: deleting a folder fires one event per file inside it.
                // Accumulate paths and process once after the burst ends.
                pendingDeletedPaths.add(event.filePath)
                pendingDeleteJob?.cancel()
                pendingDeleteJob = viewModelScope.launch {
                    delay(500L.milliseconds)
                    val paths = pendingDeletedPaths.toSet()
                    pendingDeletedPaths.clear()
                    pendingDeleteJob = null
                    paths.forEach { fileScanner.removeTrack(it) }
                    loadTracksSync()
                    val selectedPath = _folderTreeState.value.selectedPath
                    if (selectedPath != null && paths.any { File(it).parent == selectedPath }) {
                        loadSelectedFolderEntries(selectedPath)
                    }
                    val currentPath = audioPlayer.state.value.currentTrackPath
                    if (currentPath in paths) stop()
                }
            }

            is FileSystemEvent.DirectoryCreated -> bumpFolderTreeVersion()

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
        val lyrics: String?,
        val waveformPeaks: FloatArray?
    )

    private data class PlaylistBundle(
        val playlists: ImmutableList<Playlist>,
        val activePlaylistId: Long?
    )

    companion object
}
