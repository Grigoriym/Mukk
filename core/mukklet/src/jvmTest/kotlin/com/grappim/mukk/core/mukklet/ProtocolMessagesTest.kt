package com.grappim.mukk.core.mukklet

import com.grappim.mukk.core.model.PlaybackStatus
import com.grappim.mukk.core.model.RepeatMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Fixtures in resources/protocol/ are copies of ../esp32-mukklet/docs/protocol/*.json.
class ProtocolMessagesTest {

    private fun fixture(name: String): JsonElement {
        val text = requireNotNull(javaClass.getResource("/protocol/$name")) { "missing fixture $name" }.readText()
        return Json.parseToJsonElement(text)
    }

    private fun parseJson(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `track message matches the protocol example`() {
        val built = ProtocolMessages.track(
            track = DisplayTrack(
                id = "a3f9c2",
                title = "Paranoid Android",
                artist = "Radiohead",
                album = "OK Computer",
                albumArtist = "Radiohead",
                year = 1997,
                genre = "Alternative",
                trackNo = 2,
                durationMs = 386000,
                format = "FLAC",
                hasCover = true
            ),
            next = NextTrack(title = "Subterranean Homesick Alien", artist = "Radiohead")
        )
        assertEquals(fixture("track.json"), parseJson(built))
    }

    @Test
    fun `no track sends null track and null next`() {
        assertEquals(fixture("track_none.json"), parseJson(ProtocolMessages.track(track = null, next = null)))
    }

    @Test
    fun `unknown next is sent as null, not omitted`() {
        val built = parseJson(ProtocolMessages.track(sampleTrack(), next = null)).jsonObject
        assertEquals(true, built.containsKey("next"))
        assertEquals("null", built["next"].toString())
    }

    @Test
    fun `cover none matches the protocol example`() {
        assertEquals(fixture("cover_none.json"), parseJson(ProtocolMessages.coverNone("a3f9c2")))
    }

    @Test
    fun `state message matches the protocol example`() {
        val built = ProtocolMessages.state(
            DisplayState(
                status = PlaybackStatus.PLAYING,
                positionMs = 125340,
                volume = 80,
                repeat = RepeatMode.ALL,
                shuffle = false
            )
        )
        assertEquals(fixture("state.json"), parseJson(built))
    }

    @Test
    fun `state lower-cases every status and repeat mode`() {
        fun field(state: DisplayState, key: String) =
            parseJson(ProtocolMessages.state(state)).jsonObject[key]!!.jsonPrimitive.content

        val statuses = PlaybackStatus.entries.map { field(sampleState().copy(status = it), "status") }
        assertEquals(listOf("idle", "playing", "paused", "stopped"), statuses)
        val repeats = RepeatMode.entries.map { field(sampleState().copy(repeat = it), "repeat") }
        assertEquals(listOf("off", "one", "all"), repeats)
    }

    @Test
    fun `Cyrillic text and quotes survive unchanged`() {
        val title = "Группа \"Крови\" \\ Кино"
        val built = parseJson(ProtocolMessages.track(sampleTrack().copy(title = title, artist = "Кино"), next = null))
        val track = built.jsonObject["track"]!!.jsonObject
        assertEquals(title, track["title"]!!.jsonPrimitive.content)
        assertEquals("Кино", track["artist"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hello from the protocol example parses`() {
        val hello = ProtocolMessages.parse(fixture("hello.json").toString())
        assertEquals(
            IncomingMessage.Hello(
                v = 1,
                device = "mukklet-oled",
                cover = CoverSpec(w = 64, h = 64, format = CoverFormat.MONO1),
                maxChunk = 4096
            ),
            hello
        )
    }

    @Test
    fun `hello with rgb565, none and an unknown format`() {
        fun formatOf(format: String) = (ProtocolMessages.parse(
            """{"type":"hello","v":1,"device":"x","cover":{"w":240,"h":240,"format":"$format"},"maxChunk":2048}"""
        ) as IncomingMessage.Hello).cover.format

        assertEquals(CoverFormat.RGB565, formatOf("rgb565"))
        assertEquals(CoverFormat.NONE, formatOf("none"))
        assertEquals(CoverFormat.NONE, formatOf("jpeg"))
    }

    @Test
    fun `every command from the protocol example parses`() {
        val commands = (fixture("cmd.json") as JsonArray).map {
            (ProtocolMessages.parse(it.toString()) as IncomingMessage.Command).command
        }
        assertEquals(
            listOf(
                DisplayCommand.PlayPause,
                DisplayCommand.Next,
                DisplayCommand.Prev,
                DisplayCommand.Volume(delta = 5),
                DisplayCommand.Seek(deltaMs = -10000)
            ),
            commands
        )
    }

    @Test
    fun `unknown fields are ignored`() {
        assertEquals(
            IncomingMessage.Command(DisplayCommand.Volume(delta = -3)),
            ProtocolMessages.parse("""{"type":"cmd","cmd":"volume","delta":-3,"accel":2,"extra":{"a":[1]}}""")
        )
    }

    @Test
    fun `unknown type, unknown cmd and malformed input are ignored`() {
        assertNull(ProtocolMessages.parse("""{"type":"battery","level":40}"""))
        assertNull(ProtocolMessages.parse("""{"type":"cmd","cmd":"like"}"""))
        assertNull(ProtocolMessages.parse("""{"type":"cmd","cmd":"volume"}"""))
        assertNull(ProtocolMessages.parse("""{"no type":true}"""))
        assertNull(ProtocolMessages.parse("""[1,2]"""))
        assertNull(ProtocolMessages.parse("""not json"""))
    }

    private fun sampleTrack() = DisplayTrack(
        id = "1",
        title = "t",
        artist = "",
        album = "",
        albumArtist = "",
        year = 0,
        genre = "",
        trackNo = 0,
        durationMs = 0,
        format = "MP3",
        hasCover = false
    )

    private fun sampleState() = DisplayState(
        status = PlaybackStatus.IDLE,
        positionMs = 0,
        volume = 0,
        repeat = RepeatMode.OFF,
        shuffle = false
    )
}
