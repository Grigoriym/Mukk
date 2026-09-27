package com.grappim.mukk.core.mukklet

import com.grappim.mukk.core.model.MukkLogger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Builds and parses the JSON text frames of the Mukk ↔ Mukklet protocol
 * (`../esp32-mukklet/docs/PROTOCOL.md`). Unknown message types, unknown commands and unknown
 * fields are ignored, as the protocol requires.
 */
object ProtocolMessages {

    private const val TAG = "ProtocolMessages"
    private const val DEFAULT_MAX_CHUNK = 4096

    fun track(track: DisplayTrack?, next: NextTrack?): String = buildJsonObject {
        put("type", "track")
        if (track == null) {
            put("track", JsonNull)
        } else {
            put("track", buildJsonObject {
                put("id", track.id)
                put("title", track.title)
                put("artist", track.artist)
                put("album", track.album)
                put("albumArtist", track.albumArtist)
                put("year", track.year)
                put("genre", track.genre)
                put("trackNo", track.trackNo)
                put("durationMs", track.durationMs)
                put("format", track.format)
                put("hasCover", track.hasCover)
            })
        }
        if (next == null) {
            put("next", JsonNull)
        } else {
            put("next", buildJsonObject {
                put("title", next.title)
                put("artist", next.artist)
            })
        }
    }.toString()

    fun coverNone(trackId: String): String = buildJsonObject {
        put("type", "cover")
        put("trackId", trackId)
        put("none", true)
    }.toString()

    /** The header of a cover with pixels; [size] bytes of binary frames follow it. */
    fun cover(trackId: String, spec: CoverSpec, size: Int): String = buildJsonObject {
        put("type", "cover")
        put("trackId", trackId)
        put("w", spec.w)
        put("h", spec.h)
        put("format", spec.format.name.lowercase())
        put("size", size)
    }.toString()

    fun state(state: DisplayState): String = buildJsonObject {
        put("type", "state")
        put("status", state.status.name.lowercase())
        put("positionMs", state.positionMs)
        put("volume", state.volume)
        put("repeat", state.repeat.name.lowercase())
        put("shuffle", state.shuffle)
    }.toString()

    /** Returns `null` for malformed JSON, an unknown `type`, or an unknown `cmd`. */
    fun parse(text: String): IncomingMessage? {
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            MukkLogger.warn(TAG, "Malformed message from display: $text", e)
            null
        } ?: return null
        return when (val type = obj.string("type")) {
            "hello" -> parseHello(obj)
            "cmd" -> parseCommand(obj)?.let { IncomingMessage.Command(it) }
            else -> {
                MukkLogger.debug(TAG, "Ignoring message type: $type")
                null
            }
        }
    }

    private fun parseHello(obj: JsonObject): IncomingMessage.Hello {
        val cover = obj["cover"] as? JsonObject
        val format = when (cover?.string("format")) {
            "mono1" -> CoverFormat.MONO1
            "rgb565" -> CoverFormat.RGB565
            else -> CoverFormat.NONE
        }
        return IncomingMessage.Hello(
            v = obj.intOrNull("v") ?: 1,
            device = obj.string("device").orEmpty(),
            cover = CoverSpec(
                w = cover?.intOrNull("w") ?: 0,
                h = cover?.intOrNull("h") ?: 0,
                format = format
            ),
            maxChunk = obj.intOrNull("maxChunk") ?: DEFAULT_MAX_CHUNK
        )
    }

    private fun parseCommand(obj: JsonObject): DisplayCommand? =
        when (val cmd = obj.string("cmd")) {
            "play_pause" -> DisplayCommand.PlayPause
            "next" -> DisplayCommand.Next
            "prev" -> DisplayCommand.Prev
            "volume" -> obj.intOrNull("delta")?.let { DisplayCommand.Volume(it) }
            "seek" -> obj.longOrNull("deltaMs")?.let { DisplayCommand.Seek(it) }
            else -> {
                MukkLogger.debug(TAG, "Ignoring unknown cmd: $cmd")
                null
            }
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
