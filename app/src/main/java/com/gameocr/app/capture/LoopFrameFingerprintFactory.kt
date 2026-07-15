package com.gameocr.app.capture

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

internal object LoopFrameFingerprintFactory {
    private const val SAMPLE_SIZE = 96
    private const val HASH_STRIP_ROWS = 32
    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    fun create(
        bitmap: Bitmap,
        contextId: Int,
        excludedRect: Rect? = null,
    ): LoopFrameFingerprint {
        val mask = excludedRect
            ?.let(::Rect)
            ?.takeIf { it.intersect(0, 0, bitmap.width, bitmap.height) }
        return LoopFrameFingerprint(
            width = bitmap.width,
            height = bitmap.height,
            contextId = contextId,
            exactHash = exactPixelHash(bitmap, mask),
            luminanceSample = luminanceSample(bitmap, mask),
        )
    }

    private fun exactPixelHash(bitmap: Bitmap, mask: Rect?): Long {
        var hash = FNV_OFFSET_BASIS
        hash = (hash xor bitmap.width.toLong()) * FNV_PRIME
        hash = (hash xor bitmap.height.toLong()) * FNV_PRIME
        val rowsPerStrip = min(HASH_STRIP_ROWS, bitmap.height).coerceAtLeast(1)
        val pixels = IntArray(bitmap.width * rowsPerStrip)
        var top = 0
        while (top < bitmap.height) {
            val rows = min(rowsPerStrip, bitmap.height - top)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, rows)
            var offset = 0
            repeat(rows) { row ->
                val y = top + row
                repeat(bitmap.width) { x ->
                    val pixel = if (mask != null && x >= mask.left && x < mask.right &&
                        y >= mask.top && y < mask.bottom
                    ) {
                        0
                    } else {
                        pixels[offset]
                    }
                    hash = (hash xor (pixel.toLong() and 0xFFFFFFFFL)) * FNV_PRIME
                    offset++
                }
            }
            top += rows
        }
        return hash
    }

    private fun luminanceSample(bitmap: Bitmap, mask: Rect?): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        return try {
            val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            val sampleMask = mask?.let { sourceMask ->
                Rect(
                    floor(sourceMask.left * SAMPLE_SIZE.toDouble() / bitmap.width).toInt(),
                    floor(sourceMask.top * SAMPLE_SIZE.toDouble() / bitmap.height).toInt(),
                    ceil(sourceMask.right * SAMPLE_SIZE.toDouble() / bitmap.width).toInt(),
                    ceil(sourceMask.bottom * SAMPLE_SIZE.toDouble() / bitmap.height).toInt(),
                )
            }
            ByteArray(pixels.size) { index ->
                val x = index % SAMPLE_SIZE
                val y = index / SAMPLE_SIZE
                if (sampleMask != null && x >= sampleMask.left && x < sampleMask.right &&
                    y >= sampleMask.top && y < sampleMask.bottom
                ) {
                    0
                } else {
                    val color = pixels[index]
                    val red = color shr 16 and 0xFF
                    val green = color shr 8 and 0xFF
                    val blue = color and 0xFF
                    ((77 * red + 150 * green + 29 * blue) shr 8).toByte()
                }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}
