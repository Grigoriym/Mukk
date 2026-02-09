package com.grappim.mukk.data

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

object DatabaseInit {

    lateinit var database: Database
        private set

    fun init() {
        val dataDir = File(System.getProperty("user.home"), ".local/share/mukk")
        dataDir.mkdirs()
        val dbFile = File(dataDir, "library.db")

        database = Database.connect(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC"
        )

        transaction(database) {
            SchemaUtils.create(MediaTracks)
        }
    }
}
