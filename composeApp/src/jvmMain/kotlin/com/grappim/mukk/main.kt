package com.grappim.mukk

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.grappim.mukk.data.DatabaseInit
import com.grappim.mukk.data.PreferencesManager
import com.grappim.mukk.player.AudioPlayer
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

fun main() {
    DatabaseInit.init()
    PreferencesManager.load()

    val audioPlayer = AudioPlayer()
    audioPlayer.init()

    val viewModel = MukkViewModel(audioPlayer)

    val savedWidth = PreferencesManager.getInt("window.width", 1024)
    val savedHeight = PreferencesManager.getInt("window.height", 700)

    application {
        val windowState = rememberWindowState(width = savedWidth.dp, height = savedHeight.dp)

        LaunchedEffect(windowState) {
            snapshotFlow { windowState.size }
                .debounce(500)
                .onEach { size ->
                    PreferencesManager.set("window.width", size.width.value.toInt())
                    PreferencesManager.set("window.height", size.height.value.toInt())
                }
                .launchIn(this)
        }

        Window(
            onCloseRequest = {
                PreferencesManager.set("window.width", windowState.size.width.value.toInt())
                PreferencesManager.set("window.height", windowState.size.height.value.toInt())
                audioPlayer.dispose()
                exitApplication()
            },
            title = "Mukk",
            state = windowState
        ) {
            App(viewModel)
        }
    }
}
