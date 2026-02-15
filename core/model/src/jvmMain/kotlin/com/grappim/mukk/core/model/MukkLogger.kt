package com.grappim.mukk.core.model

import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object MukkLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val logFile: File

    init {
        val dataDir = File(System.getProperty("user.home"), ".local/share/mukk")
        dataDir.mkdirs()
        logFile = File(dataDir, "mukk.log")
        logFile.appendText("--- Mukk started at ${LocalDateTime.now().format(formatter)} ---\n")
    }

    fun debug(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(Level.INFO, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) = log(Level.WARN, tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) = log(Level.ERROR, tag, message, throwable)

    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = LocalDateTime.now().format(formatter)
        val line = "[$timestamp] [${level.name}] [$tag] $message"

        val stream = if (level >= Level.WARN) System.err else System.out
        stream.println(line)
        throwable?.printStackTrace(stream)

        try {
            FileOutputStream(logFile, true).buffered().use { out ->
                val ps = PrintStream(out)
                ps.println(line)
                throwable?.printStackTrace(ps)
            }
        } catch (_: Exception) {
            // Can't write to log file — don't crash the app
        }
    }
}