package com.gameocr.app.data

internal data class ResolvedTranslationOutputSettings(
    val followRecognition: Boolean,
    val layout: TranslationOutputLayout,
    val direction: TranslationOutputDirection,
)

/**
 * Converts the legacy pair of independently-following options into the current master switch plus
 * explicit manual values. The manual values are retained while following recognition is enabled.
 */
internal fun resolveTranslationOutputSettings(
    storedFollowRecognition: Boolean?,
    layout: TranslationOutputLayout,
    direction: TranslationOutputDirection,
): ResolvedTranslationOutputSettings {
    val followRecognition = storedFollowRecognition ?: true
    val manualLayout = when (layout) {
        TranslationOutputLayout.FOLLOW_RECOGNITION -> TranslationOutputLayout.HORIZONTAL
        else -> layout
    }
    val manualDirection = when (direction) {
        TranslationOutputDirection.FOLLOW_RECOGNITION -> {
            if (manualLayout == TranslationOutputLayout.VERTICAL) {
                TranslationOutputDirection.RIGHT_TO_LEFT
            } else {
                TranslationOutputDirection.LEFT_TO_RIGHT
            }
        }
        else -> direction
    }
    return ResolvedTranslationOutputSettings(
        followRecognition = followRecognition,
        layout = manualLayout,
        direction = manualDirection,
    )
}
