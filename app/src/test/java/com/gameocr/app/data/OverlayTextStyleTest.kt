package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayTextStyleTest {

    @Test
    fun normalizedClampsEveryNumericStyleRange() {
        data class Case(
            val name: String,
            val input: OverlayTextStyle,
            val expected: OverlayTextStyle
        )

        val cases = listOf(
            Case(
                name = "below minimums",
                input = OverlayTextStyle(
                    letterSpacingEm = -1f,
                    lineSpacingMultiplier = 0.2f,
                    strokeWidthDp = -3f,
                    shadowRadiusDp = -5f,
                    shadowOffsetXDp = -20f,
                    shadowOffsetYDp = -30f
                ),
                expected = OverlayTextStyle(
                    letterSpacingEm = OverlayTextStyle.MIN_LETTER_SPACING_EM,
                    lineSpacingMultiplier = OverlayTextStyle.MIN_LINE_SPACING,
                    strokeWidthDp = OverlayTextStyle.MIN_STROKE_WIDTH_DP,
                    shadowRadiusDp = OverlayTextStyle.MIN_SHADOW_RADIUS_DP,
                    shadowOffsetXDp = OverlayTextStyle.MIN_SHADOW_OFFSET_DP,
                    shadowOffsetYDp = OverlayTextStyle.MIN_SHADOW_OFFSET_DP
                )
            ),
            Case(
                name = "above maximums",
                input = OverlayTextStyle(
                    letterSpacingEm = 2f,
                    lineSpacingMultiplier = 9f,
                    strokeWidthDp = 20f,
                    shadowRadiusDp = 40f,
                    shadowOffsetXDp = 30f,
                    shadowOffsetYDp = 20f
                ),
                expected = OverlayTextStyle(
                    letterSpacingEm = OverlayTextStyle.MAX_LETTER_SPACING_EM,
                    lineSpacingMultiplier = OverlayTextStyle.MAX_LINE_SPACING,
                    strokeWidthDp = OverlayTextStyle.MAX_STROKE_WIDTH_DP,
                    shadowRadiusDp = OverlayTextStyle.MAX_SHADOW_RADIUS_DP,
                    shadowOffsetXDp = OverlayTextStyle.MAX_SHADOW_OFFSET_DP,
                    shadowOffsetYDp = OverlayTextStyle.MAX_SHADOW_OFFSET_DP
                )
            )
        )

        cases.forEach { case -> assertEquals(case.name, case.expected, case.input.normalized()) }
    }
}
