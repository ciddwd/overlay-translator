package com.gameocr.app.service

import android.graphics.Bitmap
import java.util.Locale

internal data class BitmapFrameStats(
    val sampleCount: Int,
    val transparentCount: Int,
    val darkCount: Int,
    val minLuma: Int,
    val maxLuma: Int,
    val avgLuma: Float,
    val blankLike: Boolean
) {
    val transparentRatio: Float =
        if (sampleCount == 0) 0f else transparentCount.toFloat() / sampleCount

    val darkRatio: Float =
        if (opaqueSampleCount == 0) 0f else darkCount.toFloat() / opaqueSampleCount

    private val opaqueSampleCount: Int
        get() = sampleCount - transparentCount

    fun toDiagString(): String =
        "samples=$sampleCount transparent=${transparentRatio.toDiagRatio()} " +
            "dark=${darkRatio.toDiagRatio()} luma=${avgLuma.toDiagLuma()} " +
            "range=$minLuma..$maxLuma blankLike=$blankLike"
}

internal fun sampleBitmapFrameStats(bitmap: Bitmap, gridSize: Int = 24): BitmapFrameStats {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) {
        return classifyBitmapFrameSamples(emptyList())
    }

    val xCount = gridSize.coerceAtLeast(1).coerceAtMost(width)
    val yCount = gridSize.coerceAtLeast(1).coerceAtMost(height)
    val samples = ArrayList<Int>(xCount * yCount)
    for (yi in 0 until yCount) {
        val y = if (yCount == 1) 0 else yi * (height - 1) / (yCount - 1)
        for (xi in 0 until xCount) {
            val x = if (xCount == 1) 0 else xi * (width - 1) / (xCount - 1)
            samples += bitmap.getPixel(x, y)
        }
    }
    return classifyBitmapFrameSamples(samples)
}

internal fun classifyBitmapFrameSamples(argbSamples: List<Int>): BitmapFrameStats {
    if (argbSamples.isEmpty()) {
        return BitmapFrameStats(
            sampleCount = 0,
            transparentCount = 0,
            darkCount = 0,
            minLuma = 0,
            maxLuma = 0,
            avgLuma = 0f,
            blankLike = true
        )
    }

    var transparentCount = 0
    var darkCount = 0
    var minLuma = 255
    var maxLuma = 0
    var lumaSum = 0L

    argbSamples.forEach { color ->
        val alpha = color ushr 24 and 0xff
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        val luma = (red * 299 + green * 587 + blue * 114 + 500) / 1000

        if (alpha < 8) {
            transparentCount += 1
        } else if (luma <= 8) {
            darkCount += 1
        }
        minLuma = minOf(minLuma, luma)
        maxLuma = maxOf(maxLuma, luma)
        lumaSum += luma
    }

    val sampleCount = argbSamples.size
    val opaqueCount = sampleCount - transparentCount
    val transparentRatio = transparentCount.toFloat() / sampleCount
    val darkRatio = if (opaqueCount == 0) 0f else darkCount.toFloat() / opaqueCount
    val blankLike = transparentRatio >= 0.98f || darkRatio >= 0.98f

    return BitmapFrameStats(
        sampleCount = sampleCount,
        transparentCount = transparentCount,
        darkCount = darkCount,
        minLuma = minLuma,
        maxLuma = maxLuma,
        avgLuma = lumaSum.toFloat() / sampleCount,
        blankLike = blankLike
    )
}

private fun Float.toDiagRatio(): String =
    String.format(Locale.US, "%.3f", this)

private fun Float.toDiagLuma(): String =
    String.format(Locale.US, "%.1f", this)
