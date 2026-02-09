package com.grappim.mukk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
    val browserState by viewModel.libraryBrowserState.collectAsState()
    val nowPlayingEntries by viewModel.nowPlayingFolderEntries.collectAsState()
    val nowPlayingFolderName by viewModel.nowPlayingFolderName.collectAsState()
    val scope = rememberCoroutineScope()

    val currentTrack = tracks.firstOrNull { it.filePath == playbackState.currentTrackPath }

    MukkTheme {
        MainLayout(
            browserState = browserState,
            nowPlayingEntries = nowPlayingEntries,
            nowPlayingFolderName = nowPlayingFolderName,
            playbackState = playbackState,
            currentTrack = currentTrack,
            onLibraryClick = { viewModel.navigateToRoot() },
            onOpenFolderClick = {
                scope.launch(Dispatchers.IO) {
                    val path = pickDirectoryNative()
                    if (path != null) {
                        viewModel.scanDirectory(path)
                    }
                }
            },
            onNavigateToDirectory = { path -> viewModel.navigateToDirectory(path) },
            onNavigateUp = { viewModel.navigateUp() },
            onBreadcrumbClick = { segmentIndex ->
                // Reconstruct path from root + segments up to clicked index
                val root = browserState.rootPath ?: return@MainLayout
                if (segmentIndex == 0) {
                    viewModel.navigateToDirectory(root)
                } else {
                    val segments = browserState.pathSegments.drop(1).take(segmentIndex)
                    val path = segments.fold(root) { acc, seg -> acc + File.separator + seg }
                    viewModel.navigateToDirectory(path)
                }
            },
            onFileClick = { entry ->
                if (entry.isDirectory) {
                    viewModel.navigateToDirectory(entry.file.absolutePath)
                } else {
                    viewModel.playFile(entry)
                }
            },
            onNowPlayingFileClick = { entry -> viewModel.playFile(entry) },
            onPlayPause = { viewModel.togglePlayPause() },
            onStop = { viewModel.stop() },
            onPrevious = { viewModel.previousTrack() },
            onNext = { viewModel.nextTrack() },
            onSeek = { positionMs -> viewModel.seekTo(positionMs) },
            onVolumeChange = { volume -> viewModel.setVolume(volume) }
        )
    }
}
