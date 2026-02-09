package com.grappim.mukk.data

import java.io.File
import java.util.Properties

object PreferencesManager {

    private val prefsFile = File(
        System.getProperty("user.home"),
        ".local/share/mukk/preferences.properties"
    )

    private val properties = Properties()

    fun load() {
        if (prefsFile.exists()) {
            prefsFile.inputStream().use { properties.load(it) }
        }
    }

    fun getString(key: String, default: String): String =
        properties.getProperty(key, default)

    fun getDouble(key: String, default: Double): Double =
        properties.getProperty(key)?.toDoubleOrNull() ?: default

    fun getInt(key: String, default: Int): Int =
        properties.getProperty(key)?.toIntOrNull() ?: default

    fun set(key: String, value: Any) {
        properties.setProperty(key, value.toString())
        save()
    }

    private fun save() {
        prefsFile.parentFile.mkdirs()
        prefsFile.outputStream().use { properties.store(it, null) }
    }
}
