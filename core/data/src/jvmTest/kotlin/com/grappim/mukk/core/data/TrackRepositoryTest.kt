package com.grappim.mukk.core.data

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackRepositoryTest {

    private lateinit var dbFile: File
    private lateinit var trackRepository: TrackRepository

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mukk-track-test", ".db")
        dbFile.delete()
        trackRepository = TrackRepository(DatabaseInit(dbFile))
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    private suspend fun insert(path: String) {
        trackRepository.insertIfAbsent(
            filePath = path, title = File(path).nameWithoutExtension, artist = "", album = "",
            albumArtist = "", genre = "", trackNumber = 0, discNumber = 0, year = 0,
            durationMs = 0L, fileSize = 0L, lastModified = 0L
        )
    }

    @Test
    fun `deleteByPaths removes exactly the given rows`() = runBlocking {
        insert("/music/album/01 - Old.mp3")
        insert("/music/album/01. New.mp3")
        insert("/music/other/02 - Old.mp3")

        val deleted = trackRepository.deleteByPaths(listOf("/music/album/01 - Old.mp3", "/music/other/02 - Old.mp3"))

        assertEquals(2, deleted)
        assertEquals(listOf("/music/album/01. New.mp3"), trackRepository.getAllTracks().map { it.filePath })
    }

    @Test
    fun `deleteByPaths handles more paths than one SQL statement can bind`() = runBlocking {
        val paths = (1..1200).map { "/music/album/$it.mp3" }
        paths.forEach { insert(it) }
        insert("/music/keep.mp3")

        val deleted = trackRepository.deleteByPaths(paths)

        assertEquals(1200, deleted)
        assertEquals(listOf("/music/keep.mp3"), trackRepository.getAllTracks().map { it.filePath })
    }
}
