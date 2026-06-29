package com.grappim.mukk.core.model

import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object MukkLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val fileStream: PrintStream?

    init {
        val dataDir = File(System.getProperty("user.home"), ".local/share/mukk")
        dataDir.mkdirs()
        val logFile = File(dataDir, "mukk.log")
        if (logFile.length() > 5 * 1024 * 1024) logFile.delete()
        fileStream = try {
            PrintStream(FileOutputStream(logFile, true), true)
        } catch (_: Exception) {
            null
        }
        fileStream?.println("--- Mukk started at ${LocalDateTime.now().format(formatter)} ---")
    }

    fun debug(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(Level.INFO, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) = log(Level.WARN, tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) = log(Level.ERROR, tag, message, throwable)

    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = LocalDateTime.now().format(formatter)
        val line = "[$timestamp] [${level.name}] [$tag] $message"

        val consoleStream = if (level >= Level.WARN) System.err else System.out
        consoleStream.println(line)
        throwable?.printStackTrace(consoleStream)

        // Only persist WARN+ to file — DEBUG/INFO on console only to avoid log bloat
        if (level >= Level.WARN) {
            fileStream?.println(line)
            throwable?.printStackTrace(fileStream)
        }
    }
}
