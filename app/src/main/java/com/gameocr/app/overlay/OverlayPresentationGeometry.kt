package com.gameocr.app.overlay

/** Exact view-local <-> capture geometry for a static quarter-turn presentation. */
internal object OverlayPresentationGeometry {
    data class Layout(val left: Int, val top: Int, val width: Int, val height: Int)

    /** Anchor the zero pivot to the matching capture corner; never clamp a rotated center. */
    fun layout(bounds: OverlayIntRect, clockwiseDegrees: Int): Layout {
        val angle = normalize(clockwiseDegrees)
        val quarterTurn = angle == 90 || angle == 270
        return Layout(
            left = if (angle == 90 || angle == 180) bounds.right else bounds.left,
            top = if (angle == 180 || angle == 270) bounds.bottom else bounds.top,
            width = if (quarterTurn) bounds.height else bounds.width,
            height = if (quarterTurn) bounds.width else bounds.height,
        )
    }

    /** Drawable coordinates must undo the view turn; the view applies it once when drawing. */
    fun captureLocalRectsToView(
        rects: List<OverlayIntRect>,
        captureWidth: Int,
        captureHeight: Int,
        clockwiseDegrees: Int,
    ): List<OverlayIntRect> = when (normalize(clockwiseDegrees)) {
        90 -> rects.map { r -> OverlayIntRect(r.top, captureWidth - r.right, r.bottom, captureWidth - r.left) }
        180 -> rects.map { r -> OverlayIntRect(captureWidth - r.right, captureHeight - r.bottom, captureWidth - r.left, captureHeight - r.top) }
        270 -> rects.map { r -> OverlayIntRect(captureHeight - r.bottom, r.left, captureHeight - r.top, r.right) }
        else -> rects
    }

    private fun normalize(angle: Int): Int = (((angle % 360) + 360) % 360).also {
        require(it % 90 == 0) { "Presentation must be a right-angle turn" }
    }
}
