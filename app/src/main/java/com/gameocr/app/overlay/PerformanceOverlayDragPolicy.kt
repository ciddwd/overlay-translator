package com.gameocr.app.overlay

import kotlin.math.roundToInt

internal data class PerformanceOverlayPosition(
    val x: Int,
    val y: Int,
)

/** Keeps the small diagnostics window reachable while it is dragged or the display size changes. */
internal object PerformanceOverlayDragPolicy {
    fun resolve(
        startX: Int,
        startY: Int,
        deltaX: Float,
        deltaY: Float,
        overlayWidth: Int,
        overlayHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): PerformanceOverlayPosition {
        val maximumX = (screenWidth.coerceAtLeast(0) - overlayWidth.coerceAtLeast(0)).coerceAtLeast(0)
        val maximumY = (screenHeight.coerceAtLeast(0) - overlayHeight.coerceAtLeast(0)).coerceAtLeast(0)
        return PerformanceOverlayPosition(
            x = (startX + deltaX).roundToInt().coerceIn(0, maximumX),
            y = (startY + deltaY).roundToInt().coerceIn(0, maximumY),
        )
    }
}
