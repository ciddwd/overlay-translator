package com.gameocr.app.overlay

import com.gameocr.app.data.OverlayTextStyle
import com.gameocr.app.ocr.TextRegionGranularity

internal enum class AdaptiveRegionBackgroundMode {
    ERASE_SOURCE,
    TRANSPARENT,
}

internal data class AdaptiveRegionRenderPlan(
    val backgroundMode: AdaptiveRegionBackgroundMode,
    val textStyle: OverlayTextStyle,
)

/**
 * Provides the safe base layer while capability-driven bitmap patches are prepared.
 *
 * When the pixel-mask pipeline is active, region semantics intentionally do not affect fallback:
 * a successful shape patch replaces the view later, while model-free repair is drawn beneath it.
 * Other OCR paths retain their existing adaptive behavior.
 */
internal object AdaptiveRegionRenderPolicy {
    private const val LIGHT_OUTLINE = 0xFFFFFFFF.toInt()
    private const val DARK_OUTLINE = 0xFF000000.toInt()

    fun resolve(
        granularity: TextRegionGranularity,
        foregroundColor: Int,
        pixelMaskPatchPipelineEnabled: Boolean,
    ): AdaptiveRegionRenderPlan {
        if (!pixelMaskPatchPipelineEnabled && granularity != TextRegionGranularity.FREE_TEXT) {
            return AdaptiveRegionRenderPlan(
                backgroundMode = AdaptiveRegionBackgroundMode.ERASE_SOURCE,
                textStyle = OverlayTextStyle(),
            )
        }
        val outlineColor = listOf(LIGHT_OUTLINE, DARK_OUTLINE).maxBy { candidate ->
            AdaptiveOverlayStylePolicy.contrastRatio(candidate, foregroundColor)
        }
        return AdaptiveRegionRenderPlan(
            backgroundMode = AdaptiveRegionBackgroundMode.TRANSPARENT,
            textStyle = OverlayTextStyle(
                strokeEnabled = true,
                strokeColor = outlineColor,
            ),
        )
    }
}
