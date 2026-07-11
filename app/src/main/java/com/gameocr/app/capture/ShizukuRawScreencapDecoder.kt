package com.gameocr.app.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import java.nio.ByteBuffer

/**
 * Decodes the raw stdout format produced by `screencap` when it is invoked
 * without `-p`.
 *
 * AOSP writes little-endian width, height, pixel format, optional colorspace,
 * then compact pixel rows. Older platform builds omit the colorspace field.
 */
internal object ShizukuRawScreencapDecoder {

    private const val LEGACY_HEADER_BYTES = 12
    private const val COLORSPACE_HEADER_BYTES = 16
    private const val UNKNOWN_COLORSPACE = 0
    private const val MAX_DIMENSION = 10_000
    private const val MAX_PIXELS = 25_000_000

    internal data class FrameSpec(
        val width: Int,
        val height: Int,
        val pixelFormat: Int,
        val colorspace: Int,
        val headerBytes: Int,
        val bytesPerPixel: Int,
        val bitmapEncoding: BitmapEncoding
    ) {
        val pixelByteCount: Int = width * height * bytesPerPixel
    }

    internal enum class BitmapEncoding {
        ARGB_8888_DIRECT,
        RGB_565_DIRECT,
        RGB_888_TO_ARGB_8888
    }

    fun decode(bytes: ByteArray): Bitmap? {
        val spec = parse(bytes) ?: return null
        val offset = spec.headerBytes
        return when (spec.bitmapEncoding) {
            BitmapEncoding.ARGB_8888_DIRECT -> Bitmap
                .createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
                .also { bitmap ->
                    bitmap.copyPixelsFromBuffer(
                        ByteBuffer.wrap(bytes, offset, spec.pixelByteCount)
                    )
                }

            BitmapEncoding.RGB_565_DIRECT -> Bitmap
                .createBitmap(spec.width, spec.height, Bitmap.Config.RGB_565)
                .also { bitmap ->
                    bitmap.copyPixelsFromBuffer(
                        ByteBuffer.wrap(bytes, offset, spec.pixelByteCount)
                    )
                }

            BitmapEncoding.RGB_888_TO_ARGB_8888 -> decodeRgb888(spec, bytes, offset)
        }
    }

    internal fun parse(bytes: ByteArray): FrameSpec? =
        parseCandidate(bytes, COLORSPACE_HEADER_BYTES, requireExactPayload = true)
            ?: parseCandidate(bytes, LEGACY_HEADER_BYTES, requireExactPayload = true)
            ?: parseCandidate(bytes, COLORSPACE_HEADER_BYTES, requireExactPayload = false)

    private fun parseCandidate(
        bytes: ByteArray,
        headerBytes: Int,
        requireExactPayload: Boolean
    ): FrameSpec? {
        if (bytes.size < headerBytes) return null
        val width = readIntLe(bytes, 0)
        val height = readIntLe(bytes, 4)
        val format = readIntLe(bytes, 8)
        val colorspace = if (headerBytes >= COLORSPACE_HEADER_BYTES) {
            readIntLe(bytes, 12)
        } else {
            UNKNOWN_COLORSPACE
        }
        val encoding = encodingFor(format) ?: return null
        if (!isPlausibleDimensions(width, height)) return null

        val pixelBytes = width.toLong() * height.toLong() * encoding.bytesPerPixel
        if (pixelBytes > Int.MAX_VALUE) return null
        val expectedPayload = pixelBytes.toInt()
        val actualPayload = bytes.size - headerBytes
        if (requireExactPayload) {
            if (actualPayload != expectedPayload) return null
        } else if (actualPayload < expectedPayload) {
            return null
        }

        return FrameSpec(
            width = width,
            height = height,
            pixelFormat = format,
            colorspace = colorspace,
            headerBytes = headerBytes,
            bytesPerPixel = encoding.bytesPerPixel,
            bitmapEncoding = encoding.bitmapEncoding
        )
    }

    private fun isPlausibleDimensions(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) return false
        return width.toLong() * height.toLong() <= MAX_PIXELS
    }

    private fun encodingFor(format: Int): PixelEncoding? = when (format) {
        PixelFormat.RGBA_8888,
        PixelFormat.RGBX_8888 -> PixelEncoding(4, BitmapEncoding.ARGB_8888_DIRECT)
        PixelFormat.RGB_565 -> PixelEncoding(2, BitmapEncoding.RGB_565_DIRECT)
        PixelFormat.RGB_888 -> PixelEncoding(3, BitmapEncoding.RGB_888_TO_ARGB_8888)
        else -> null
    }

    private data class PixelEncoding(
        val bytesPerPixel: Int,
        val bitmapEncoding: BitmapEncoding
    )

    private fun decodeRgb888(spec: FrameSpec, bytes: ByteArray, offset: Int): Bitmap {
        val pixels = IntArray(spec.width * spec.height)
        var source = offset
        for (i in pixels.indices) {
            val r = bytes[source++].toInt() and 0xff
            val g = bytes[source++].toInt() and 0xff
            val b = bytes[source++].toInt() and 0xff
            pixels[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, spec.width, spec.height, Bitmap.Config.ARGB_8888)
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
