package com.gameocr.app.overlay

import kotlin.math.roundToInt

internal data class TranslationCardLayoutSpec(
    val safeWidthPx: Int,
    val safeHeightPx: Int,
    val widthFraction: Float,
    val maxHeightFraction: Float,
) {
    fun shellWidthPx(): Int =
        (safeWidthPx * widthFraction).roundToInt().coerceAtLeast(1)

    fun cardHeightPx(shellVerticalPaddingPx: Int): Int =
        ((safeHeightPx * maxHeightFraction).roundToInt() - shellVerticalPaddingPx)
            .coerceAtLeast(1)
}

internal data class TranslationCardSafeInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

internal fun translationCardLayoutSpec(
    screenWidthPx: Int,
    screenHeightPx: Int,
    safeInsets: TranslationCardSafeInsets = TranslationCardSafeInsets(),
): TranslationCardLayoutSpec {
    val safeWidth = (
        screenWidthPx - safeInsets.left.coerceAtLeast(0) - safeInsets.right.coerceAtLeast(0)
        ).coerceAtLeast(1)
    val safeHeight = (
        screenHeightPx - safeInsets.top.coerceAtLeast(0) - safeInsets.bottom.coerceAtLeast(0)
        ).coerceAtLeast(1)
    val isLandscape = safeWidth > safeHeight
    return TranslationCardLayoutSpec(
        safeWidthPx = safeWidth,
        safeHeightPx = safeHeight,
        widthFraction = if (isLandscape) 0.78f else 0.88f,
        maxHeightFraction = if (isLandscape) 0.88f else 0.72f,
    )
}
