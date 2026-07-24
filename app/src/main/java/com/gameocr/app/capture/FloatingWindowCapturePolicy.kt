package com.gameocr.app.capture

import com.gameocr.app.data.RenderMode
import kotlin.math.ceil
import kotlin.math.floor

internal enum class FloatingWindowCaptureAction {
    NONE,
    HIDE_TEMPORARILY,
    PRESERVE_AND_MASK,
}

internal fun floatingWindowCaptureAction(
    loopMode: Boolean,
    renderMode: RenderMode,
    isFloatingWindowShown: Boolean,
): FloatingWindowCaptureAction {
    if (!loopMode || !isFloatingWindowShown) return FloatingWindowCaptureAction.NONE
    return if (renderMode == RenderMode.FLOATING_WINDOW) {
        FloatingWindowCaptureAction.PRESERVE_AND_MASK
    } else {
        FloatingWindowCaptureAction.HIDE_TEMPORARILY
    }
}

internal data class OverlayCaptureRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val isEmpty: Boolean
        get() = right <= left || bottom <= top
}

internal fun mapOverlayBoundsToCapture(
    bounds: OverlayCaptureRect?,
    overlayWidth: Int,
    overlayHeight: Int,
    captureWidth: Int,
    captureHeight: Int,
): OverlayCaptureRect? {
    if (bounds == null || bounds.isEmpty) return null
    if (overlayWidth <= 0 || overlayHeight <= 0 || captureWidth <= 0 || captureHeight <= 0) {
        return null
    }

    val scaleX = captureWidth.toDouble() / overlayWidth
    val scaleY = captureHeight.toDouble() / overlayHeight
    val mapped = OverlayCaptureRect(
        left = floor(bounds.left * scaleX).toInt().coerceIn(0, captureWidth),
        top = floor(bounds.top * scaleY).toInt().coerceIn(0, captureHeight),
        right = ceil(bounds.right * scaleX).toInt().coerceIn(0, captureWidth),
        bottom = ceil(bounds.bottom * scaleY).toInt().coerceIn(0, captureHeight),
    )
    return mapped.takeUnless(OverlayCaptureRect::isEmpty)
}
