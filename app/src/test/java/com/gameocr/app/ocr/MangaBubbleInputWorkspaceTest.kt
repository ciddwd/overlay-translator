package com.gameocr.app.ocr

import org.junit.Assert.*
import org.junit.Test

class MangaBubbleInputWorkspaceTest {
    @Test
    fun repeatedPacking_table_keepsChannelOrderDirectBuffersAndNoStalePixels() {
        val io = MangaBubbleInputWorkspace(2)
        val image = io.imageBuffer
        val size = io.sizeBuffer
        for (dimensions in listOf(1440 to 3200, 3200 to 1440, 1 to 1)) {
            io.pixels.indices.forEach { i -> io.pixels[i] = if (dimensions.first == 1440) 0xff102080.toInt() + i else 0xfff0a030.toInt() - i }
            io.pack(dimensions.first, dimensions.second)
            assertSame(image, io.imageBuffer)
            assertSame(size, io.sizeBuffer)
            assertTrue(image.isDirect)
            assertTrue(size.isDirect)
            assertEquals(0, image.position())
            assertEquals(12, image.remaining())
            for (i in io.pixels.indices) {
                assertEquals((io.pixels[i] ushr 16 and 255) / 255f, image[i], 0f)
                assertEquals((io.pixels[i] ushr 8 and 255) / 255f, image[4 + i], 0f)
                assertEquals((io.pixels[i] and 255) / 255f, image[8 + i], 0f)
            }
            assertEquals(dimensions.first.toLong(), size[0])
            assertEquals(dimensions.second.toLong(), size[1])
            // ORT can read through a view without changing subsequent pack/reset behavior.
            image.position(image.limit())
            size.position(size.limit())
        }
    }

    @Test
    fun invalidDimensions_areRejected() {
        val io = MangaBubbleInputWorkspace(1)
        for (dims in listOf(0 to 10, 10 to 0, -1 to 3)) {
            try { io.pack(dims.first, dims.second); fail("invalid size must not reach ORT") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
