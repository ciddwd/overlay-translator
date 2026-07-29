package com.gameocr.app.gallery

import com.gameocr.app.data.BorderStyle
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayStyleMode
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.ocr.TextOrientation
import com.gameocr.app.ocr.TranslationOutputOrientationPolicy
import kotlin.math.roundToInt

internal enum class GalleryExportRenderMode {
    FIXED_BLOCKS,
    ADAPTIVE_BLOCKS,
    UNSUPPORTED_FLOATING,
}

internal data class GalleryExportPalette(
    val backgroundColor: Int,
    val foregroundColor: Int,
    val borderColor: Int,
    val borderWidthPx: Int,
    val borderStyle: BorderStyle,
)

internal fun galleryExportRenderMode(
    renderMode: RenderMode,
    overlayStyleMode: OverlayStyleMode,
): GalleryExportRenderMode = when {
    renderMode == RenderMode.FLOATING_WINDOW ->
        GalleryExportRenderMode.UNSUPPORTED_FLOATING
    overlayStyleMode == OverlayStyleMode.ADAPTIVE ->
        GalleryExportRenderMode.ADAPTIVE_BLOCKS
    else ->
        GalleryExportRenderMode.FIXED_BLOCKS
}

internal fun galleryExportRenderMode(settings: Settings): GalleryExportRenderMode =
    galleryExportRenderMode(settings.renderMode, settings.overlayStyleMode)

internal fun galleryCanExport(
    status: GalleryTaskStatus,
    successCount: Int,
    renderMode: GalleryExportRenderMode,
): Boolean =
    galleryCanExport(status, successCount) &&
        renderMode != GalleryExportRenderMode.UNSUPPORTED_FLOATING

internal fun galleryExportOrientation(
    settings: Settings,
    storedOrientation: String?,
): TextOrientation = TranslationOutputOrientationPolicy.resolve(
    recognized = storedOrientation
        ?.let { value -> runCatching { TextOrientation.valueOf(value) }.getOrNull() }
        ?: TextOrientation.UNKNOWN,
    followRecognition = settings.translationOutputFollowRecognition,
    layout = settings.translationOutputLayout,
    direction = settings.translationOutputDirection,
)

internal fun galleryExportPlacement(
    requested: OverlayPlacement,
    orientation: TextOrientation,
): OverlayPlacement = if (orientation.isGalleryVertical()) {
    OverlayPlacement.OVERLAP
} else {
    requested
}

internal fun galleryExportPalette(
    settings: Settings,
    density: Float,
    renderScale: Float,
): GalleryExportPalette {
    val safeDensity = density.takeIf { it > 0f } ?: 1f
    val safeScale = renderScale.takeIf { it > 0f } ?: 1f
    val borderStyle = if (settings.overlayTheme == OverlayTheme.CUSTOM) {
        settings.customBorderStyle
    } else {
        BorderStyle.SOLID
    }
    val (background, foreground, border, baseBorderWidthPx) = when (settings.overlayTheme) {
        OverlayTheme.CLASSIC_DARK -> listOf(
            0xE6000000.toInt(),
            0xFFFFFFFF.toInt(),
            0x00000000,
            0,
        )
        OverlayTheme.AMBER_GOLD -> listOf(
            0xF0241608.toInt(),
            0xFFFFD27F.toInt(),
            0xFFB8860B.toInt(),
            (2f * safeScale).roundToInt().coerceAtLeast(1),
        )
        OverlayTheme.PAPER_LIGHT -> listOf(
            0xF0F5EFE0.toInt(),
            0xFF3E2A1F.toInt(),
            0xFFB68850.toInt(),
            safeScale.roundToInt().coerceAtLeast(1),
        )
        OverlayTheme.FROST_GLASS -> listOf(
            0xCC1E293B.toInt(),
            0xFFE0F2FE.toInt(),
            0xFF60A5FA.toInt(),
            safeScale.roundToInt().coerceAtLeast(1),
        )
        OverlayTheme.CUSTOM -> listOf(
            settings.customBgColor,
            settings.customFgColor,
            settings.customBorderColor,
            (settings.customBorderWidth.coerceAtLeast(0) * safeDensity * safeScale)
                .roundToInt(),
        )
    }
    val alpha = settings.overlayAlpha.coerceIn(0f, 1f)
    return GalleryExportPalette(
        backgroundColor = galleryColorWithMultipliedAlpha(background, alpha),
        foregroundColor = galleryColorWithMultipliedAlpha(foreground, alpha),
        borderColor = galleryColorWithMultipliedAlpha(border, alpha),
        borderWidthPx = baseBorderWidthPx,
        borderStyle = borderStyle,
    )
}

internal fun galleryColorWithMultipliedAlpha(color: Int, multiplier: Float): Int {
    val alpha = (color ushr 24 and 0xFF)
    val resolvedAlpha = (alpha * multiplier.coerceIn(0f, 1f)).roundToInt()
    return (color and 0x00FFFFFF) or (resolvedAlpha shl 24)
}

internal fun TextOrientation.isGalleryVertical(): Boolean =
    this == TextOrientation.VERTICAL_LTR || this == TextOrientation.VERTICAL_RTL
