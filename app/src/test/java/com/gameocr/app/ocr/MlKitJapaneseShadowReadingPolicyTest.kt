package com.gameocr.app.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MlKitJapaneseShadowReadingPolicyTest {

    @Test
    fun mapRectToUpright_tableDriven_restoresEverySupportedRotationAndClipsEdges() {
        data class Case(
            val name: String,
            val rotation: Int,
            val input: MlKitGeometryRect,
            val expected: MlKitGeometryRect,
        )

        val cases = listOf(
            Case(
                name = "upright coordinates remain unchanged",
                rotation = 0,
                input = rect(60, 20, 80, 100),
                expected = rect(60, 20, 80, 100),
            ),
            Case(
                name = "clockwise output maps back to upright",
                rotation = 90,
                input = rect(100, 60, 180, 80),
                expected = rect(60, 20, 80, 100),
            ),
            Case(
                name = "counterclockwise output maps back to upright",
                rotation = -90,
                input = rect(20, 20, 100, 40),
                expected = rect(60, 20, 80, 100),
            ),
            Case(
                name = "rounding outside the rotated crop is clipped",
                rotation = 90,
                input = rect(-2, -3, 205, 104),
                expected = rect(0, 0, 100, 200),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                MlKitJapaneseShadowReadingPolicy.mapRectToUpright(
                    rect = case.input,
                    uprightWidth = 100,
                    uprightHeight = 200,
                    rotationDegrees = case.rotation,
                ),
            )
        }
    }

    @Test
    fun orderVerticalRtl_tableDriven_usesOriginalReadingGeometryForAllRotations() {
        data class OriginalLine(val text: String, val bounds: MlKitGeometryRect)
        data class Case(val name: String, val rotation: Int)

        val uprightLines = listOf(
            OriginalLine("左列", rect(20, 10, 40, 100)),
            OriginalLine("右列下", rect(70, 110, 90, 190)),
            OriginalLine("右列上", rect(70, 10, 90, 100)),
        )
        val cases = listOf(
            Case("upright recognition", 0),
            Case("clockwise recognition", 90),
            Case("counterclockwise recognition", -90),
        )

        cases.forEach { case ->
            val recognizerOrder = uprightLines.reversed().map { line ->
                block(
                    text = line.text,
                    bounds = rotateFromUpright(line.bounds, case.rotation),
                )
            }
            val ordered = MlKitJapaneseShadowReadingPolicy.orderVerticalRtl(
                blocks = recognizerOrder,
                uprightWidth = 100,
                uprightHeight = 200,
                rotationDegrees = case.rotation,
            )

            assertEquals(
                case.name,
                listOf("右列上", "右列下", "左列"),
                ordered.map(TextBlock::text),
            )
            assertEquals(
                "${case.name} restored boxes",
                listOf(
                    rect(70, 10, 90, 100),
                    rect(70, 110, 90, 190),
                    rect(20, 10, 40, 100),
                ),
                ordered.map { it.boundingBox.toGeometry() },
            )
        }
    }

    @Test
    fun orderVerticalRtl_rejectsUnsupportedRotationInsteadOfGuessing() {
        assertThrows(IllegalArgumentException::class.java) {
            MlKitJapaneseShadowReadingPolicy.orderVerticalRtl(
                blocks = listOf(block("text", rect(0, 0, 10, 20))),
                uprightWidth = 100,
                uprightHeight = 200,
                rotationDegrees = 180,
            )
        }
    }

    private fun rotateFromUpright(
        rect: MlKitGeometryRect,
        rotation: Int,
    ): MlKitGeometryRect = when (rotation) {
        0 -> rect
        90 -> MlKitGeometryRect(
            left = 200 - rect.bottom,
            top = rect.left,
            right = 200 - rect.top,
            bottom = rect.right,
        )
        -90 -> MlKitGeometryRect(
            left = rect.top,
            top = 100 - rect.right,
            right = rect.bottom,
            bottom = 100 - rect.left,
        )
        else -> error("Unsupported test rotation")
    }

    private fun block(text: String, bounds: MlKitGeometryRect) = TextBlock(
        text = text,
        boundingBox = bounds.toRect(),
        sourceBoxes = listOf(bounds.toRect()),
    )

    private fun MlKitGeometryRect.toRect() = Rect().apply {
        left = this@toRect.left
        top = this@toRect.top
        right = this@toRect.right
        bottom = this@toRect.bottom
    }

    private fun Rect.toGeometry() = MlKitGeometryRect(left, top, right, bottom)

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
        MlKitGeometryRect(left, top, right, bottom)
}
