package com.gameocr.app.capture

/** Keep the last drawable coordinate basis across transient zero-size layouts. */
internal class RegionPickerViewport {
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    fun resize(region: CaptureRegion?, newWidth: Int, newHeight: Int, minSidePx: Int): CaptureRegion? {
        if (newWidth <= 0 || newHeight <= 0) return region
        val selection = RegionPickerViewportPolicy.resizeSelection(
            region, width, height, newWidth, newHeight, minSidePx,
        )
        width = newWidth
        height = newHeight
        return selection
    }
}

/** Keep an uncommitted selection in the same relative screen area after the viewport changes. */
internal object RegionPickerViewportPolicy {
    fun resizeSelection(
        region: CaptureRegion?,
        oldWidth: Int,
        oldHeight: Int,
        newWidth: Int,
        newHeight: Int,
        minSidePx: Int,
    ): CaptureRegion? {
        if (region == null || newWidth <= 0 || newHeight <= 0 ||
            region.right <= region.left || region.bottom <= region.top) return null

        fun scaled(value: Int, oldExtent: Int, newExtent: Int): Int =
            if (oldExtent > 0) (value.toDouble() * newExtent / oldExtent).toInt() else value

        val minWidth = minSidePx.coerceIn(1, newWidth)
        val minHeight = minSidePx.coerceIn(1, newHeight)
        val left = scaled(region.left, oldWidth, newWidth).coerceIn(0, newWidth - minWidth)
        val top = scaled(region.top, oldHeight, newHeight).coerceIn(0, newHeight - minHeight)
        return CaptureRegion(
            left = left,
            top = top,
            right = scaled(region.right, oldWidth, newWidth).coerceIn(left + minWidth, newWidth),
            bottom = scaled(region.bottom, oldHeight, newHeight).coerceIn(top + minHeight, newHeight),
        )
    }
}
