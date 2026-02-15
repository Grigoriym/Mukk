package com.grappim.mukk.di

import com.grappim.mukk.MukkViewModel
import com.grappim.mukk.core.data.DatabaseInit
import com.grappim.mukk.core.data.PreferencesManager
import com.grappim.mukk.core.data.TrackRepository
import com.grappim.mukk.core.model.player.AudioPlayer
import com.grappim.mukk.core.model.scanner.FileScanner
import com.grappim.mukk.core.model.scanner.FileSystemWatcher
import com.grappim.mukk.core.model.scanner.MetadataReader
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { DatabaseInit() }
    single { PreferencesManager() }
    single { MetadataReader() }
    single { TrackRepository(databaseInit = get()) }
    single { FileScanner(trackRepository = get(), metadataReader = get()) }
    single { AudioPlayer().also { it.init() } }
    single { FileSystemWatcher() }
    viewModel { MukkViewModel(
        audioPlayer = get(),
        trackRepository = get(),
        preferencesManager = get(),
        fileScanner = get(),
        metadataReader = get(),
        fileSystemWatcher = get()
    ) }
}
