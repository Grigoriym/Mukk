package com.grappim.mukk.scanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchService: WatchService? = null
    private var watchJob: Job? = null
    private val keyToPath = mutableMapOf<WatchKey, Path>()

    fun watch(rootDirectory: File) {
        stop()

        val root = rootDirectory.toPath()
        if (!Files.isDirectory(root)) return

        try {
            val ws = FileSystems.getDefault().newWatchService()
            watchService = ws

            registerRecursive(root, ws)

            watchJob = scope.launch {
                while (isActive) {
                    val key = ws.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    val dir = keyToPath[key] ?: run {
                        key.cancel()
                        continue
                    }

                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        if (kind == java.nio.file.StandardWatchEventKinds.OVERFLOW) continue

                        @Suppress("UNCHECKED_CAST")
                        val child = dir.resolve((event as java.nio.file.WatchEvent<Path>).context())

                        when (kind) {
                            ENTRY_CREATE -> {
                                if (Files.isDirectory(child)) {
                                    try {
                                        registerRecursive(child, ws)
                                    } catch (e: IOException) {
                                        // inotify limit reached — on-select scan still works
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

                            ENTRY_MODIFY -> {
                                if (isAudioFile(child)) {
                                    _events.tryEmit(
                                        FileSystemEvent.AudioFileChanged(
                                            directory = dir.toString(),
                                            filePath = child.toString()
                                        )
                                    )
                                }
                            }

                            ENTRY_DELETE -> {
                                val extension = child.fileName.toString()
                                    .substringAfterLast('.', "")
                                    .lowercase()
                                if (extension in FileScanner.AUDIO_EXTENSIONS) {
                                    _events.tryEmit(
                                        FileSystemEvent.AudioFileDeleted(
                                            directory = dir.toString(),
                                            filePath = child.toString()
                                        )
                                    )
                                } else {
                                    _events.tryEmit(
                                        FileSystemEvent.DirectoryDeleted(child.toString())
                                    )
                                }
                            }
                        }
                    }

                    val valid = key.reset()
                    if (!valid) {
                        keyToPath.remove(key)
                    }
                }
            }
        } catch (e: IOException) {
            // WatchService creation failed — on-select scan still works as fallback
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        keyToPath.clear()
        try {
            watchService?.close()
        } catch (_: IOException) {
        }
        watchService = null
    }

    private fun registerRecursive(root: Path, ws: WatchService) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    val key = dir.register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                    keyToPath[key] = dir
                } catch (e: IOException) {
                    // inotify limit — skip this directory
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
}
