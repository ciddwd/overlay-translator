package com.gameocr.app.capture

import kotlinx.serialization.Serializable

const val DEFAULT_CAPTURE_REGION_BORDER_COLOR: Int = 0xFF1976D2.toInt()
const val DEFAULT_CAPTURE_REGION_BORDER_WIDTH_DP: Int = 2
const val MIN_CAPTURE_REGION_BORDER_WIDTH_DP: Int = 1
const val MAX_CAPTURE_REGION_BORDER_WIDTH_DP: Int = 6

@Serializable
enum class CaptureRegionBorderStyle {
    SOLID,
    DASHED,
    DOTTED,
}

internal data class CaptureRegionBorderRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal fun shouldShowCaptureRegionBorder(
    enabled: Boolean,
    region: CaptureRegion?,
): Boolean = enabled && region?.isValid() == true

internal fun shouldHideCaptureRegionBorder(
    hiddenForCapture: Boolean,
    hiddenForEditor: Boolean,
    hiddenForWordSelect: Boolean,
    autoHideOnCapture: Boolean = true,
): Boolean = (autoHideOnCapture && hiddenForCapture) || hiddenForEditor || hiddenForWordSelect

internal fun normalizedCaptureRegionBorderWidthDp(widthDp: Int): Int =
    widthDp.coerceIn(MIN_CAPTURE_REGION_BORDER_WIDTH_DP, MAX_CAPTURE_REGION_BORDER_WIDTH_DP)

/**
 * Moves a valid capture region without changing its size and keeps it fully inside the display.
 * Returns null when either the region or display geometry cannot support a drag operation.
 */
internal fun movedCaptureRegion(
    region: CaptureRegion,
    deltaX: Int,
    deltaY: Int,
    screenWidth: Int,
    screenHeight: Int,
): CaptureRegion? {
    if (!region.isValid() || screenWidth <= 0 || screenHeight <= 0) return null
    if (region.width > screenWidth || region.height > screenHeight) return null

    val left = (region.left.toLong() + deltaX.toLong())
        .coerceIn(0L, (screenWidth - region.width).toLong())
        .toInt()
    val top = (region.top.toLong() + deltaY.toLong())
        .coerceIn(0L, (screenHeight - region.height).toLong())
        .toInt()
    return CaptureRegion(
        left = left,
        top = top,
        right = left + region.width,
        bottom = top + region.height,
    )
}

internal enum class CaptureRegionResizeCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

/** Resizes one corner while keeping the opposite corner fixed. */
internal fun resizedCaptureRegion(
    region: CaptureRegion,
    corner: CaptureRegionResizeCorner,
    deltaX: Int,
    deltaY: Int,
    screenWidth: Int,
    screenHeight: Int,
    minSidePx: Int,
): CaptureRegion? {
    if (!region.isValid() || screenWidth <= 0 || screenHeight <= 0) return null
    val minSide = minSidePx.coerceAtLeast(9)
    if (minSide > screenWidth || minSide > screenHeight) return null

    var left = region.left.coerceIn(0, screenWidth)
    var top = region.top.coerceIn(0, screenHeight)
    var right = region.right.coerceIn(0, screenWidth)
    var bottom = region.bottom.coerceIn(0, screenHeight)
    if (right - left < minSide || bottom - top < minSide) return null

    when (corner) {
        CaptureRegionResizeCorner.TOP_LEFT -> {
            left = (region.left.toLong() + deltaX.toLong())
                .coerceIn(0L, (right - minSide).toLong()).toInt()
            top = (region.top.toLong() + deltaY.toLong())
                .coerceIn(0L, (bottom - minSide).toLong()).toInt()
        }
        CaptureRegionResizeCorner.TOP_RIGHT -> {
            right = (region.right.toLong() + deltaX.toLong())
                .coerceIn((left + minSide).toLong(), screenWidth.toLong()).toInt()
            top = (region.top.toLong() + deltaY.toLong())
                .coerceIn(0L, (bottom - minSide).toLong()).toInt()
        }
        CaptureRegionResizeCorner.BOTTOM_LEFT -> {
            left = (region.left.toLong() + deltaX.toLong())
                .coerceIn(0L, (right - minSide).toLong()).toInt()
            bottom = (region.bottom.toLong() + deltaY.toLong())
                .coerceIn((top + minSide).toLong(), screenHeight.toLong()).toInt()
        }
        CaptureRegionResizeCorner.BOTTOM_RIGHT -> {
            right = (region.right.toLong() + deltaX.toLong())
                .coerceIn((left + minSide).toLong(), screenWidth.toLong()).toInt()
            bottom = (region.bottom.toLong() + deltaY.toLong())
                .coerceIn((top + minSide).toLong(), screenHeight.toLong()).toInt()
        }
    }
    return CaptureRegion(left, top, right, bottom)
}

internal fun captureRegionBorderRect(
    region: CaptureRegion,
    viewportWidth: Int,
    viewportHeight: Int,
    strokeWidthPx: Float,
): CaptureRegionBorderRect? {
    if (!region.isValid() || viewportWidth <= 0 || viewportHeight <= 0) return null
    val halfStroke = (strokeWidthPx.coerceAtLeast(1f) / 2f)
    if (viewportWidth <= halfStroke * 2f || viewportHeight <= halfStroke * 2f) return null
    val left = region.left.toFloat().coerceIn(halfStroke, viewportWidth - halfStroke)
    val top = region.top.toFloat().coerceIn(halfStroke, viewportHeight - halfStroke)
    val right = region.right.toFloat().coerceIn(halfStroke, viewportWidth - halfStroke)
    val bottom = region.bottom.toFloat().coerceIn(halfStroke, viewportHeight - halfStroke)
    return if (right > left && bottom > top) {
        CaptureRegionBorderRect(left, top, right, bottom)
    } else {
        null
    }
}
