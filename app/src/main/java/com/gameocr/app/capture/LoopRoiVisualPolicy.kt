package com.gameocr.app.capture

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

internal data class LoopRoiVisualFingerprint(
    val contextId: Int,
    val roi: LoopTextRect,
    val luminance: ByteArray,
    val edges: ByteArray,
)

internal object LoopRoiVisualFingerprintFactory {
    private const val SAMPLE_WIDTH = 96
    private const val SAMPLE_HEIGHT = 48

    fun create(
        bitmap: Bitmap,
        roi: LoopTextRect,
        contextId: Int,
    ): LoopRoiVisualFingerprint? {
        if (roi.left < 0 || roi.top < 0 || roi.right > bitmap.width || roi.bottom > bitmap.height) {
            return null
        }
        val androidRect = Rect(roi.left, roi.top, roi.right, roi.bottom)
        if (!androidRect.intersect(0, 0, bitmap.width, bitmap.height) ||
            androidRect.width() <= 0 || androidRect.height() <= 0
        ) {
            return null
        }
        val cropped = Bitmap.createBitmap(
            bitmap,
            androidRect.left,
            androidRect.top,
            androidRect.width(),
            androidRect.height(),
        )
        val scaled = Bitmap.createScaledBitmap(cropped, SAMPLE_WIDTH, SAMPLE_HEIGHT, true)
        return try {
            val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
            scaled.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
            val luminance = ByteArray(pixels.size) { index ->
                val color = pixels[index]
                val red = color shr 16 and 0xFF
                val green = color shr 8 and 0xFF
                val blue = color and 0xFF
                ((77 * red + 150 * green + 29 * blue) shr 8).toByte()
            }
            LoopRoiVisualFingerprint(
                contextId = contextId,
                roi = roi,
                luminance = luminance,
                edges = edgeSample(luminance, SAMPLE_WIDTH, SAMPLE_HEIGHT),
            )
        } finally {
            if (scaled !== cropped) scaled.recycle()
            cropped.recycle()
        }
    }

    private fun edgeSample(luminance: ByteArray, width: Int, height: Int): ByteArray =
        ByteArray(luminance.size) { index ->
            val x = index % width
            val y = index / width
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                0
            } else {
                val horizontal = abs(unsigned(luminance[index + 1]) - unsigned(luminance[index - 1]))
                val vertical = abs(unsigned(luminance[index + width]) - unsigned(luminance[index - width]))
                ((horizontal + vertical) / 2).coerceAtMost(255).toByte()
            }
        }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF
}

internal object LoopRoiVisualPolicy {
    private const val LUMINANCE_WEIGHT = 0.35f
    private const val EDGE_WEIGHT = 0.65f
    private const val EDGE_CHANGE_THRESHOLD = 24
    private const val LOCAL_EDGE_CHANGE_AMPLIFICATION = 12f

    fun similarity(
        previous: LoopRoiVisualFingerprint,
        current: LoopRoiVisualFingerprint,
    ): Float {
        if (previous.contextId != current.contextId || previous.roi != current.roi) return 0f
        val luminanceSimilarity = sampleSimilarity(previous.luminance, current.luminance)
        val edgeSimilarity = minOf(
            sampleSimilarity(previous.edges, current.edges),
            localizedEdgeSimilarity(previous.edges, current.edges),
        )
        return (luminanceSimilarity * LUMINANCE_WEIGHT + edgeSimilarity * EDGE_WEIGHT)
            .coerceIn(0f, 1f)
    }

    private fun sampleSimilarity(previous: ByteArray, current: ByteArray): Float =
        LoopFrameChangePolicy.similarity(previous, current)

    private fun localizedEdgeSimilarity(previous: ByteArray, current: ByteArray): Float {
        if (previous.isEmpty() || previous.size != current.size) return 0f
        val changed = previous.indices.count { index ->
            abs((previous[index].toInt() and 0xFF) - (current[index].toInt() and 0xFF)) >=
                EDGE_CHANGE_THRESHOLD
        }
        val changedRatio = changed.toFloat() / previous.size
        return (1f - changedRatio * LOCAL_EDGE_CHANGE_AMPLIFICATION).coerceIn(0f, 1f)
    }
}
