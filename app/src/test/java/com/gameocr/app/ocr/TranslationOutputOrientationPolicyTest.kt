package com.gameocr.app.ocr

import com.gameocr.app.data.TranslationOutputDirection
import com.gameocr.app.data.TranslationOutputLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationOutputOrientationPolicyTest {
    @Test
    fun resolve_cases() {
        data class Case(
            val name: String,
            val recognized: TextOrientation,
            val followRecognition: Boolean,
            val layout: TranslationOutputLayout,
            val direction: TranslationOutputDirection,
            val expected: TextOrientation,
        )

        val cases = listOf(
            Case("follow vertical rtl", TextOrientation.VERTICAL_RTL, true, horizontal, ltr, TextOrientation.VERTICAL_RTL),
            Case("follow stacked", TextOrientation.STACKED, true, vertical, rtl, TextOrientation.STACKED),
            Case("follow unknown fallback", TextOrientation.UNKNOWN, true, vertical, rtl, TextOrientation.HORIZONTAL_LTR),
            Case("manual horizontal ltr", TextOrientation.VERTICAL_RTL, false, horizontal, ltr, TextOrientation.HORIZONTAL_LTR),
            Case("manual horizontal rtl", TextOrientation.VERTICAL_LTR, false, horizontal, rtl, TextOrientation.HORIZONTAL_RTL),
            Case("manual vertical ltr", TextOrientation.HORIZONTAL_RTL, false, vertical, ltr, TextOrientation.VERTICAL_LTR),
            Case("manual vertical rtl", TextOrientation.HORIZONTAL_LTR, false, vertical, rtl, TextOrientation.VERTICAL_RTL),
            Case("manual sanitizes follow enums", TextOrientation.VERTICAL_RTL, false, followLayout, followDirection, TextOrientation.HORIZONTAL_LTR),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                TranslationOutputOrientationPolicy.resolve(
                    case.recognized,
                    case.followRecognition,
                    case.layout,
                    case.direction,
                ),
            )
        }
    }

    private val followLayout = TranslationOutputLayout.FOLLOW_RECOGNITION
    private val horizontal = TranslationOutputLayout.HORIZONTAL
    private val vertical = TranslationOutputLayout.VERTICAL
    private val followDirection = TranslationOutputDirection.FOLLOW_RECOGNITION
    private val ltr = TranslationOutputDirection.LEFT_TO_RIGHT
    private val rtl = TranslationOutputDirection.RIGHT_TO_LEFT
}
