package com.gameocr.app.overlay

internal data class OverlayIntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = (right - left).coerceAtLeast(1)

    val height: Int
        get() = (bottom - top).coerceAtLeast(1)
}

internal data class VerticalOverlaySlot(
    val left: Int,
    val right: Int
) {
    val width: Int
        get() = (right - left).coerceAtLeast(1)
}

internal fun verticalOverlaySlot(
    rect: OverlayIntRect,
    allRects: List<OverlayIntRect>,
    screenWidth: Int,
    rightToLeft: Boolean,
    minGapPx: Int,
    minReadableWidthPx: Int = 0
): VerticalOverlaySlot {
    val gap = minGapPx.coerceAtLeast(0)
    val safeScreenWidth = screenWidth.coerceAtLeast(1)
    val minReadableWidth = minReadableWidthPx.coerceIn(0, safeScreenWidth)
    val maxSlotWidth = maxOf(
        rect.width + maxOf(rect.width / 4, gap * 2, 1),
        minReadableWidth
    )
    val leftBoundary = nearestLeftBoundary(rect, allRects, gap)
    val rightBoundary = nearestRightBoundary(rect, allRects, safeScreenWidth, gap)
    return if (rightToLeft) {
        val right = rect.right.coerceIn(1, safeScreenWidth)
        val maxWidthLeft = right - maxSlotWidth
        val readableLeft = right - minReadableWidth
        val left = minOf(maxOf(leftBoundary, maxWidthLeft), readableLeft)
            .coerceIn(0, right - 1)
        if (left == 0 && right - left < maxSlotWidth && right < safeScreenWidth) {
            val expandedRight = minOf(rightBoundary, maxSlotWidth, safeScreenWidth)
                .coerceAtLeast(right)
            return VerticalOverlaySlot(left = 0, right = expandedRight)
        }
        VerticalOverlaySlot(left = left, right = right)
    } else {
        val left = rect.left.coerceIn(0, safeScreenWidth - 1)
        val maxWidthRight = left + maxSlotWidth
        val readableRight = left + minReadableWidth
        val right = maxOf(minOf(rightBoundary, maxWidthRight), readableRight)
            .coerceIn(left + 1, safeScreenWidth)
        if (right == safeScreenWidth && right - left < maxSlotWidth && left > 0) {
            val expandedLeft = maxOf(leftBoundary, safeScreenWidth - maxSlotWidth, 0)
                .coerceAtMost(left)
            return VerticalOverlaySlot(left = expandedLeft, right = safeScreenWidth)
        }
        VerticalOverlaySlot(left = left, right = right)
    }
}

/**
 * Expands a final adaptive vertical viewport without crossing a neighbouring OCR block.
 * Japanese vertical text grows toward the left first; LTR vertical text grows toward the right.
 * At a screen edge the remaining width is borrowed from the opposite side.
 */
internal fun adaptiveVerticalOverflowSlot(
    rect: OverlayIntRect,
    allRects: List<OverlayIntRect>,
    screenWidth: Int,
    rightToLeft: Boolean,
    minGapPx: Int,
    requiredWidthPx: Int,
): VerticalOverlaySlot {
    val safeScreenWidth = screenWidth.coerceAtLeast(1)
    val gap = minGapPx.coerceAtLeast(0)
    val requiredWidth = maxOf(rect.width, requiredWidthPx.coerceAtLeast(1))
        .coerceAtMost(safeScreenWidth)
    val leftBoundary = nearestLeftBoundary(rect, allRects, gap).coerceIn(0, safeScreenWidth - 1)
    val rightBoundary = nearestRightBoundary(rect, allRects, safeScreenWidth, gap)
        .coerceIn(leftBoundary + 1, safeScreenWidth)
    val originalLeft = rect.left.coerceIn(leftBoundary, rightBoundary - 1)
    val originalRight = rect.right.coerceIn(originalLeft + 1, rightBoundary)

    return if (rightToLeft) {
        var right = originalRight
        val left = (right - requiredWidth).coerceAtLeast(leftBoundary)
        if (right - left < requiredWidth) {
            right = (left + requiredWidth).coerceAtMost(rightBoundary)
        }
        VerticalOverlaySlot(left = left, right = right)
    } else {
        var left = originalLeft
        val right = (left + requiredWidth).coerceAtMost(rightBoundary)
        if (right - left < requiredWidth) {
            left = (right - requiredWidth).coerceAtLeast(leftBoundary)
        }
        VerticalOverlaySlot(left = left, right = right)
    }
}

private fun nearestLeftBoundary(
    rect: OverlayIntRect,
    allRects: List<OverlayIntRect>,
    gap: Int
): Int =
    allRects.asSequence()
        .filter { it != rect }
        .filter { it.right <= rect.left }
        .filter { verticalOverlap(it, rect) > 0 }
        .maxOfOrNull { it.right + gap }
        ?: 0

private fun nearestRightBoundary(
    rect: OverlayIntRect,
    allRects: List<OverlayIntRect>,
    screenWidth: Int,
    gap: Int
): Int =
    allRects.asSequence()
        .filter { it != rect }
        .filter { it.left >= rect.right }
        .filter { verticalOverlap(it, rect) > 0 }
        .minOfOrNull { it.left - gap }
        ?: screenWidth

private fun verticalOverlap(a: OverlayIntRect, b: OverlayIntRect): Int =
    (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
