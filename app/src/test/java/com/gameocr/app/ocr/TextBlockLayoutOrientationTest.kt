package com.gameocr.app.ocr

import android.graphics.Rect
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class TextBlockLayoutOrientationTest {

    @Test
    fun inferSourceLayoutOrientation_tableDriven_handlesMixedMangaPage() {
        data class Case(
            val name: String,
            val sourceBoxes: List<IntRect>,
            val blockBounds: IntRect,
            val fallback: TextOrientation = TextOrientation.VERTICAL_RTL,
            val expected: TextOrientation,
        )

        val cases = listOf(
            Case(
                name = "wide title is horizontal on vertical page",
                sourceBoxes = listOf(IntRect(82, 70, 490, 154)),
                blockBounds = IntRect(70, 54, 503, 188),
                expected = TextOrientation.HORIZONTAL_LTR,
            ),
            Case(
                name = "multiple portrait columns are vertical",
                sourceBoxes = listOf(
                    IntRect(1190, 575, 1240, 900),
                    IntRect(1125, 590, 1177, 940),
                    IntRect(1050, 610, 1102, 920),
                ),
                blockBounds = IntRect(1010, 562, 1321, 979),
                expected = TextOrientation.VERTICAL_RTL,
            ),
            Case(
                name = "square source boxes fall back to wide block",
                sourceBoxes = listOf(IntRect(10, 10, 40, 40)),
                blockBounds = IntRect(0, 0, 160, 70),
                expected = TextOrientation.HORIZONTAL_LTR,
            ),
            Case(
                name = "square bubble defaults to manga vertical",
                sourceBoxes = listOf(IntRect(10, 10, 40, 40), IntRect(50, 10, 80, 40)),
                blockBounds = IntRect(0, 0, 100, 100),
                expected = TextOrientation.VERTICAL_RTL,
            ),
            Case(
                name = "square subtitle follows horizontal page",
                sourceBoxes = listOf(IntRect(10, 10, 40, 40)),
                blockBounds = IntRect(0, 0, 100, 100),
                fallback = TextOrientation.HORIZONTAL_LTR,
                expected = TextOrientation.HORIZONTAL_LTR,
            ),
            Case(
                name = "portrait block stays vertical on horizontal page",
                sourceBoxes = listOf(IntRect(10, 10, 45, 150)),
                blockBounds = IntRect(0, 0, 60, 170),
                fallback = TextOrientation.HORIZONTAL_LTR,
                expected = TextOrientation.VERTICAL_RTL,
            ),
            Case(
                name = "majority orientation wins over one outlier",
                sourceBoxes = listOf(
                    IntRect(10, 10, 30, 110),
                    IntRect(40, 10, 62, 105),
                    IntRect(70, 10, 170, 35),
                ),
                blockBounds = IntRect(0, 0, 180, 120),
                expected = TextOrientation.VERTICAL_RTL,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                inferSourceLayoutOrientation(
                    case.sourceBoxes,
                    case.blockBounds,
                    case.fallback,
                ),
            )
        }
    }

    @Test
    fun withFallbackLayoutOrientation_tableDriven_preservesPerBlockDetection() {
        data class Case(
            val name: String,
            val detected: TextOrientation?,
            val fallback: TextOrientation,
            val expected: TextOrientation,
        )

        val cases = listOf(
            Case(
                "horizontal title survives vertical page fallback",
                TextOrientation.HORIZONTAL_LTR,
                TextOrientation.VERTICAL_RTL,
                TextOrientation.HORIZONTAL_LTR,
            ),
            Case(
                "vertical bubble survives horizontal page fallback",
                TextOrientation.VERTICAL_RTL,
                TextOrientation.HORIZONTAL_LTR,
                TextOrientation.VERTICAL_RTL,
            ),
            Case(
                "missing detection receives fallback",
                null,
                TextOrientation.VERTICAL_RTL,
                TextOrientation.VERTICAL_RTL,
            ),
            Case(
                "unknown detection receives fallback",
                TextOrientation.UNKNOWN,
                TextOrientation.HORIZONTAL_LTR,
                TextOrientation.HORIZONTAL_LTR,
            ),
        )

        cases.forEach { case ->
            val block = TextBlock(
                text = "text",
                boundingBox = Rect(0, 0, 100, 40),
                layoutOrientation = case.detected,
            )
            assertEquals(
                case.name,
                case.expected,
                block.withFallbackLayoutOrientation(case.fallback).layoutOrientation,
            )
        }
    }
}
