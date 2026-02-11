package com.grappim.mukk.di

import com.grappim.mukk.MukkViewModel
import com.grappim.mukk.data.DatabaseInit
import com.grappim.mukk.data.PreferencesManager
import com.grappim.mukk.player.AudioPlayer
import com.grappim.mukk.scanner.FileScanner
import com.grappim.mukk.scanner.MetadataReader
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { DatabaseInit() }
    single { PreferencesManager() }
    single { MetadataReader() }
    single { FileScanner(databaseInit = get(), metadataReader = get()) }
    single { AudioPlayer().also { it.init() } }
    viewModel { MukkViewModel(
        audioPlayer = get(),
        databaseInit = get(),
        preferencesManager = get(),
        fileScanner = get(),
        metadataReader = get()
    ) }
}
