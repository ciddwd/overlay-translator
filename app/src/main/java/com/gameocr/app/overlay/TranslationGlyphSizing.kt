package com.gameocr.app.overlay

import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil

internal const val TRANSLATION_TARGET_GLYPH_RATIO = 1f
internal const val TRANSLATION_MIN_GLYPH_RATIO = 0.85f

internal data class TranslationGlyphSizeRange(val minPx: Int, val targetPx: Int)

internal data class TranslationViewport(val topPx: Int, val heightPx: Int)

/** Expand right first, then shift left if necessary, without covering another text block. */
internal fun translationHorizontalViewport(
    source: OverlayIntRect,
    others: List<OverlayIntRect>,
    screenWidthPx: Int,
    requiredWidthPx: Int,
    gapPx: Int,
): OverlayIntRect {
    val screen = screenWidthPx.coerceAtLeast(1)
    val left = source.left.coerceIn(0, screen - 1)
    val right = source.right.coerceIn(left + 1, screen)
    val neighbors = others.filter { it.bottom > source.top && it.top < source.bottom }
    if (neighbors.any { it.left < right && it.right > left }) {
        return OverlayIntRect(left, source.top, right, source.bottom)
    }
    val lower = neighbors.filter { it.right <= left }.maxOfOrNull { it.right + gapPx.coerceAtLeast(0) }
        ?.coerceIn(0, left) ?: 0
    val upper = neighbors.filter { it.left >= right }.minOfOrNull { it.left - gapPx.coerceAtLeast(0) }
        ?.coerceIn(right, screen) ?: screen
    val width = maxOf(right - left, requiredWidthPx, 1).coerceAtMost(upper - lower)
    val resolvedLeft = left.coerceIn(lower, upper - width)
    return OverlayIntRect(resolvedLeft, source.top, resolvedLeft + width, source.bottom)
}

internal fun translationWrapLimitedMaxFontPx(
    range: TranslationGlyphSizeRange,
    sourceLineBudget: Int,
    lineCountAt: (Int) -> Int,
): Int = adaptiveLargestFittingTextSizePx(range.minPx, range.targetPx) { candidate ->
    lineCountAt(candidate) <= sourceLineBudget.coerceAtLeast(1)
}.sizePx

/** Reserve the target layout first; use all available space before allowing a smaller font. */
internal fun translationTargetViewport(
    sourceTopPx: Int,
    sourceHeightPx: Int,
    targetLayoutHeightPx: Int,
    screenHeightPx: Int,
    lowerBoundaryPx: Int,
    upperBoundaryPx: Int = 0,
    gapPx: Int = 0,
): TranslationViewport {
    val screen = screenHeightPx.coerceAtLeast(1)
    val sourceTop = sourceTopPx.coerceIn(0, screen - 1)
    val sourceBottom = (sourceTop.toLong() + sourceHeightPx.coerceAtLeast(1)).coerceAtMost(screen.toLong()).toInt()
    val top = upperBoundaryPx.coerceIn(0, sourceTop)
    // A preferred gap may consume free space, never the source's existing line height.
    val bottom = (if (lowerBoundaryPx < screen)
        maxOf(lowerBoundaryPx - gapPx.coerceAtLeast(0), minOf(sourceBottom, lowerBoundaryPx)) else screen)
        .coerceIn(top + 1, screen)
    val height = maxOf(sourceHeightPx, targetLayoutHeightPx, 1).coerceAtMost(bottom - top)
    return TranslationViewport(sourceTopPx.coerceIn(top, bottom - height), height)
}

internal fun translationSourceGlyphHeight(
    lineHeightsPx: List<Int>,
    fallbackPx: Int,
): Float = translationSourceGlyphHeight(lineHeightsPx, fallbackPx, 0)

internal fun translationSourceGlyphHeight(
    lineHeightsPx: List<Int>,
    fallbackPx: Int,
    sourceGlyphCount: Int,
    sourceLineLengthsPx: List<Int> = emptyList(),
    maximumHeightPx: Float = Float.POSITIVE_INFINITY,
): Float {
    val heights = lineHeightsPx.filter { it > 0 }.sorted()
    if (heights.isEmpty()) return minOf(fallbackPx.coerceAtLeast(1).toFloat(), maximumHeightPx.coerceAtLeast(1f))
    val middle = heights.size / 2
    val thickness = if (heights.size % 2 == 0) heights[middle - 1] / 2f + heights[middle] / 2f
        else heights[middle].toFloat()
    // A detector column includes margins and sometimes ruby; its width is not a glyph size.
    // The amount of source text along those columns supplies an independent size ceiling.
    val lengths = sourceLineLengthsPx.filter { it > 0 }
    val alongText = if (sourceGlyphCount > 0 && lengths.isNotEmpty()) {
        lengths.sumOf { it.toLong() }.toFloat() / sourceGlyphCount / 1.05f
    } else Float.POSITIVE_INFINITY
    return minOf(thickness, alongText, maximumHeightPx).coerceAtLeast(1f)
}

/** Render-only policy: converts visible source ink height to a font size, not an OCR geometry. */
internal fun translationGlyphSizeRange(
    sourceHeightPx: Float,
    referenceInkHeightPx: Float,
    referenceTextSizePx: Float = 100f,
    constrainToBounds: Boolean = false,
): TranslationGlyphSizeRange? {
    if (!sourceHeightPx.isFinite() || sourceHeightPx <= 0f ||
        !referenceInkHeightPx.isFinite() || referenceInkHeightPx <= 0f ||
        !referenceTextSizePx.isFinite() || referenceTextSizePx <= 0f) return null
    // Detection rectangles include line margins. Treat their height as a font-size ceiling;
    // compensating for a font's shorter ink bounds magnifies the translation and adds wraps.
    val target = sourceHeightPx * (if (constrainToBounds) 1.6f else TRANSLATION_TARGET_GLYPH_RATIO) *
        minOf(1f, referenceTextSizePx / referenceInkHeightPx)
    if (!target.isFinite() || target > Int.MAX_VALUE / 2f) return null
    val maxPx = ceil(target).toInt().coerceAtLeast(1)
    // Bounds affect available layout space, never the shared readable font-size floor.
    // Fixed text areas must fit the complete translation. A source-relative 85% floor
    // clips longer translations, while a source-size ceiling leaves shorter ones tiny.
    val minPx = ceil(if (constrainToBounds) sourceHeightPx * .35f
        else maxPx * TRANSLATION_MIN_GLYPH_RATIO).toInt().coerceIn(1, maxPx)
    return TranslationGlyphSizeRange(minPx, maxPx)
}

/** Measure the current translation with its actual typeface; punctuation alone is not a font sample. */
internal fun measureTranslationGlyphRange(
    paint: Paint,
    text: CharSequence,
    sourceHeightPx: Float,
    constrainToBounds: Boolean = false,
): TranslationGlyphSizeRange? {
    val sample = buildString {
        var index = 0
        var count = 0
        while (index < text.length && count < 128) {
            val codePoint = Character.codePointAt(text, index)
            if (Character.isLetterOrDigit(codePoint)) {
                appendCodePoint(codePoint)
                count++
            }
            index += Character.charCount(codePoint)
        }
    }.ifEmpty { "国Hg" }
    val probe = Paint(paint).apply { textSize = 100f }
    val bounds = Rect()
    probe.getTextBounds(sample, 0, sample.length, bounds)
    return translationGlyphSizeRange(
        sourceHeightPx, bounds.height().toFloat(), constrainToBounds = constrainToBounds,
    )
}
