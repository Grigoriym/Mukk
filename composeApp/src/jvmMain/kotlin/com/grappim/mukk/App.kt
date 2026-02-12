package com.grappim.mukk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.grappim.mukk.data.PreferencesManager
import com.grappim.mukk.ui.MainLayout
import com.grappim.mukk.ui.MukkTheme
import com.grappim.mukk.ui.SettingsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
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
    } catch (e: Exception) { MukkLogger.debug("App", "zenity not available: ${e.message}") }

    try {
        val proc = ProcessBuilder("kdialog", "--getexistingdirectory", System.getProperty("user.home"))
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        val exitCode = proc.waitFor()
        return if (exitCode == 0 && output.isNotEmpty()) output else null
    } catch (e: Exception) { MukkLogger.debug("App", "kdialog not available: ${e.message}") }

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
fun App() {
    val viewModel = koinViewModel<MukkViewModel>()
    val preferencesManager = koinInject<PreferencesManager>()
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var showSettingsDialog by remember { mutableStateOf(false) }

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
                uiState = uiState,
                preferencesManager = preferencesManager,
                onToggleExpand = { path -> viewModel.toggleFolderExpanded(path) },
                onSelectFolder = { path -> viewModel.selectFolder(path) },
                onRescanClick = { viewModel.rescan() },
                onSettingsClick = { showSettingsDialog = true },
                onOpenFolderClick = {
                    scope.launch(Dispatchers.IO) {
                        val path = pickDirectoryNative()
                        if (path != null) {
                            viewModel.scanDirectory(path)
                        }
                    }
                },
                onToggleColumn = { viewModel.toggleColumnVisibility(it) },
                onTrackClick = { entry -> viewModel.selectTrack(entry.file.absolutePath) },
                onTrackDoubleClick = { entry -> viewModel.playFile(entry) },
                getSubfolders = { path -> viewModel.getSubfolders(path) },
                onPlayPause = { viewModel.togglePlayPause() },
                onStop = { viewModel.stop() },
                onPrevious = { viewModel.previousTrack() },
                onNext = { viewModel.nextTrack() },
                onSeek = { positionMs -> viewModel.seekTo(positionMs) },
                onVolumeChange = { volume -> viewModel.setVolume(volume) }
            )

            if (showSettingsDialog) {
                SettingsDialog(
                    settingsState = uiState.settingsState,
                    isScanning = uiState.scanProgress.isScanning,
                    onRepeatModeChange = { viewModel.setRepeatMode(it) },
                    onShuffleToggle = { viewModel.toggleShuffle() },
                    onResumeModeChange = { viewModel.setResumeMode(it) },
                    onAudioDeviceChange = { viewModel.setAudioDevice(it) },
                    onRescanAll = { viewModel.rescan() },
                    onClearLibrary = { viewModel.clearLibrary() },
                    onResetPreferences = { viewModel.resetPreferences() },
                    onDismiss = { showSettingsDialog = false }
                )
            }
        }
    }
}
