package com.grappim.mukk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.grappim.mukk.ui.MainLayout
import com.grappim.mukk.ui.MukkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()

    val currentTrack = tracks.firstOrNull { it.filePath == playbackState.currentTrackPath }

    MukkTheme {
        MainLayout(
            folderTreeState = folderTreeState,
            selectedFolderEntries = selectedFolderEntries,
            playbackState = playbackState,
            currentTrack = currentTrack,
            onToggleExpand = { path -> viewModel.toggleFolderExpanded(path) },
            onSelectFolder = { path -> viewModel.selectFolder(path) },
            onOpenFolderClick = {
                scope.launch(Dispatchers.IO) {
                    val path = pickDirectoryNative()
                    if (path != null) {
                        viewModel.scanDirectory(path)
                    }
                }
            },
            onTrackClick = { entry -> viewModel.playFile(entry) },
            getSubfolders = { path -> viewModel.getSubfolders(path) },
            onPlayPause = { viewModel.togglePlayPause() },
            onStop = { viewModel.stop() },
            onPrevious = { viewModel.previousTrack() },
            onNext = { viewModel.nextTrack() },
            onSeek = { positionMs -> viewModel.seekTo(positionMs) },
            onVolumeChange = { volume -> viewModel.setVolume(volume) }
        )
    }
}
