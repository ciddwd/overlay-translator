package com.gameocr.app.ocr

import android.graphics.Rect
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class RotationGeometryTest {

    @Test
    fun mapIntRectFromRotated180_mapsBackIntoOriginalCoordinateSpace() {
        data class Case(
            val name: String,
            val rect: IntRect,
            val imageW: Int,
            val imageH: Int,
            val expected: IntRect,
        )

        val cases = listOf(
            Case(
                name = "center box",
                rect = IntRect(left = 10, top = 20, right = 30, bottom = 50),
                imageW = 100,
                imageH = 80,
                expected = IntRect(left = 70, top = 30, right = 90, bottom = 60),
            ),
            Case(
                name = "full frame stays full frame",
                rect = IntRect(left = 0, top = 0, right = 100, bottom = 80),
                imageW = 100,
                imageH = 80,
                expected = IntRect(left = 0, top = 0, right = 100, bottom = 80),
            ),
            Case(
                name = "out of bounds clamps",
                rect = IntRect(left = -5, top = -10, right = 110, bottom = 90),
                imageW = 100,
                imageH = 80,
                expected = IntRect(left = 0, top = 0, right = 100, bottom = 80),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                mapIntRectFromRotated180(case.rect, case.imageW, case.imageH)
            )
        }
    }

    @Test
    fun mapBlocksFromRotated180_preservesTextAndMapsRect() {
        val block = TextBlock(
            text = "hello",
            boundingBox = Rect(10, 20, 30, 50),
            confidence = 0.8f,
            recognizedLanguage = "en",
            layoutOrientation = TextOrientation.HORIZONTAL_LTR,
            sourceBoxes = listOf(
                Rect(10, 20, 20, 50),
                Rect(20, 20, 30, 50),
            ),
        )

        val mapped = mapBlocksFromRotated180(listOf(block), imageW = 100, imageH = 80).single()

        assertEquals("hello", mapped.text)
        assertRectEquals(Rect(70, 30, 90, 60), mapped.boundingBox)
        assertEquals(0.8f, mapped.confidence, 0.0001f)
        assertEquals("en", mapped.recognizedLanguage)
        assertEquals(TextOrientation.HORIZONTAL_LTR, mapped.layoutOrientation)
        assertEquals(2, mapped.sourceBoxes.size)
        assertRectEquals(Rect(80, 30, 90, 60), mapped.sourceBoxes[0])
        assertRectEquals(Rect(70, 30, 80, 60), mapped.sourceBoxes[1])
    }

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals("left", expected.left, actual.left)
        assertEquals("top", expected.top, actual.top)
        assertEquals("right", expected.right, actual.right)
        assertEquals("bottom", expected.bottom, actual.bottom)
    }
}
