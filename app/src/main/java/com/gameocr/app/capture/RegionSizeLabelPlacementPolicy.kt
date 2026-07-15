package com.gameocr.app.capture

internal data class RegionSizeLabelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun intersects(other: RegionSizeLabelRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

/** Places the size label outside the selected content, or hides it when no safe slot exists. */
internal object RegionSizeLabelPlacementPolicy {
    fun place(
        selection: RegionSizeLabelRect,
        viewportWidth: Int,
        viewportHeight: Int,
        labelWidth: Int,
        labelHeight: Int,
        gap: Int,
        margin: Int,
    ): RegionSizeLabelRect? {
        if (viewportWidth <= 0 || viewportHeight <= 0 || labelWidth <= 0 || labelHeight <= 0) {
            return null
        }
        val minLeft = margin
        val maxLeft = viewportWidth - margin - labelWidth
        val minTop = margin
        val maxTop = viewportHeight - margin - labelHeight
        if (maxLeft < minLeft || maxTop < minTop) return null

        fun rect(left: Int, top: Int): RegionSizeLabelRect =
            RegionSizeLabelRect(left, top, left + labelWidth, top + labelHeight)

        val centeredLeft = ((selection.left + selection.right - labelWidth) / 2)
            .coerceIn(minLeft, maxLeft)
        val centeredTop = ((selection.top + selection.bottom - labelHeight) / 2)
            .coerceIn(minTop, maxTop)
        val candidates = listOf(
            rect(centeredLeft, selection.top - gap - labelHeight),
            rect(centeredLeft, selection.bottom + gap),
            rect(selection.right + gap, centeredTop),
            rect(selection.left - gap - labelWidth, centeredTop),
        )
        return candidates.firstOrNull { candidate ->
            candidate.left >= minLeft && candidate.right <= viewportWidth - margin &&
                candidate.top >= minTop && candidate.bottom <= viewportHeight - margin &&
                !candidate.intersects(selection)
        }
    }
}
