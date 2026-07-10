package com.gameocr.app.capture

import android.graphics.PixelFormat
import com.gameocr.app.capture.ShizukuRawScreencapDecoder.BitmapEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShizukuRawScreencapDecoderTest {

    @Test
    fun parseSupportedRawFrames_tableDriven() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val pixelFormat: Int,
            val headerBytes: Int,
            val colorspace: Int,
            val expectedBytesPerPixel: Int,
            val expectedEncoding: BitmapEncoding
        )

        val cases = listOf(
            Case(
                name = "current rgba8888 header with colorspace",
                width = 2,
                height = 3,
                pixelFormat = PixelFormat.RGBA_8888,
                headerBytes = 16,
                colorspace = 42,
                expectedBytesPerPixel = 4,
                expectedEncoding = BitmapEncoding.ARGB_8888_DIRECT
            ),
            Case(
                name = "current rgbx8888 header with colorspace",
                width = 4,
                height = 1,
                pixelFormat = PixelFormat.RGBX_8888,
                headerBytes = 16,
                colorspace = 7,
                expectedBytesPerPixel = 4,
                expectedEncoding = BitmapEncoding.ARGB_8888_DIRECT
            ),
            Case(
                name = "legacy rgb565 header without colorspace",
                width = 3,
                height = 2,
                pixelFormat = PixelFormat.RGB_565,
                headerBytes = 12,
                colorspace = 0,
                expectedBytesPerPixel = 2,
                expectedEncoding = BitmapEncoding.RGB_565_DIRECT
            ),
            Case(
                name = "current rgb888 header converts to argb8888",
                width = 2,
                height = 2,
                pixelFormat = PixelFormat.RGB_888,
                headerBytes = 16,
                colorspace = 99,
                expectedBytesPerPixel = 3,
                expectedEncoding = BitmapEncoding.RGB_888_TO_ARGB_8888
            )
        )

        cases.forEach { case ->
            val spec = ShizukuRawScreencapDecoder.parse(
                rawFrame(
                    width = case.width,
                    height = case.height,
                    pixelFormat = case.pixelFormat,
                    headerBytes = case.headerBytes,
                    colorspace = case.colorspace
                )
            )

            assertNotNull(case.name, spec)
            spec!!
            assertEquals(case.name, case.width, spec.width)
            assertEquals(case.name, case.height, spec.height)
            assertEquals(case.name, case.pixelFormat, spec.pixelFormat)
            assertEquals(case.name, case.headerBytes, spec.headerBytes)
            assertEquals(case.name, case.colorspace, spec.colorspace)
            assertEquals(case.name, case.expectedBytesPerPixel, spec.bytesPerPixel)
            assertEquals(case.name, case.expectedEncoding, spec.bitmapEncoding)
        }
    }

    @Test
    fun rejectMalformedRawFrames_tableDriven() {
        data class Case(
            val name: String,
            val bytes: ByteArray
        )

        val cases = listOf(
            Case(
                name = "shorter than legacy header",
                bytes = ByteArray(11)
            ),
            Case(
                name = "zero width",
                bytes = rawFrame(width = 0, height = 1, pixelFormat = PixelFormat.RGBA_8888)
            ),
            Case(
                name = "negative height",
                bytes = headerOnly(width = 1, height = -1, pixelFormat = PixelFormat.RGBA_8888)
            ),
            Case(
                name = "dimension exceeds guardrail",
                bytes = headerOnly(width = 10_001, height = 1, pixelFormat = PixelFormat.RGBA_8888)
            ),
            Case(
                name = "pixel count exceeds guardrail",
                bytes = headerOnly(width = 6_000, height = 6_000, pixelFormat = PixelFormat.RGBA_8888)
            ),
            Case(
                name = "unsupported pixel format",
                bytes = rawFrame(width = 1, height = 1, pixelFormat = 99)
            ),
            Case(
                name = "truncated payload",
                bytes = rawFrame(width = 2, height = 2, pixelFormat = PixelFormat.RGBA_8888)
                    .let { it.copyOf(it.size - 1) }
            )
        )

        cases.forEach { case ->
            assertNull(case.name, ShizukuRawScreencapDecoder.parse(case.bytes))
        }
    }

    @Test
    fun parseCurrentHeaderWithTrailingPayloadBytes() {
        val frame = rawFrame(
            width = 2,
            height = 1,
            pixelFormat = PixelFormat.RGBA_8888,
            headerBytes = 16,
            colorspace = 12
        )
        val spec = ShizukuRawScreencapDecoder.parse(frame + byteArrayOf(1, 2, 3, 4))

        assertNotNull(spec)
        spec!!
        assertEquals(16, spec.headerBytes)
        assertEquals(12, spec.colorspace)
        assertEquals(2 * 1 * 4, spec.pixelByteCount)
    }

    private fun rawFrame(
        width: Int,
        height: Int,
        pixelFormat: Int,
        headerBytes: Int = 16,
        colorspace: Int = 0
    ): ByteArray {
        val payloadSize = when {
            width <= 0 || height <= 0 -> 0
            else -> width * height * bytesPerPixelForTest(pixelFormat)
        }
        return headerOnly(width, height, pixelFormat, headerBytes, colorspace) +
            ByteArray(payloadSize) { (it and 0xff).toByte() }
    }

    private fun headerOnly(
        width: Int,
        height: Int,
        pixelFormat: Int,
        headerBytes: Int = 16,
        colorspace: Int = 0
    ): ByteArray {
        val bytes = ByteArray(headerBytes)
        putIntLe(bytes, 0, width)
        putIntLe(bytes, 4, height)
        putIntLe(bytes, 8, pixelFormat)
        if (headerBytes >= 16) {
            putIntLe(bytes, 12, colorspace)
        }
        return bytes
    }

    private fun bytesPerPixelForTest(pixelFormat: Int): Int = when (pixelFormat) {
        PixelFormat.RGBA_8888,
        PixelFormat.RGBX_8888 -> 4
        PixelFormat.RGB_565 -> 2
        PixelFormat.RGB_888 -> 3
        else -> 4
    }

    private fun putIntLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}
