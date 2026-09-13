package com.gameocr.app.capture

import kotlinx.serialization.Serializable

/**
 * 屏幕坐标系下的截屏区域。null 表示整屏。
 * left/top/right/bottom 均为屏幕像素绝对坐标。
 */
@Serializable
data class CaptureRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun isValid(): Boolean = width > 8 && height > 8
}

internal data class CaptureRegionOrigin(
    val x: Int,
    val y: Int,
)

/**
 * Converts an absolute capture region to the screen-space origin used to render local OCR boxes.
 * Invalid regions are treated as full-screen, matching the capture crop behavior.
 */
internal fun captureRegionOrigin(region: CaptureRegion?): CaptureRegionOrigin =
    region
        ?.takeIf(CaptureRegion::isValid)
        ?.let { CaptureRegionOrigin(x = it.left, y = it.top) }
        ?: CaptureRegionOrigin(x = 0, y = 0)
