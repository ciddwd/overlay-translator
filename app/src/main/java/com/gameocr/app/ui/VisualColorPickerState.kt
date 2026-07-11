package com.gameocr.app.ui

import android.graphics.Color
import kotlin.math.roundToInt

internal data class VisualColorPickerState(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float,
) {
    fun normalized(): VisualColorPickerState = copy(
        hue = hue.coerceIn(0f, MAX_HUE),
        saturation = saturation.coerceIn(0f, 1f),
        value = value.coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )

    fun toArgb(): Int {
        val normalized = normalized()
        return Color.HSVToColor(
            alphaByte(normalized.alpha),
            floatArrayOf(normalized.hue, normalized.saturation, normalized.value),
        )
    }

    companion object {
        const val MAX_HUE = 359.999f

        fun fromArgb(argb: Int): VisualColorPickerState {
            val hsv = FloatArray(3)
            Color.colorToHSV(argb, hsv)
            return VisualColorPickerState(
                hue = hsv[0],
                saturation = hsv[1],
                value = hsv[2],
                alpha = ((argb ushr 24) and 0xFF) / 255f,
            ).normalized()
        }
    }
}

internal data class SaturationValueSelection(
    val saturation: Float,
    val value: Float,
)

internal data class VisualColorDialogBounds(
    val widthDp: Float,
    val maxHeightDp: Float,
)

internal fun visualColorDialogBounds(
    availableWidthDp: Float,
    availableHeightDp: Float,
): VisualColorDialogBounds = VisualColorDialogBounds(
    widthDp = availableWidthDp.coerceIn(0f, 400f),
    maxHeightDp = availableHeightDp.coerceIn(0f, 560f),
)

internal fun saturationValueFromPosition(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
): SaturationValueSelection {
    if (width <= 0f || height <= 0f) return SaturationValueSelection(0f, 1f)
    return SaturationValueSelection(
        saturation = (x / width).coerceIn(0f, 1f),
        value = (1f - y / height).coerceIn(0f, 1f),
    )
}

internal fun alphaByte(alpha: Float): Int =
    (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
