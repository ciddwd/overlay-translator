package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import com.gameocr.app.overlay.translationGlyphSizeRange
import com.gameocr.app.overlay.translationSourceGlyphHeight

/** A detected container may include controls and decoration outside its actual source text. */
internal object ShapeTranslationLayoutPolicy {
    data class Plan(val bounds: IntRect, val mask: BooleanArray, val maximumFontSizePx: Int,
                    val sourceFontSizePx: Float = maximumFontSizePx.toFloat()) {
        val minimumFontSizePx: Int
            get() = requireNotNull(translationGlyphSizeRange(sourceFontSizePx, 100f,
                constrainToBounds = true)).minPx.coerceAtMost(maximumFontSizePx)
    }

    fun plan(
        model: BubbleSegmentationPostprocessor.InstanceMask,
        sourceBoxes: List<IntRect>,
        orientation: ShapeAwareTextLayout.Orientation,
        maximumFontSizePx: Int,
        sourceGlyphCount: Int = 0,
    ): Plan? {
        if (model.width <= 0 || model.height <= 0 || model.pixels.size != model.width * model.height ||
            maximumFontSizePx < 1) return null
        val modelBounds = IntRect(model.left, model.top, model.left + model.width, model.top + model.height)
        val sources = sourceBoxes.filter { it.width > 0 && it.height > 0 &&
            it.right > modelBounds.left && it.left < modelBounds.right &&
            it.bottom > modelBounds.top && it.top < modelBounds.bottom }
        if (sources.isEmpty()) return null
        val bounds = IntRect(
            maxOf(modelBounds.left, sources.minOf { it.left }),
            maxOf(modelBounds.top, sources.minOf { it.top }),
            minOf(modelBounds.right, sources.maxOf { it.right }),
            minOf(modelBounds.bottom, sources.maxOf { it.bottom }),
        )
        val mask = BooleanArray(bounds.width * bounds.height) { index ->
            val x = bounds.left - model.left + index % bounds.width
            val y = bounds.top - model.top + index / bounds.width
            model.pixels[y * model.width + x]
        }
        if (mask.none { it }) return null
        val heights = sources.map {
            if (orientation == ShapeAwareTextLayout.Orientation.HORIZONTAL) it.height else it.width
        }
        val size = translationSourceGlyphHeight(heights, maximumFontSizePx,
            sourceGlyphCount = if (orientation == ShapeAwareTextLayout.Orientation.HORIZONTAL) 0 else sourceGlyphCount,
            sourceLineLengthsPx = sources.map { it.height }, maximumHeightPx = maximumFontSizePx.toFloat())
        return Plan(bounds, mask, minOf(maximumFontSizePx,
            requireNotNull(translationGlyphSizeRange(size,100f,constrainToBounds=true)).targetPx),size)
    }

    /** Join OCR fragments as continuous text, with word separators only where the script needs them. */
    fun joinTranslations(fragments: List<String>): String = ShapeAwareTextLayout.normalize(
        fragments.map(String::trim).filter(String::isNotEmpty).joinToString("\n"),
    )
}
