@file:OptIn(FlowPreview::class)

package com.grappim.mukk

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.grappim.mukk.data.PreferencesManager
import com.grappim.mukk.di.appModule
import com.grappim.mukk.player.AudioPlayer
import com.grappim.mukk.scanner.FileSystemWatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

fun main() {
    val koinApp = startKoin {
        modules(appModule)
    }
    MukkLogger.info("Main", "Mukk starting")
    val koin = koinApp.koin
    val preferencesManager = koin.get<PreferencesManager>()
    val audioPlayer = koin.get<AudioPlayer>()
    val fileSystemWatcher = koin.get<FileSystemWatcher>()

    val savedWidth = preferencesManager.getInt("window.width", 1024)
    val savedHeight = preferencesManager.getInt("window.height", 700)

    application {
        val windowState = rememberWindowState(width = savedWidth.dp, height = savedHeight.dp)

        LaunchedEffect(windowState) {
            snapshotFlow { windowState.size }
                .debounce(500)
                .onEach { size ->
                    preferencesManager.set("window.width", size.width.value.toInt())
                    preferencesManager.set("window.height", size.height.value.toInt())
                }
                .launchIn(this)
        }

        val appIcon = BitmapPainter(
            Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.readAllBytes()
                .decodeToImageBitmap()
        )

        Window(
            icon = appIcon,
            onCloseRequest = {
                preferencesManager.set("window.width", windowState.size.width.value.toInt())
                preferencesManager.set("window.height", windowState.size.height.value.toInt())
                fileSystemWatcher.stop()
                audioPlayer.dispose()
                preferencesManager.dispose()
                stopKoin()
                exitApplication()
            },
            title = "Mukk",
            state = windowState
        ) {
            App()
        }
    }
}
