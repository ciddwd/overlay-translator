package com.gameocr.app.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class TextBlockSourceGeometryTest {

    @Test
    fun mergeSourceBoxes_tableDriven_preservesOriginalLineGeometry() {
        data class Case(
            val name: String,
            val first: TextBlock,
            val second: TextBlock,
            val expected: List<Rect>,
        )

        fun block(bounds: Rect, sourceBoxes: List<Rect> = emptyList()) = TextBlock(
            text = "text",
            boundingBox = bounds,
            sourceBoxes = sourceBoxes,
        )

        val cases = listOf(
            Case(
                name = "unmerged blocks use their own bounds",
                first = block(Rect(10, 10, 40, 30)),
                second = block(Rect(45, 10, 80, 30)),
                expected = listOf(Rect(10, 10, 40, 30), Rect(45, 10, 80, 30)),
            ),
            Case(
                name = "existing source lines remain intact",
                first = block(
                    Rect(10, 10, 80, 30),
                    listOf(Rect(10, 10, 40, 30), Rect(45, 10, 80, 30)),
                ),
                second = block(Rect(10, 35, 80, 55)),
                expected = listOf(
                    Rect(10, 10, 40, 30),
                    Rect(45, 10, 80, 30),
                    Rect(10, 35, 80, 55),
                ),
            ),
            Case(
                name = "vertical columns preserve individual widths",
                first = block(Rect(70, 10, 90, 120), listOf(Rect(70, 10, 90, 120))),
                second = block(Rect(40, 15, 62, 118), listOf(Rect(40, 15, 62, 118))),
                expected = listOf(Rect(70, 10, 90, 120), Rect(40, 15, 62, 118)),
            ),
        )

        cases.forEach { case ->
            val actual = mergeSourceBoxes(case.first, case.second)
            assertEquals(case.name, case.expected.size, actual.size)
            case.expected.zip(actual).forEachIndexed { index, (expected, result) ->
                assertRectEquals("${case.name} #$index", expected, result)
            }
        }
    }

    private fun assertRectEquals(name: String, expected: Rect, actual: Rect) {
        assertEquals("$name left", expected.left, actual.left)
        assertEquals("$name top", expected.top, actual.top)
        assertEquals("$name right", expected.right, actual.right)
        assertEquals("$name bottom", expected.bottom, actual.bottom)
    }
}
