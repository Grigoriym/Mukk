package com.grappim.mukk.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Properties

class PreferencesManager {

    private val prefsFile = File(
        System.getProperty("user.home"),
        ".local/share/mukk/preferences.properties"
    )

    private val properties = Properties()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeMutex = Mutex()

    init {
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

    fun getBoolean(key: String, default: Boolean): Boolean =
        properties.getProperty(key)?.toBooleanStrictOrNull() ?: default

    fun clear() {
        properties.clear()
        scope.launch {
            writeMutex.withLock {
                save()
            }
        }
    }

    fun set(key: String, value: Any) {
        properties.setProperty(key, value.toString())
        scope.launch {
            writeMutex.withLock {
                save()
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    private fun save() {
        prefsFile.parentFile.mkdirs()
        prefsFile.outputStream().use { properties.store(it, null) }
    }
}
