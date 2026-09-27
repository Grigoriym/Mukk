package com.grappim.mukk.core.mukklet

import com.grappim.mukk.core.model.MukkLogger
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Turns embedded cover-art bytes into the pixel data of a `cover` message
 * (`../esp32-mukklet/docs/PROTOCOL.md`, "Cover formats"): decode → center-crop to the target
 * aspect ratio → scale to exactly `w × h` → `mono1` or `rgb565`.
 */
object CoverEncoder {

    private const val TAG = "CoverEncoder"
    private const val MONO1_THRESHOLD = 128f
    private const val BITS_PER_BYTE = 8

    /**
     * Returns the pixel data for [spec], or `null` when there is nothing to send: the bytes
     * don't decode, [spec] asks for `none`, or [spec] is invalid (`mono1` needs `w` to be a
     * multiple of 8). The caller then sends `cover` with `none: true`.
     */
    fun encode(imageBytes: ByteArray, spec: CoverSpec): ByteArray? {
        if (spec.format == CoverFormat.NONE || !isSupported(spec)) return null
        val scaled = decode(imageBytes)?.let { scale(cropToAspect(it, spec.w, spec.h), spec.w, spec.h) }
        return when {
            scaled == null -> null
            spec.format == CoverFormat.MONO1 -> toMono1(scaled)
            else -> toRgb565(scaled)
        }
    }

    private fun isSupported(spec: CoverSpec): Boolean {
        val sizeOk = spec.w > 0 && spec.h > 0
        val widthOk = spec.format != CoverFormat.MONO1 || spec.w % BITS_PER_BYTE == 0
        if (!sizeOk || !widthOk) MukkLogger.warn(TAG, "Unsupported cover spec: $spec")
        return sizeOk && widthOk
    }

    /** Splits [data] into binary-frame payloads of at most [maxChunk] bytes, in order. */
    fun chunks(data: ByteArray, maxChunk: Int): List<ByteArray> {
        require(maxChunk > 0) { "maxChunk must be positive: $maxChunk" }
        return (data.indices step maxChunk).map { start ->
            data.copyOfRange(start, minOf(start + maxChunk, data.size))
        }
    }

    private fun decode(bytes: ByteArray): BufferedImage? {
        val image = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: IOException) {
            MukkLogger.debug(TAG, "Cover art failed to decode: ${e.message}")
            null
        }
        if (image == null) MukkLogger.debug(TAG, "Cover art is in an unreadable format")
        return image
    }

    /** The largest centered region of [image] with the aspect ratio `w : h`. */
    internal fun cropToAspect(image: BufferedImage, w: Int, h: Int): BufferedImage {
        // Compare image.width / image.height with w / h without floating point.
        val srcW = image.width.toLong()
        val srcH = image.height.toLong()
        return if (srcW * h > srcH * w) {
            val cropW = (srcH * w / h).toInt().coerceAtLeast(1)
            image.getSubimage((image.width - cropW) / 2, 0, cropW, image.height)
        } else {
            val cropH = (srcW * h / w).toInt().coerceAtLeast(1)
            image.getSubimage(0, (image.height - cropH) / 2, image.width, cropH)
        }
    }

    /**
     * Scales to exactly `w × h` as an opaque RGB image. A large downscale halves the image in
     * bilinear steps first: one bilinear step straight from, say, 1000 px to 64 px skips most
     * source pixels and aliases.
     */
    private fun scale(image: BufferedImage, w: Int, h: Int): BufferedImage {
        var current = image
        while (current.width / 2 >= w && current.height / 2 >= h) {
            current = drawScaled(current, current.width / 2, current.height / 2)
        }
        return drawScaled(current, w, h)
    }

    private fun drawScaled(image: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(image, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /**
     * Grayscale → Floyd–Steinberg dither at threshold 128 → 1 bit per pixel, rows top to
     * bottom, MSB = leftmost pixel, `1` = lit (white). `image.width` must be a multiple of 8.
     */
    internal fun toMono1(image: BufferedImage): ByteArray {
        val w = image.width
        val h = image.height
        val gray = FloatArray(w * h) { i -> luma(image.getRGB(i % w, i / w)) }
        val out = ByteArray(w * h / BITS_PER_BYTE)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val lit = gray[i] >= MONO1_THRESHOLD
                if (lit) {
                    val bit = 0x80 ushr (x % BITS_PER_BYTE)
                    out[i / BITS_PER_BYTE] = (out[i / BITS_PER_BYTE].toInt() or bit).toByte()
                }
                val error = gray[i] - if (lit) 255f else 0f
                if (x + 1 < w) gray[i + 1] += error * 7 / 16
                if (y + 1 < h) {
                    if (x > 0) gray[i + w - 1] += error * 3 / 16
                    gray[i + w] += error * 5 / 16
                    if (x + 1 < w) gray[i + w + 1] += error * 1 / 16
                }
            }
        }
        return out
    }

    /** 16 bits per pixel (5 red, 6 green, 5 blue), rows top to bottom, big-endian. */
    internal fun toRgb565(image: BufferedImage): ByteArray {
        val w = image.width
        val out = ByteArray(w * image.height * 2)
        for (i in 0 until w * image.height) {
            val rgb = image.getRGB(i % w, i / w)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            val pixel = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
            out[2 * i] = (pixel shr 8).toByte()
            out[2 * i + 1] = pixel.toByte()
        }
        return out
    }

    private fun luma(rgb: Int): Float {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}
