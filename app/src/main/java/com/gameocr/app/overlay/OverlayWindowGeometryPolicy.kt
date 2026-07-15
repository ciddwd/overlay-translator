package com.gameocr.app.overlay

internal data class OverlayWindowSize(
    val widthPx: Int,
    val heightPx: Int,
)

internal fun constrainOverlayWindowSize(
    requestedWidthPx: Int,
    requestedHeightPx: Int,
    screenWidthPx: Int,
    screenHeightPx: Int,
    minimumWidthPx: Int,
    minimumHeightPx: Int,
): OverlayWindowSize {
    val maxWidth = screenWidthPx.coerceAtLeast(1)
    val maxHeight = screenHeightPx.coerceAtLeast(1)
    val minWidth = minimumWidthPx.coerceIn(1, maxWidth)
    val minHeight = minimumHeightPx.coerceIn(1, maxHeight)
    return OverlayWindowSize(
        widthPx = requestedWidthPx.coerceIn(minWidth, maxWidth),
        heightPx = requestedHeightPx.coerceIn(minHeight, maxHeight),
    )
}
