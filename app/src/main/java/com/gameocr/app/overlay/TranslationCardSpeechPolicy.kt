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
