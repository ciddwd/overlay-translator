package com.gameocr.app.overlay

import kotlin.math.roundToInt

/** Screen-space erasure stays on the source ink, independent of translation reflow/scrolling. */
internal fun translationSourceEraseRects(
    block: OverlayIntRect,
    sourceBoxes: List<OverlayIntRect>,
    neighbors: List<OverlayIntRect>,
    screenWidth: Int,
    screenHeight: Int,
): List<OverlayIntRect> {
    if (screenWidth <= 0 || screenHeight <= 0) return emptyList()
    val sources = sourceBoxes.filter { it.right > it.left && it.bottom > it.top }
        .ifEmpty { listOf(block) }
    return sources.mapNotNull { source ->
        if (source.right <= source.left || source.bottom <= source.top) return@mapNotNull null
        val margin = (minOf(source.width, source.height) * 0.06f).roundToInt().coerceIn(2, 8)
        var left = (source.left - margin).coerceIn(0, screenWidth)
        var right = (source.right + margin).coerceIn(0, screenWidth)
        var top = (source.top - margin).coerceIn(0, screenHeight)
        var bottom = (source.bottom + margin).coerceIn(0, screenHeight)
        neighbors.forEach { neighbor ->
            if (neighbor.bottom > top && neighbor.top < bottom) {
                if (neighbor.right <= source.left) left = maxOf(left, neighbor.right)
                if (neighbor.left >= source.right) right = minOf(right, neighbor.left)
            }
            if (neighbor.right > left && neighbor.left < right) {
                if (neighbor.bottom <= source.top) top = maxOf(top, neighbor.bottom)
                if (neighbor.top >= source.bottom) bottom = minOf(bottom, neighbor.top)
            }
        }
        if (right <= left || bottom <= top) null else OverlayIntRect(left, top, right, bottom)
    }
}
