package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalTextLayoutTest {

    @Test
    fun normalize_vertical_overlay_text_treats_ocr_column_breaks_as_soft_breaks() {
        data class Case(
            val name: String,
            val raw: String,
            val expected: String
        )

        val cases = listOf(
            Case(
                name = "cjk-column-breaks-collapse-without-spaces",
                raw = "我所在的誠南高中棒球部\n雖然不能算是顶尖的隊伍\n但部員們的士氣並不低落。",
                expected = "我所在的誠南高中棒球部雖然不能算是顶尖的隊伍但部員們的士氣並不低落。"
            ),
            Case(
                name = "ascii-words-keep-one-separator",
                raw = "Seinan\nHigh",
                expected = "Seinan High"
            ),
            Case(
                name = "blank-lines-are-ignored",
                raw = "\n而給予我們動力\n\n就是一直在比賽中爲我們加油的\n",
                expected = "而給予我們動力就是一直在比賽中爲我們加油的"
            ),
            Case(
                name = "crlf-is-normalized",
                raw = "A\r\nB",
                expected = "A B"
            )
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, normalizeVerticalOverlayText(case.raw))
        }
    }

    @Test
    fun readable_min_size_keeps_vertical_text_from_becoming_tiny() {
        data class Case(
            val name: String,
            val originalPx: Float,
            val minReadablePx: Float,
            val expectedPx: Float
        )

        val cases = listOf(
            Case(
                name = "fourteen-sp-on-xxhdpi-keeps-eighty-six-percent",
                originalPx = 42f,
                minReadablePx = 36f,
                expectedPx = 36.12f
            ),
            Case(
                name = "small-user-size-does-not-grow",
                originalPx = 30f,
                minReadablePx = 36f,
                expectedPx = 30f
            ),
            Case(
                name = "large-user-size-respects-readable-floor",
                originalPx = 60f,
                minReadablePx = 36f,
                expectedPx = 51.6f
            )
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedPx,
                verticalTextReadableMinSizePx(case.originalPx, case.minReadablePx),
                0.001f
            )
        }
    }
}
