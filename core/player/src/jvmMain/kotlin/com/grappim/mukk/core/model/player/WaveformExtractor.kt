package com.grappim.mukk.core.model.player

import com.grappim.mukk.core.model.MukkLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freedesktop.gstreamer.Caps
import org.freedesktop.gstreamer.Element
import org.freedesktop.gstreamer.ElementFactory
import org.freedesktop.gstreamer.GhostPad
import org.freedesktop.gstreamer.PadLinkException
import org.freedesktop.gstreamer.Pipeline
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.elements.AppSink
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

class WaveformExtractor {

    suspend fun extract(file: File, bucketCount: Int = BUCKET_COUNT): FloatArray? =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            var pipeline: Pipeline? = null
            try {
                pipeline = buildPipeline(file) ?: return@withContext null
                val appSink = pipeline.getElementByName("appsink") as? AppSink
                    ?: return@withContext null

                pipeline.play()

                val chunks = mutableListOf<ShortArray>()
                var totalSamples = 0

                while (!appSink.isEOS) {
                    val sample = appSink.pullSample() ?: break
                    val buffer = sample.getBuffer()
                    val bb = buffer.map(false)
                    if (bb != null) {
                        bb.order(ByteOrder.LITTLE_ENDIAN)
                        val chunk = ShortArray(bb.remaining() / 2) { bb.short }
                        buffer.unmap()
                        if (chunk.isNotEmpty()) {
                            chunks.add(chunk)
                            totalSamples += chunk.size
                        }
                    }
                }

                if (totalSamples == 0) null
                else computePeaks(chunks, totalSamples, bucketCount)
            } catch (e: Exception) {
                MukkLogger.warn("WaveformExtractor", "Extraction failed: ${file.name}", e)
                null
            } finally {
                try {
                    pipeline?.setState(State.NULL)
                    pipeline?.dispose()
                } catch (e: Exception) {
                    MukkLogger.debug("WaveformExtractor", "Pipeline cleanup error: ${e.message}")
                }
            }
        }

    private fun buildPipeline(file: File): Pipeline? = try {
        val pipeline = Pipeline("waveform")
        val src = ElementFactory.make("filesrc", "src")
        src.set("location", file.absolutePath)
        val decodebin = ElementFactory.make("decodebin", "decode")
        val convert = ElementFactory.make("audioconvert", "convert")
        val resample = ElementFactory.make("audioresample", "resample")
        val capsFilter = ElementFactory.make("capsfilter", "capsfilter")
        capsFilter.set("caps", Caps.fromString("audio/x-raw,format=S16LE,channels=1,rate=22050"))
        val appSink = AppSink("appsink")
        appSink.set("sync", false)
        appSink.set("emit-signals", false)
        appSink.set("max-buffers", 50)
        appSink.set("drop", false)

        pipeline.addMany(src, decodebin, convert, resample, capsFilter, appSink)
        src.link(decodebin)
        Element.linkMany(convert, resample, capsFilter, appSink)

        // decodebin has dynamic pads — link first audio pad to audioconvert
        decodebin.connect(Element.PAD_ADDED { _, pad ->
            val caps = pad.currentCaps ?: return@PAD_ADDED
            if (caps.size() == 0) return@PAD_ADDED
            if (!caps.getStructure(0).name.startsWith("audio/")) return@PAD_ADDED
            val sinkPad = convert.getStaticPad("sink") ?: return@PAD_ADDED
            if (!sinkPad.isLinked) {
                try { pad.link(sinkPad) } catch (e: PadLinkException) {
                    MukkLogger.warn("WaveformExtractor", "Pad link failed: ${e.message}")
                }
            }
        })

        pipeline
    } catch (e: Exception) {
        MukkLogger.warn("WaveformExtractor", "Pipeline build failed", e)
        null
    }

    private fun computePeaks(chunks: List<ShortArray>, totalSamples: Int, bucketCount: Int): FloatArray {
        val peaks = FloatArray(bucketCount)
        var pos = 0
        for (chunk in chunks) {
            for (sample in chunk) {
                val bucket = (pos.toLong() * bucketCount / totalSamples).toInt()
                    .coerceIn(0, bucketCount - 1)
                val amplitude = abs(sample.toInt()).toFloat() / Short.MAX_VALUE
                if (amplitude > peaks[bucket]) peaks[bucket] = amplitude
                pos++
            }
        }
        return peaks
    }

    companion object {
        const val BUCKET_COUNT = 400
    }
}
