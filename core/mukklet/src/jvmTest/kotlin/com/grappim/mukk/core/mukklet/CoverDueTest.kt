package com.grappim.mukk.core.mukklet

import com.grappim.mukk.core.model.PlaybackStatus
import com.grappim.mukk.core.model.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CoverDueTest {

    private val track = DisplayTrack(
        id = "a3f9c2",
        title = "Title",
        artist = "Artist",
        album = "Album",
        albumArtist = "",
        year = 0,
        genre = "",
        trackNo = 1,
        durationMs = 180_000,
        format = "FLAC",
        hasCover = false
    )
    private val state = DisplayState(
        status = PlaybackStatus.PLAYING,
        positionMs = 0,
        volume = 80,
        repeat = RepeatMode.OFF,
        shuffle = false
    )
    private val art = CoverArt(byteArrayOf(1, 2, 3))
    private val snapshot = NowPlayingSnapshot(track, NextTrack("Next", "Artist"), art, state)
    private val sent = SentCover(track.id, art, encoded = true)

    @Test
    fun `cover art with the same bytes is equal`() {
        assertEquals(CoverArt(byteArrayOf(1, 2, 3)), CoverArt(byteArrayOf(1, 2, 3)))
        assertNotEquals(CoverArt(byteArrayOf(1, 2, 3)), CoverArt(byteArrayOf(1, 2, 4)))
    }

    @Test
    fun `cover is due on the first send`() {
        assertTrue(isCoverDue(null, snapshot))
    }

    @Test
    fun `cover is due for a new track`() {
        assertTrue(isCoverDue(sent, snapshot.copy(track = track.copy(id = "b00b1e"))))
    }

    @Test
    fun `cover is due for new art`() {
        assertTrue(isCoverDue(sent, snapshot.copy(cover = CoverArt(byteArrayOf(9)))))
        assertTrue(isCoverDue(sent, snapshot.copy(cover = null)))
    }

    @Test
    fun `cover is not due when only next changed`() {
        assertFalse(isCoverDue(sent, snapshot.copy(next = NextTrack("Other", "Artist"))))
    }

    @Test
    fun `cover is not due when the same art was read again`() {
        assertFalse(isCoverDue(sent, snapshot.copy(cover = CoverArt(byteArrayOf(1, 2, 3)))))
    }
}
