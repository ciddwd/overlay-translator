package com.gameocr.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class OverlayTextAlignment {
    START,
    CENTER,
    END
}

/** Typography and text effects shared by every translation display theme and render mode. */
@Serializable
data class OverlayTextStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val letterSpacingEm: Float = 0f,
    val lineSpacingMultiplier: Float = 1f,
    val alignment: OverlayTextAlignment = OverlayTextAlignment.START,
    val strokeEnabled: Boolean = false,
    val strokeWidthDp: Float = 1.5f,
    val strokeColor: Int = 0xFF000000.toInt(),
    val shadowEnabled: Boolean = false,
    val shadowRadiusDp: Float = 3f,
    val shadowOffsetXDp: Float = 1f,
    val shadowOffsetYDp: Float = 1f,
    val shadowColor: Int = 0xCC000000.toInt()
) {
    fun normalized(): OverlayTextStyle = copy(
        letterSpacingEm = letterSpacingEm.coerceIn(MIN_LETTER_SPACING_EM, MAX_LETTER_SPACING_EM),
        lineSpacingMultiplier = lineSpacingMultiplier.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING),
        strokeWidthDp = strokeWidthDp.coerceIn(MIN_STROKE_WIDTH_DP, MAX_STROKE_WIDTH_DP),
        shadowRadiusDp = shadowRadiusDp.coerceIn(MIN_SHADOW_RADIUS_DP, MAX_SHADOW_RADIUS_DP),
        shadowOffsetXDp = shadowOffsetXDp.coerceIn(MIN_SHADOW_OFFSET_DP, MAX_SHADOW_OFFSET_DP),
        shadowOffsetYDp = shadowOffsetYDp.coerceIn(MIN_SHADOW_OFFSET_DP, MAX_SHADOW_OFFSET_DP)
    )

    companion object {
        const val MIN_LETTER_SPACING_EM = 0f
        const val MAX_LETTER_SPACING_EM = 0.2f
        const val MIN_LINE_SPACING = 1f
        const val MAX_LINE_SPACING = 2f
        const val MIN_STROKE_WIDTH_DP = 0.5f
        const val MAX_STROKE_WIDTH_DP = 6f
        const val MIN_SHADOW_RADIUS_DP = 0f
        const val MAX_SHADOW_RADIUS_DP = 12f
        const val MIN_SHADOW_OFFSET_DP = -8f
        const val MAX_SHADOW_OFFSET_DP = 8f
    }
}
