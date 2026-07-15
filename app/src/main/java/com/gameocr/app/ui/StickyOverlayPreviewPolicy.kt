package com.gameocr.app.ui

internal object StickyOverlayPreviewPolicy {
    fun shouldStick(
        previewTopInWindow: Float,
        sectionBottomInWindow: Float,
        viewportTopInWindow: Float,
        previewHeightPx: Int,
    ): Boolean {
        if (!previewTopInWindow.isFinite() || !sectionBottomInWindow.isFinite() ||
            !viewportTopInWindow.isFinite() || previewHeightPx <= 0
        ) {
            return false
        }
        return previewTopInWindow <= viewportTopInWindow &&
            sectionBottomInWindow > viewportTopInWindow + previewHeightPx
    }
}
