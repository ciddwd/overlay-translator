package com.gameocr.app.ocr

import com.gameocr.app.data.TranslationOutputDirection
import com.gameocr.app.data.TranslationOutputLayout

internal object TranslationOutputOrientationPolicy {
    fun resolve(
        recognized: TextOrientation,
        layout: TranslationOutputLayout,
        direction: TranslationOutputDirection,
    ): TextOrientation {
        if (layout == TranslationOutputLayout.FOLLOW_RECOGNITION &&
            direction == TranslationOutputDirection.FOLLOW_RECOGNITION &&
            recognized != TextOrientation.UNKNOWN
        ) {
            return recognized
        }

        val vertical = when (layout) {
            TranslationOutputLayout.FOLLOW_RECOGNITION -> recognized.isVertical()
            TranslationOutputLayout.HORIZONTAL -> false
            TranslationOutputLayout.VERTICAL -> true
        }
        val leftToRight = when (direction) {
            TranslationOutputDirection.FOLLOW_RECOGNITION -> recognized.isLeftToRight()
            TranslationOutputDirection.LEFT_TO_RIGHT -> true
            TranslationOutputDirection.RIGHT_TO_LEFT -> false
        }
        return when {
            vertical && leftToRight -> TextOrientation.VERTICAL_LTR
            vertical -> TextOrientation.VERTICAL_RTL
            leftToRight -> TextOrientation.HORIZONTAL_LTR
            else -> TextOrientation.HORIZONTAL_RTL
        }
    }

    private fun TextOrientation.isVertical(): Boolean =
        this == TextOrientation.VERTICAL_LTR || this == TextOrientation.VERTICAL_RTL

    private fun TextOrientation.isLeftToRight(): Boolean = when (this) {
        TextOrientation.HORIZONTAL_RTL, TextOrientation.VERTICAL_RTL -> false
        else -> true
    }
}
