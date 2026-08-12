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
    renderMode: RenderMode,
    isFloatingWindowShown: Boolean,
    autoHideWhenObstructing: Boolean,
    windowBounds: OverlayCaptureRect?,
    captureRegion: CaptureRegion?,
): FloatingWindowCaptureAction {
    if (!isFloatingWindowShown) return FloatingWindowCaptureAction.NONE
    if (!floatingWindowOverlapsCaptureRegion(windowBounds, captureRegion)) {
        return FloatingWindowCaptureAction.NONE
    }
    if (windowBounds == null || windowBounds.isEmpty) {
        return FloatingWindowCaptureAction.HIDE_TEMPORARILY
    }
    return if (renderMode != RenderMode.FLOATING_WINDOW || autoHideWhenObstructing) {
        FloatingWindowCaptureAction.HIDE_TEMPORARILY
    } else {
        FloatingWindowCaptureAction.PRESERVE_AND_MASK
    }
}

internal fun floatingWindowOverlapsCaptureRegion(
    windowBounds: OverlayCaptureRect?,
    captureRegion: CaptureRegion?,
): Boolean {
    if (captureRegion == null || !captureRegion.isValid()) return true
    // A visible window without measurable bounds must be treated as obstructing; otherwise its
    // contents can leak into OCR with no safe rectangle to mask.
    if (windowBounds == null || windowBounds.isEmpty) return true
    return windowBounds.left < captureRegion.right &&
        windowBounds.right > captureRegion.left &&
        windowBounds.top < captureRegion.bottom &&
        windowBounds.bottom > captureRegion.top
}

internal fun shouldHideFloatingButtonForCapture(
    loopMode: Boolean,
    isFloatingButtonShown: Boolean,
): Boolean = loopMode && isFloatingButtonShown

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
