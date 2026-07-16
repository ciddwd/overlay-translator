package com.gameocr.app.overlay

import android.graphics.Bitmap
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.sourceBoxesOrBoundingBox
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class AdaptiveStyleFallbackReason {
    NONE,
    INVALID_REGION,
    TOO_FEW_SAMPLES,
    BUSY_BACKGROUND,
}

data class AdaptiveOverlayStyle(
    val backgroundColor: Int,
    val foregroundColor: Int,
    val maxTextSizeSp: Float,
    val confidence: Float,
    val fallbackReason: AdaptiveStyleFallbackReason,
) {
    val usedFallback: Boolean
        get() = fallbackReason != AdaptiveStyleFallbackReason.NONE
}

internal data class AdaptiveStyleSample(
    val edgeColors: IntArray,
    val boxWidthPx: Int,
    val boxHeightPx: Int,
    val sourceGlyphCount: Int,
    val scaledDensity: Float,
    val sourceLineThicknessPx: IntArray = IntArray(0),
)

internal const val ADAPTIVE_MIN_TEXT_SIZE_SP = 4

internal data class AdaptiveOverlaySize(val width: Int, val height: Int)

internal fun adaptiveOverlaySize(originalWidth: Int, originalHeight: Int): AdaptiveOverlaySize =
    AdaptiveOverlaySize(
        width = originalWidth.coerceAtLeast(1),
        height = originalHeight.coerceAtLeast(1),
    )

internal fun adaptiveEraseRects(
    block: OverlayIntRect,
    sourceBoxes: List<OverlayIntRect>,
): List<OverlayIntRect> {
    if (sourceBoxes.isEmpty()) {
        return listOf(OverlayIntRect(0, 0, block.width, block.height))
    }
    return sourceBoxes.mapNotNull { source ->
        val margin = (min(source.width, source.height) * 0.06f)
            .roundToInt()
            .coerceIn(2, 8)
        val left = (source.left - block.left - margin).coerceIn(0, block.width)
        val top = (source.top - block.top - margin).coerceIn(0, block.height)
        val right = (source.right - block.left + margin).coerceIn(0, block.width)
        val bottom = (source.bottom - block.top + margin).coerceIn(0, block.height)
        if (right <= left || bottom <= top) {
            null
        } else {
            OverlayIntRect(left, top, right, bottom)
        }
    }.ifEmpty {
        listOf(OverlayIntRect(0, 0, block.width, block.height))
    }
}

internal fun adaptiveAutoSizeMaxSp(
    maxTextSizeSp: Float,
    minTextSizeSp: Int = ADAPTIVE_MIN_TEXT_SIZE_SP,
    maxAllowedTextSizeSp: Int = 28,
): Int? {
    val resolvedMax = maxTextSizeSp.roundToInt().coerceIn(minTextSizeSp, maxAllowedTextSizeSp)
    return resolvedMax.takeIf { it > minTextSizeSp }
}

/** Pure decision policy kept separate from Bitmap sampling so it can be exhaustively unit tested. */
internal object AdaptiveOverlayStylePolicy {
    private const val MIN_EDGE_SAMPLES = 8
    private const val MIN_CONFIDENCE = 0.38f
    private const val MIN_REGION_PX = 8
    private const val SAFE_BACKGROUND = 0xFF000000.toInt()
    private const val LIGHT_TEXT = 0xFFFFFFFF.toInt()
    private const val DARK_TEXT = 0xFF000000.toInt()

    fun resolve(sample: AdaptiveStyleSample): AdaptiveOverlayStyle {
        val maxTextSizeSp = estimateMaxTextSizeSp(
            widthPx = sample.boxWidthPx,
            heightPx = sample.boxHeightPx,
            sourceGlyphCount = sample.sourceGlyphCount,
            scaledDensity = sample.scaledDensity,
            sourceLineThicknessPx = sample.sourceLineThicknessPx,
        )
        if (sample.boxWidthPx < MIN_REGION_PX || sample.boxHeightPx < MIN_REGION_PX) {
            return fallback(maxTextSizeSp, AdaptiveStyleFallbackReason.INVALID_REGION)
        }

        val colors = sample.edgeColors.filterOpaque()
        if (colors.size < MIN_EDGE_SAMPLES) {
            return fallback(maxTextSizeSp, AdaptiveStyleFallbackReason.TOO_FEW_SAMPLES)
        }

        val bucketCounts = IntArray(4096)
        var bestBucket = 0
        var bestCount = 0
        var lumaSum = 0.0
        colors.forEach { color ->
            val bucket = quantizedBucket(color)
            val count = ++bucketCounts[bucket]
            if (count > bestCount) {
                bestCount = count
                bestBucket = bucket
            }
            lumaSum += relativeLuminance(color)
        }

        val meanLuma = lumaSum / colors.size
        var lumaVariance = 0.0
        colors.forEach { color ->
            val delta = relativeLuminance(color) - meanLuma
            lumaVariance += delta * delta
        }
        val lumaStdDev = sqrt(lumaVariance / colors.size).toFloat()
        val dominance = bestCount.toFloat() / colors.size
        val confidence = (
            dominance * 0.65f +
                (1f - (lumaStdDev / 0.35f).coerceIn(0f, 1f)) * 0.35f
            ).coerceIn(0f, 1f)
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        var dominantCount = 0
        colors.forEach { color ->
            if (quantizedBucket(color) == bestBucket) {
                redSum += (color ushr 16 and 0xFF).toLong()
                greenSum += (color ushr 8 and 0xFF).toLong()
                blueSum += (color and 0xFF).toLong()
                dominantCount++
            }
        }
        if (dominantCount == 0) {
            return fallback(maxTextSizeSp, AdaptiveStyleFallbackReason.TOO_FEW_SAMPLES)
        }

        val dominantBackground = argb(
            alpha = 0xFF,
            red = (redSum / dominantCount).toInt(),
            green = (greenSum / dominantCount).toInt(),
            blue = (blueSum / dominantCount).toInt(),
        )
        return AdaptiveOverlayStyle(
            backgroundColor = dominantBackground,
            foregroundColor = readableForeground(dominantBackground),
            maxTextSizeSp = maxTextSizeSp,
            confidence = confidence,
            fallbackReason = if (confidence < MIN_CONFIDENCE) {
                AdaptiveStyleFallbackReason.BUSY_BACKGROUND
            } else {
                AdaptiveStyleFallbackReason.NONE
            },
        )
    }

    internal fun estimateMaxTextSizeSp(
        widthPx: Int,
        heightPx: Int,
        sourceGlyphCount: Int,
        scaledDensity: Float,
        sourceLineThicknessPx: IntArray = IntArray(0),
    ): Float {
        if (widthPx <= 0 || heightPx <= 0 || scaledDensity <= 0f) return 14f
        sourceLineThicknessPx
            .filter { it > 0 }
            .sorted()
            .takeIf { it.isNotEmpty() }
            ?.let { sizes ->
                val middle = sizes.size / 2
                val medianPx = if (sizes.size % 2 == 0) {
                    (sizes[middle - 1] + sizes[middle]) / 2f
                } else {
                    sizes[middle].toFloat()
                }
                return (medianPx / scaledDensity).coerceIn(ADAPTIVE_MIN_TEXT_SIZE_SP.toFloat(), 28f)
            }
        val glyphs = sourceGlyphCount.coerceAtLeast(1)
        val estimatedGlyphPx = sqrt(widthPx.toDouble() * heightPx / glyphs).toFloat()
        val minorAxisPx = min(widthPx, heightPx).toFloat()
        return (min(estimatedGlyphPx * 1.05f, minorAxisPx * 0.82f) / scaledDensity)
            .coerceIn(ADAPTIVE_MIN_TEXT_SIZE_SP.toFloat(), 28f)
    }

    internal fun contrastRatio(foreground: Int, background: Int): Double {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun readableForeground(background: Int): Int =
        if (contrastRatio(LIGHT_TEXT, background) >= contrastRatio(DARK_TEXT, background)) {
            LIGHT_TEXT
        } else {
            DARK_TEXT
        }

    private fun fallback(
        maxTextSizeSp: Float,
        reason: AdaptiveStyleFallbackReason,
        confidence: Float = 0f,
    ) = AdaptiveOverlayStyle(
        backgroundColor = SAFE_BACKGROUND,
        foregroundColor = LIGHT_TEXT,
        maxTextSizeSp = maxTextSizeSp,
        confidence = confidence,
        fallbackReason = reason,
    )

    private fun IntArray.filterOpaque(): IntArray {
        val result = IntArray(size)
        var count = 0
        forEach { color ->
            if (color ushr 24 >= 0x80) result[count++] = color
        }
        return result.copyOf(count)
    }

    private fun quantizedBucket(color: Int): Int =
        ((color ushr 20 and 0xF) shl 8) or
            ((color ushr 12 and 0xF) shl 4) or
            (color ushr 4 and 0xF)

    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val srgb = value / 255.0
            return if (srgb <= 0.04045) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }
        val red = channel(color ushr 16 and 0xFF)
        val green = channel(color ushr 8 and 0xFF)
        val blue = channel(color and 0xFF)
        return red * 0.2126 + green * 0.7152 + blue * 0.0722
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)
}

internal object AdaptiveOverlayStyleAnalyzer {
    private const val MAX_SAMPLE_GRID = 32

    fun analyze(
        bitmap: Bitmap,
        blocks: List<TextBlock>,
        scaledDensity: Float,
    ): List<AdaptiveOverlayStyle> = blocks.map { block ->
        val box = block.boundingBox
        val width = box.width().coerceAtLeast(0)
        val height = box.height().coerceAtLeast(0)
        val glyphCount = block.text.count { !it.isWhitespace() }
        AdaptiveOverlayStylePolicy.resolve(
            AdaptiveStyleSample(
                edgeColors = sampleInnerBorder(bitmap, box.left, box.top, box.right, box.bottom),
                boxWidthPx = width,
                boxHeightPx = height,
                sourceGlyphCount = glyphCount,
                scaledDensity = scaledDensity,
                sourceLineThicknessPx = block.sourceBoxesOrBoundingBox()
                    .map { sourceBox ->
                        min(sourceBox.width(), sourceBox.height()).coerceAtLeast(0)
                    }
                    .toIntArray(),
            )
        )
    }

    private fun sampleInnerBorder(
        bitmap: Bitmap,
        boxLeft: Int,
        boxTop: Int,
        boxRight: Int,
        boxBottom: Int,
    ): IntArray {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return IntArray(0)
        val boxWidth = boxRight - boxLeft
        val boxHeight = boxBottom - boxTop
        if (boxWidth <= 0 || boxHeight <= 0) return IntArray(0)

        val left = boxLeft.coerceIn(0, bitmap.width)
        val top = boxTop.coerceIn(0, bitmap.height)
        val right = boxRight.coerceIn(0, bitmap.width)
        val bottom = boxBottom.coerceIn(0, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return IntArray(0)

        val gridWidth = min(width, MAX_SAMPLE_GRID)
        val gridHeight = min(height, MAX_SAMPLE_GRID)
        val samples = IntArray(gridWidth * gridHeight)
        var count = 0
        for (gridY in 0 until gridHeight) {
            val y = top + ((gridY + 0.5f) * height / gridHeight).toInt().coerceIn(0, height - 1)
            for (gridX in 0 until gridWidth) {
                if (gridX != 0 && gridX != gridWidth - 1 && gridY != 0 && gridY != gridHeight - 1) continue
                val x = left + ((gridX + 0.5f) * width / gridWidth).toInt().coerceIn(0, width - 1)
                samples[count++] = bitmap.getPixel(x, y)
            }
        }
        return samples.copyOf(count)
    }
}
