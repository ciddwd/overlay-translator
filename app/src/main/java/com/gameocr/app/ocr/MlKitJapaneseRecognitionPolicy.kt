package com.gameocr.app.ocr

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Japanese ML Kit input sizing based on Google's documented text-recognition guidance.
 *
 * The caller may already have applied a user-selected 2x upscale. This policy therefore maps
 * every result back to the caller's coordinate space instead of changing the public OCR contract.
 */
internal object MlKitJapaneseInputPolicy {
    private const val TARGET_SHORT_SIDE = 1_200
    private const val MAX_LONG_SIDE = 3_000
    private const val MAX_UPSCALE = 3f

    fun plan(width: Int, height: Int): MlKitInputScalePlan {
        require(width > 0 && height > 0)
        val shortSide = min(width, height).toFloat()
        val longSide = max(width, height).toFloat()
        val growForShortSide = TARGET_SHORT_SIDE / shortSide
        val shrinkForLongSide = MAX_LONG_SIDE / longSide
        val scale = when {
            longSide > MAX_LONG_SIDE -> shrinkForLongSide
            shortSide < TARGET_SHORT_SIDE -> min(growForShortSide, min(MAX_UPSCALE, shrinkForLongSide))
            else -> 1f
        }.coerceAtMost(1f.takeIf { longSide >= MAX_LONG_SIDE } ?: MAX_UPSCALE)

        val outputWidth = (width * scale).toInt().coerceAtLeast(1)
        val outputHeight = (height * scale).toInt().coerceAtLeast(1)
        return MlKitInputScalePlan(
            inputWidth = width,
            inputHeight = height,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
        )
    }
}

internal data class MlKitInputScalePlan(
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
) {
    val changed: Boolean
        get() = inputWidth != outputWidth || inputHeight != outputHeight

    val scaleX: Float
        get() = outputWidth.toFloat() / inputWidth

    val scaleY: Float
        get() = outputHeight.toFloat() / inputHeight

    fun mapRectToInput(rect: Rect): Rect = Rect(
        (rect.left / scaleX).toInt().coerceIn(0, inputWidth),
        (rect.top / scaleY).toInt().coerceIn(0, inputHeight),
        kotlin.math.ceil(rect.right / scaleX).toInt().coerceIn(0, inputWidth),
        kotlin.math.ceil(rect.bottom / scaleY).toInt().coerceIn(0, inputHeight),
    )
}

internal data class MlKitGeometryPoint(val x: Float, val y: Float)

internal enum class MlKitGeometryAxis {
    HORIZONTAL,
    VERTICAL,
    UNKNOWN,
}

internal object MlKitJapaneseGeometryPolicy {
    private const val AXIS_DOMINANCE_RATIO = 1.25f

    /**
     * Uses character/element centroids before the enclosing rectangle. A vertical Japanese line
     * can be wrapped by ML Kit in a wide block, so the outer rectangle alone is insufficient.
     */
    fun dominantAxis(points: List<MlKitGeometryPoint>): MlKitGeometryAxis {
        if (points.size < 2) return MlKitGeometryAxis.UNKNOWN
        val xSpread = points.maxOf { it.x } - points.minOf { it.x }
        val ySpread = points.maxOf { it.y } - points.minOf { it.y }
        return when {
            ySpread >= xSpread * AXIS_DOMINANCE_RATIO && ySpread > 0f -> MlKitGeometryAxis.VERTICAL
            xSpread >= ySpread * AXIS_DOMINANCE_RATIO && xSpread > 0f -> MlKitGeometryAxis.HORIZONTAL
            else -> MlKitGeometryAxis.UNKNOWN
        }
    }
}
