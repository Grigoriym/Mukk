package com.grappim.mukk.core.data

import com.grappim.mukk.core.model.PlaylistBrowseState
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistRepositoryTest {

    private lateinit var dbFile: File
    private lateinit var databaseInit: DatabaseInit
    private lateinit var trackRepository: TrackRepository
    private lateinit var waveformRepository: WaveformRepository
    private lateinit var playlistRepository: PlaylistRepository

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mukk-playlist-test", ".db")
        dbFile.delete()
        databaseInit = DatabaseInit(dbFile)
        trackRepository = TrackRepository(databaseInit)
        waveformRepository = WaveformRepository(databaseInit)
        playlistRepository = PlaylistRepository(databaseInit, trackRepository, waveformRepository)
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `create assigns increasing sort order and getAll returns them in order`() = runBlocking {
        val first = playlistRepository.create("Default", "/music/default")
        val second = playlistRepository.create("Podcasts", "/music/podcasts")

        assertEquals(0, first.sortOrder)
        assertEquals(1, second.sortOrder)

        val all = playlistRepository.getAll()
        assertEquals(listOf(first.id, second.id), all.map { it.id })
    }

    @Test
    fun `rename updates the stored name`() = runBlocking {
        val playlist = playlistRepository.create("Default", "/music/default")

        val renamed = playlistRepository.rename(playlist.id, "Classical")

        assertTrue(renamed)
        assertEquals("Classical", playlistRepository.getAll().single().name)
    }

    @Test
    fun `reorder writes new sort order values`() = runBlocking {
        val first = playlistRepository.create("Default", "/music/default")
        val second = playlistRepository.create("Podcasts", "/music/podcasts")

        playlistRepository.reorder(listOf(second.id, first.id))

        val all = playlistRepository.getAll()
        assertEquals(listOf(second.id, first.id), all.map { it.id })
    }

    @Test
    fun `delete removes the playlist and cascades into tracks and waveform cache`() = runBlocking {
        val playlist = playlistRepository.create("Default", "/music/default")
        val trackPath = "/music/default/song.mp3"

        trackRepository.insertIfAbsent(
            filePath = trackPath,
            title = "Song",
            artist = "Artist",
            album = "Album",
            albumArtist = "Artist",
            genre = "Genre",
            trackNumber = 1,
            discNumber = 1,
            year = 2020,
            durationMs = 1000L,
            fileSize = 2048L,
            lastModified = 0L
        )
        waveformRepository.put(trackPath, floatArrayOf(0.1f, 0.2f))

        val deleted = playlistRepository.delete(playlist.id)

        assertTrue(deleted)
        assertTrue(playlistRepository.getAll().isEmpty())
        assertNull(trackRepository.findByPath(trackPath))
        assertNull(waveformRepository.get(trackPath))
    }

    @Test
    fun `delete of unknown id returns false`() = runBlocking {
        val deleted = playlistRepository.delete(id = 999L)

        assertTrue(!deleted)
    }

    @Test
    fun `saveBrowseState is returned by getBrowseStates`() = runBlocking {
        val first = playlistRepository.create("Default", "/music/default")
        playlistRepository.create("Podcasts", "/music/podcasts")
        val state = PlaylistBrowseState(
            selectedPath = "/music/default/album",
            expandedPaths = setOf("/music/default", "/music/default/album")
        )

        val saved = playlistRepository.saveBrowseState(first.id, state)

        assertTrue(saved)
        assertEquals(mapOf(first.id to state), playlistRepository.getBrowseStates())
    }

    @Test
    fun `existing database without browse columns gains them and keeps its rows`() = runBlocking {
        dbFile.delete()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(256) NOT NULL, " +
                        "folder_path VARCHAR(1024) NOT NULL, sort_order INT DEFAULT 0 NOT NULL)"
                )
                statement.executeUpdate(
                    "INSERT INTO playlists (name, folder_path, sort_order) VALUES ('Old', '/music/old', 0)"
                )
            }
        }

        val repository = PlaylistRepository(DatabaseInit(dbFile), trackRepository, waveformRepository)
        val old = repository.getAll().single()
        repository.saveBrowseState(old.id, PlaylistBrowseState("/music/old/album", setOf("/music/old")))

        assertEquals("Old", old.name)
        assertEquals("/music/old/album", repository.getBrowseStates().getValue(old.id).selectedPath)
    }
}
