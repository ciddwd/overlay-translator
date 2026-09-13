package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect

internal fun IntRect.overlapsProtectedSource(regions: List<IntRect>): Boolean = regions.any {
    left < it.right && right > it.left && top < it.bottom && bottom > it.top
}

/** Last-mile alpha protection: growth/repair heuristics must never erase rejected OCR regions. */
internal fun ShapeAwareBubblePatch.protectSourceRegions(regions: List<IntRect>): ShapeAwareBubblePatch {
    if (!bounds.overlapsProtectedSource(regions)) return this
    val output = pixels.copyOf()
    regions.forEach { region ->
        val left = maxOf(bounds.left, region.left)
        val right = minOf(bounds.right, region.right)
        val top = maxOf(bounds.top, region.top)
        val bottom = minOf(bounds.bottom, region.bottom)
        for (y in top until bottom) for (x in left until right) {
            output[(y - bounds.top) * bounds.width + x - bounds.left] = 0
        }
    }
    return copy(pixels = output)
}
