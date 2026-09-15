package com.grappim.mukk.core.data

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

class DatabaseInit(
    dbFile: File = File(File(System.getProperty("user.home"), ".local/share/mukk"), "library.db")
) {

    val database: Database

    init {
        dbFile.parentFile.mkdirs()

        database = Database.connect(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC"
        )

        transaction(database) {
            SchemaUtils.create(MediaTracks, WaveformCacheTable, PlaylistsTable)
        }
    }
}
