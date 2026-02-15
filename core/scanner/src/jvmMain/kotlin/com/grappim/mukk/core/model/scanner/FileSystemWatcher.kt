package com.grappim.mukk.core.model.scanner

import com.grappim.mukk.core.model.MukkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class FileSystemEvent {
    data class AudioFileChanged(val directory: String, val filePath: String) : FileSystemEvent()
    data class AudioFileDeleted(val directory: String, val filePath: String) : FileSystemEvent()
    data class DirectoryCreated(val directoryPath: String) : FileSystemEvent()
    data class DirectoryDeleted(val directoryPath: String) : FileSystemEvent()
}

class FileSystemWatcher {

    private val _events = MutableSharedFlow<FileSystemEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<FileSystemEvent> = _events.asSharedFlow()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchService: WatchService? = null
    private var watchJob: Job? = null
    private val keyToPath = ConcurrentHashMap<WatchKey, Path>()

    fun watch(rootDirectory: File) {
        stop()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val root = rootDirectory.toPath()
        if (!Files.isDirectory(root)) return

        try {
            val ws = FileSystems.getDefault().newWatchService()
            watchService = ws

            registerRecursive(root, ws)
            MukkLogger.debug("FileSystemWatcher", "Started watching: $root")

            watchJob = scope.launch {
                while (isActive) {
                    val key = try {
                        ws.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    } catch (_: ClosedWatchServiceException) {
                        break
                    }

                    val dir = keyToPath[key]
                    if (dir == null) {
                        key.cancel()
                        continue
                    }

                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue

                        @Suppress("UNCHECKED_CAST")
                        val child = dir.resolve((event as WatchEvent<Path>).context())

                        when (kind) {
                            ENTRY_CREATE -> handleCreate(child, dir, ws)
                            ENTRY_MODIFY -> handleModify(child, dir)
                            ENTRY_DELETE -> handleDelete(child, dir)
                        }
                    }

                    val valid = key.reset()
                    if (!valid) {
                        keyToPath.remove(key)
                    }
                }
            }
        } catch (e: IOException) {
            MukkLogger.warn("FileSystemWatcher", "WatchService creation failed, falling back to on-select scan", e)
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        scope.cancel()
        keyToPath.clear()
        try {
            watchService?.close()
        } catch (e: IOException) {
            MukkLogger.debug("FileSystemWatcher", "Error closing WatchService: ${e.message}")
        }
        watchService = null
        MukkLogger.debug("FileSystemWatcher", "Stopped")
    }

    private fun handleCreate(child: Path, dir: Path, ws: WatchService) {
        if (Files.isDirectory(child)) {
            try {
                registerRecursive(child, ws)
            } catch (e: IOException) {
                MukkLogger.warn("FileSystemWatcher", "inotify limit, skipping watch for $child", e)
            }
            _events.tryEmit(FileSystemEvent.DirectoryCreated(child.toString()))
        } else if (isAudioFile(child)) {
            _events.tryEmit(
                FileSystemEvent.AudioFileChanged(
                    directory = dir.toString(),
                    filePath = child.toString()
                )
            )
        }
    }

    private fun handleModify(child: Path, dir: Path) {
        if (isAudioFile(child)) {
            _events.tryEmit(
                FileSystemEvent.AudioFileChanged(
                    directory = dir.toString(),
                    filePath = child.toString()
                )
            )
        }
    }

    private fun handleDelete(child: Path, dir: Path) {
        // Deleted files no longer exist on disk, so we can't check isDirectory().
        // Use the extension heuristic: if it looks like an audio file, emit AudioFileDeleted.
        // If it has a known extension of any kind, it's a non-audio file — ignore it.
        // Otherwise (no extension or unknown), assume it was a directory.
        if (isAudioFile(child)) {
            _events.tryEmit(
                FileSystemEvent.AudioFileDeleted(
                    directory = dir.toString(),
                    filePath = child.toString()
                )
            )
        } else if (!hasFileExtension(child)) {
            _events.tryEmit(FileSystemEvent.DirectoryDeleted(child.toString()))
        }
    }

    private fun registerRecursive(root: Path, ws: WatchService) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    val key = dir.register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                    keyToPath[key] = dir
                } catch (e: IOException) {
                    MukkLogger.warn("FileSystemWatcher", "inotify limit, skipping watch for $dir", e)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun isAudioFile(path: Path): Boolean {
        val extension = path.fileName.toString()
            .substringAfterLast('.', "")
            .lowercase()
        return extension in FileScanner.AUDIO_EXTENSIONS
    }

    private fun hasFileExtension(path: Path): Boolean {
        val name = path.fileName.toString()
        val dotIndex = name.lastIndexOf('.')
        return dotIndex > 0 && dotIndex < name.length - 1
    }
}
