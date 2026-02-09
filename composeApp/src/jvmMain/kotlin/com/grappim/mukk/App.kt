package com.grappim.mukk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.grappim.mukk.ui.MainLayout
import com.grappim.mukk.ui.MukkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser

private fun pickDirectoryNative(): String? {
    // Try zenity (GNOME/GTK) first, then kdialog (KDE), fall back to Swing.
    // Only fall through if the command is not found (exception), not if the user cancelled.
    try {
        val proc = ProcessBuilder("zenity", "--file-selection", "--directory", "--title=Select Music Folder")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        val exitCode = proc.waitFor()
        return if (exitCode == 0 && output.isNotEmpty()) output else null
    } catch (_: Exception) { /* zenity not available, try next */ }

    try {
        val proc = ProcessBuilder("kdialog", "--getexistingdirectory", System.getProperty("user.home"))
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        val exitCode = proc.waitFor()
        return if (exitCode == 0 && output.isNotEmpty()) output else null
    } catch (_: Exception) { /* kdialog not available, try next */ }

    // Fallback to Swing
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Select Music Folder"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else null
}

@Composable
fun App(viewModel: MukkViewModel) {
    val tracks by viewModel.tracks.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val folderTreeState by viewModel.folderTreeState.collectAsState()
    val selectedFolderEntries by viewModel.selectedFolderEntries.collectAsState()
    val selectedTrackPath by viewModel.selectedTrackPath.collectAsState()
    val albumArt by viewModel.currentAlbumArt.collectAsState()
    val lyrics by viewModel.currentLyrics.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scope = rememberCoroutineScope()

    val currentTrack = tracks.firstOrNull { it.filePath == playbackState.currentTrackPath }
    val playingFolderPath = playbackState.currentTrackPath?.let { File(it).parent }

    MukkTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Spacebar && event.type == KeyEventType.KeyDown) {
                        viewModel.togglePlayPause()
                        true
                    } else {
                        false
                    }
                }
        ) {
            MainLayout(
                folderTreeState = folderTreeState,
                selectedFolderEntries = selectedFolderEntries,
                playbackState = playbackState,
                currentTrack = currentTrack,
                selectedTrackPath = selectedTrackPath,
                playingFolderPath = playingFolderPath,
                isScanning = isScanning,
                onToggleExpand = { path -> viewModel.toggleFolderExpanded(path) },
                onSelectFolder = { path -> viewModel.selectFolder(path) },
                onRescanClick = { viewModel.rescan() },
                onOpenFolderClick = {
                    scope.launch(Dispatchers.IO) {
                        val path = pickDirectoryNative()
                        if (path != null) {
                            viewModel.scanDirectory(path)
                        }
                    }
                },
                onTrackClick = { entry -> viewModel.selectTrack(entry.file.absolutePath) },
                onTrackDoubleClick = { entry -> viewModel.playFile(entry) },
                getSubfolders = { path -> viewModel.getSubfolders(path) },
                onPlayPause = { viewModel.togglePlayPause() },
                onStop = { viewModel.stop() },
                onPrevious = { viewModel.previousTrack() },
                onNext = { viewModel.nextTrack() },
                onSeek = { positionMs -> viewModel.seekTo(positionMs) },
                albumArt = albumArt,
                lyrics = lyrics,
                onVolumeChange = { volume -> viewModel.setVolume(volume) }
            )
        }
    }
}
