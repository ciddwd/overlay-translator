package com.gameocr.app.ocr

import com.gameocr.app.data.PaddleDetectionProfile
import kotlin.math.roundToInt

internal data class PaddleDetectionResizePlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetWidth: Int,
    val targetHeight: Int,
) {
    val scaleX: Float
        get() = sourceWidth.toFloat() / targetWidth

    val scaleY: Float
        get() = sourceHeight.toFloat() / targetHeight

    val inputPixels: Long
        get() = targetWidth.toLong() * targetHeight

    val resized: Boolean
        get() = sourceWidth != targetWidth || sourceHeight != targetHeight
}

internal data class PaddleDetectionOutputScale(
    val x: Float,
    val y: Float,
)

internal object PaddleDetectionSizing {
    private const val ALIGNMENT = 32

    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        profile: PaddleDetectionProfile,
    ): PaddleDetectionResizePlan {
        val safeWidth = sourceWidth.coerceAtLeast(1)
        val safeHeight = sourceHeight.coerceAtLeast(1)
        val longestSide = maxOf(safeWidth, safeHeight)
        val resizeRatio = minOf(1f, profile.maxSideLen.toFloat() / longestSide)
        val targetWidth = align((safeWidth * resizeRatio).roundToInt(), profile.maxSideLen)
        val targetHeight = align((safeHeight * resizeRatio).roundToInt(), profile.maxSideLen)
        return PaddleDetectionResizePlan(
            sourceWidth = safeWidth,
            sourceHeight = safeHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
        )
    }

    fun outputScale(
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): PaddleDetectionOutputScale = PaddleDetectionOutputScale(
        x = sourceWidth.coerceAtLeast(1).toFloat() / outputWidth.coerceAtLeast(1),
        y = sourceHeight.coerceAtLeast(1).toFloat() / outputHeight.coerceAtLeast(1),
    )

    private fun align(value: Int, maxSideLen: Int): Int {
        val rounded = ((value + ALIGNMENT / 2) / ALIGNMENT) * ALIGNMENT
        return rounded.coerceIn(ALIGNMENT, maxSideLen)
    }
}
