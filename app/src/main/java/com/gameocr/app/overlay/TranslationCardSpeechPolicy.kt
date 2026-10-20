package com.gameocr.app.overlay

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

/** Fixed blue for translation actions and dictionary links, independent of overlay borders. */
internal const val TRANSLATION_ACTION_ACCENT_COLOR: Int = 0xFF90CAF9.toInt()

internal fun translationActionMarkerColor(accentColor: Int): Int =
    (accentColor and 0x00FFFFFF) or 0x66000000
