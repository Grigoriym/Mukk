package com.grappim.mukk

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.grappim.mukk.data.DatabaseInit
import com.grappim.mukk.player.AudioPlayer

fun main() {
    DatabaseInit.init()

    val audioPlayer = AudioPlayer()
    audioPlayer.init()

    val viewModel = MukkViewModel(audioPlayer)

    application {
        Window(
            onCloseRequest = {
                audioPlayer.dispose()
                exitApplication()
            },
            title = "Mukk",
            state = rememberWindowState(width = 1024.dp, height = 700.dp)
        ) {
            App(viewModel)
        }
    }
}
