package com.grappim.mukk.core.mukklet

import com.grappim.mukk.core.model.PlaybackStatus
import com.grappim.mukk.core.model.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkTimingTest {

    private val playing = DisplayState(
        status = PlaybackStatus.PLAYING,
        positionMs = 10_000,
        volume = 80,
        repeat = RepeatMode.OFF,
        shuffle = false
    )
    private val paused = playing.copy(status = PlaybackStatus.PAUSED)

    @Test
    fun `reconnect backoff doubles from 2 s and caps at 30 s`() {
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            (0..6).map { reconnectDelayMs(it) }
        )
        assertEquals(30_000L, reconnectDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun `first state is always due`() {
        assertTrue(isStateDue(null, playing, nowMs = 0))
    }

    @Test
    fun `normal playback progress is not due before the heartbeat`() {
        val sent = SentState(playing, atMs = 1_000)
        assertFalse(isStateDue(sent, playing.copy(positionMs = 13_000), nowMs = 4_000))
        assertFalse(isStateDue(sent, playing.copy(positionMs = 14_400), nowMs = 4_000))
    }

    @Test
    fun `heartbeat is due after 5 s`() {
        val sent = SentState(paused, atMs = 1_000)
        assertFalse(isStateDue(sent, paused, nowMs = 5_999))
        assertTrue(isStateDue(sent, paused, nowMs = 6_000))
    }

    @Test
    fun `seek is due when position leaves the extrapolation`() {
        val sentPlaying = SentState(playing, atMs = 1_000)
        assertTrue(isStateDue(sentPlaying, playing.copy(positionMs = 30_000), nowMs = 2_000))
        assertTrue(isStateDue(sentPlaying, playing.copy(positionMs = 0), nowMs = 2_000))

        val sentPaused = SentState(paused, atMs = 1_000)
        assertFalse(isStateDue(sentPaused, paused.copy(positionMs = 11_000), nowMs = 3_000))
        assertTrue(isStateDue(sentPaused, paused.copy(positionMs = 12_000), nowMs = 3_000))
    }

    @Test
    fun `status, volume, repeat and shuffle changes are due`() {
        val sent = SentState(playing, atMs = 1_000)
        val now = 1_200L
        val position = 10_200L
        assertTrue(isStateDue(sent, paused.copy(positionMs = position), now))
        assertTrue(isStateDue(sent, playing.copy(positionMs = position, volume = 85), now))
        assertTrue(isStateDue(sent, playing.copy(positionMs = position, repeat = RepeatMode.ALL), now))
        assertTrue(isStateDue(sent, playing.copy(positionMs = position, shuffle = true), now))
        assertFalse(isStateDue(sent, playing.copy(positionMs = position), now))
    }
}
