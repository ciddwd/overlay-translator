package com.gameocr.app.ocr

import com.gameocr.app.data.TranslationOutputDirection
import com.gameocr.app.data.TranslationOutputLayout

internal object TranslationOutputOrientationPolicy {
    fun resolve(
        recognized: TextOrientation,
        followRecognition: Boolean,
        layout: TranslationOutputLayout,
        direction: TranslationOutputDirection,
    ): TextOrientation {
        if (followRecognition) {
            return recognized.takeUnless { it == TextOrientation.UNKNOWN }
                ?: TextOrientation.HORIZONTAL_LTR
        }

        val vertical = when (layout) {
            TranslationOutputLayout.FOLLOW_RECOGNITION -> false
            TranslationOutputLayout.HORIZONTAL -> false
            TranslationOutputLayout.VERTICAL -> true
        }
        val leftToRight = when (direction) {
            TranslationOutputDirection.FOLLOW_RECOGNITION -> true
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

}
