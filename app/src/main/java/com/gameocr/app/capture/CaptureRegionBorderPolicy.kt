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

internal fun normalizedCaptureRegionBorderWidthDp(widthDp: Int): Int =
    widthDp.coerceIn(MIN_CAPTURE_REGION_BORDER_WIDTH_DP, MAX_CAPTURE_REGION_BORDER_WIDTH_DP)

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
