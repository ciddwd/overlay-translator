package com.gameocr.app.overlay

import com.gameocr.app.ocr.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalTextLayoutTest {

    @Test
    fun resolveOverlayBlockOrientation_tableDriven_respectsFollowAndManualModes() {
        data class Case(
            val name: String,
            val page: TextOrientation,
            val block: TextOrientation?,
            val followRecognition: Boolean,
            val expected: TextOrientation,
        )

        val cases = listOf(
            Case(
                "follow mode keeps horizontal title on vertical page",
                TextOrientation.VERTICAL_RTL,
                TextOrientation.HORIZONTAL_LTR,
                true,
                TextOrientation.HORIZONTAL_LTR,
            ),
            Case(
                "follow mode keeps vertical bubble on mixed page",
                TextOrientation.VERTICAL_RTL,
                TextOrientation.VERTICAL_RTL,
                true,
                TextOrientation.VERTICAL_RTL,
            ),
            Case(
                "missing block orientation uses page direction",
                TextOrientation.HORIZONTAL_LTR,
                null,
                true,
                TextOrientation.HORIZONTAL_LTR,
            ),
            Case(
                "unknown block orientation uses page direction",
                TextOrientation.VERTICAL_RTL,
                TextOrientation.UNKNOWN,
                true,
                TextOrientation.VERTICAL_RTL,
            ),
            Case(
                "manual horizontal overrides detected vertical",
                TextOrientation.HORIZONTAL_RTL,
                TextOrientation.VERTICAL_RTL,
                false,
                TextOrientation.HORIZONTAL_RTL,
            ),
            Case(
                "manual vertical overrides detected horizontal",
                TextOrientation.VERTICAL_LTR,
                TextOrientation.HORIZONTAL_LTR,
                false,
                TextOrientation.VERTICAL_LTR,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                resolveOverlayBlockOrientation(case.page, case.block, case.followRecognition),
            )
        }
    }

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
            val minimumOriginalSizeRatio: Float = 0.86f,
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
            ),
            Case(
                name = "adaptive-style-can-shrink-to-four-sp-floor",
                originalPx = 63f,
                minReadablePx = 12f,
                minimumOriginalSizeRatio = 0f,
                expectedPx = 12f
            )
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedPx,
                verticalTextReadableMinSizePx(
                    case.originalPx,
                    case.minReadablePx,
                    case.minimumOriginalSizeRatio
                ),
                0.001f
            )
        }
    }
}
