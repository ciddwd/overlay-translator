package com.gameocr.app.overlay

import com.gameocr.app.data.OverlayTheme

internal data class TranslationCardSpeechButtonMetrics(
    val sizePx: Int,
    val paddingPx: Int,
)

internal fun translationCardSpeechButtonMetrics(density: Float): TranslationCardSpeechButtonMetrics =
    TranslationCardSpeechButtonMetrics(
        sizePx = (28 * density).toInt(),
        paddingPx = (6 * density).toInt(),
    )

internal fun shouldShowTranslationCardSpeechButton(
    speechEnabled: Boolean,
    text: CharSequence?,
): Boolean = speechEnabled && !text.isNullOrBlank()

/** One action color shared by translation cards and compact dictionary previews. */
internal fun translationActionAccentColor(
    theme: OverlayTheme,
    customBorderColor: Int,
    customForegroundColor: Int,
): Int = when (theme) {
    OverlayTheme.CLASSIC_DARK -> 0xFF90CAF9.toInt()
    OverlayTheme.AMBER_GOLD -> 0xFFB8860B.toInt()
    OverlayTheme.PAPER_LIGHT -> 0xFFB68850.toInt()
    OverlayTheme.FROST_GLASS -> 0xFF60A5FA.toInt()
    OverlayTheme.CUSTOM -> customBorderColor.takeIf { it != 0 } ?: customForegroundColor
}

internal fun translationActionMarkerColor(accentColor: Int): Int =
    (accentColor and 0x00FFFFFF) or 0x66000000
