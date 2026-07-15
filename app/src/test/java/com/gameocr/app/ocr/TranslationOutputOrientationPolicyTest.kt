package com.gameocr.app.ocr

import com.gameocr.app.data.TranslationOutputDirection
import com.gameocr.app.data.TranslationOutputLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationOutputOrientationPolicyTest {
    @Test
    fun resolve_cases() {
        data class Case(
            val recognized: TextOrientation,
            val layout: TranslationOutputLayout,
            val direction: TranslationOutputDirection,
            val expected: TextOrientation,
        )

        val cases = listOf(
            Case(TextOrientation.VERTICAL_RTL, followLayout, followDirection, TextOrientation.VERTICAL_RTL),
            Case(TextOrientation.STACKED, followLayout, followDirection, TextOrientation.STACKED),
            Case(TextOrientation.UNKNOWN, followLayout, followDirection, TextOrientation.HORIZONTAL_LTR),
            Case(TextOrientation.VERTICAL_RTL, TranslationOutputLayout.HORIZONTAL, followDirection, TextOrientation.HORIZONTAL_RTL),
            Case(TextOrientation.HORIZONTAL_LTR, TranslationOutputLayout.VERTICAL, followDirection, TextOrientation.VERTICAL_LTR),
            Case(TextOrientation.HORIZONTAL_RTL, TranslationOutputLayout.VERTICAL, followDirection, TextOrientation.VERTICAL_RTL),
            Case(TextOrientation.VERTICAL_RTL, followLayout, TranslationOutputDirection.LEFT_TO_RIGHT, TextOrientation.VERTICAL_LTR),
            Case(TextOrientation.HORIZONTAL_LTR, followLayout, TranslationOutputDirection.RIGHT_TO_LEFT, TextOrientation.HORIZONTAL_RTL),
            Case(TextOrientation.UNKNOWN, TranslationOutputLayout.VERTICAL, TranslationOutputDirection.RIGHT_TO_LEFT, TextOrientation.VERTICAL_RTL),
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                TranslationOutputOrientationPolicy.resolve(case.recognized, case.layout, case.direction),
            )
        }
    }

    private val followLayout = TranslationOutputLayout.FOLLOW_RECOGNITION
    private val followDirection = TranslationOutputDirection.FOLLOW_RECOGNITION
}
