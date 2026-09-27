package com.grappim.mukk.core.mukklet

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverEncoderTest {

    private fun image(w: Int, h: Int, color: (x: Int, y: Int) -> Color): BufferedImage =
        BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until h) for (x in 0 until w) setRGB(x, y, color(x, y).rgb)
        }

    private fun png(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    private fun setBits(bytes: ByteArray): Int = bytes.sumOf { Integer.bitCount(it.toInt() and 0xFF) }

    @Test
    fun `crop of a 200x100 image to a square keeps the center`() {
        // Red left 50 px, green middle 100 px, blue right 50 px.
        val source = image(200, 100) { x, _ ->
            when {
                x < 50 -> Color.RED
                x < 150 -> Color.GREEN
                else -> Color.BLUE
            }
        }
        val cropped = CoverEncoder.cropToAspect(source, 64, 64)

        assertEquals(100, cropped.width)
        assertEquals(100, cropped.height)
        for (y in 0 until 100) for (x in 0 until 100) {
            assertEquals(Color.GREEN.rgb, cropped.getRGB(x, y), "pixel $x,$y")
        }
    }

    @Test
    fun `crop of a tall image keeps the vertical center`() {
        val source = image(100, 300) { _, y -> if (y in 100 until 200) Color.GREEN else Color.RED }
        val cropped = CoverEncoder.cropToAspect(source, 240, 240)

        assertEquals(100, cropped.width)
        assertEquals(100, cropped.height)
        assertEquals(Color.GREEN.rgb, cropped.getRGB(0, 0))
        assertEquals(Color.GREEN.rgb, cropped.getRGB(99, 99))
    }

    @Test
    fun `mono1 of left-white right-black 8x1 is 0xF0`() {
        val source = image(8, 1) { x, _ -> if (x < 4) Color.WHITE else Color.BLACK }

        assertContentEquals(byteArrayOf(0xF0.toByte()), CoverEncoder.toMono1(source))
    }

    @Test
    fun `mono1 of solid mid-gray dithers to about half set bits`() {
        val source = image(64, 64) { _, _ -> Color(128, 128, 128) }
        val bits = setBits(CoverEncoder.toMono1(source))
        val ratio = bits.toDouble() / (64 * 64)

        assertTrue(ratio in 0.45..0.55, "set-bit ratio was $ratio")
    }

    @Test
    fun `rgb565 of pure red is F8 00`() {
        val source = image(1, 1) { _, _ -> Color.RED }

        assertContentEquals(byteArrayOf(0xF8.toByte(), 0x00), CoverEncoder.toRgb565(source))
    }

    @Test
    fun `rgb565 is big-endian for pure blue and green`() {
        val source = image(2, 1) { x, _ -> if (x == 0) Color.BLUE else Color.GREEN }

        assertContentEquals(
            byteArrayOf(0x00, 0x1F, 0x07, 0xE0.toByte()),
            CoverEncoder.toRgb565(source)
        )
    }

    @Test
    fun `512 bytes with maxChunk 200 split into 200, 200, 112`() {
        val data = ByteArray(512) { it.toByte() }
        val chunks = CoverEncoder.chunks(data, 200)

        assertEquals(listOf(200, 200, 112), chunks.map { it.size })
        assertContentEquals(data, chunks.reduce { acc, bytes -> acc + bytes })
    }

    @Test
    fun `encode produces exactly the size the protocol asks for`() {
        val bytes = png(image(500, 300) { _, _ -> Color.RED })

        val mono = assertNotNull(CoverEncoder.encode(bytes, CoverSpec(64, 64, CoverFormat.MONO1)))
        assertEquals(64 * 64 / 8, mono.size)

        val rgb = assertNotNull(CoverEncoder.encode(bytes, CoverSpec(240, 240, CoverFormat.RGB565)))
        assertEquals(240 * 240 * 2, rgb.size)
        for (i in rgb.indices step 2) {
            assertEquals(0xF8.toByte(), rgb[i])
            assertEquals(0x00.toByte(), rgb[i + 1])
        }
    }

    @Test
    fun `encode returns null for bytes that are not an image`() {
        assertNull(CoverEncoder.encode(byteArrayOf(1, 2, 3, 4), CoverSpec(64, 64, CoverFormat.MONO1)))
    }

    @Test
    fun `encode returns null for format none and for mono1 width not a multiple of 8`() {
        val bytes = png(image(10, 10) { _, _ -> Color.WHITE })

        assertNull(CoverEncoder.encode(bytes, CoverSpec(64, 64, CoverFormat.NONE)))
        assertNull(CoverEncoder.encode(bytes, CoverSpec(60, 60, CoverFormat.MONO1)))
    }
}
