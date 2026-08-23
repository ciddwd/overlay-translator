package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitJapaneseRecognitionPolicyTest {

    @Test
    fun inputScale_tableDriven_keepsUsefulTextSizeWithoutOversizedFrames() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val expectedWidth: Int,
            val expectedHeight: Int,
        )

        val cases = listOf(
            Case("current 2x portrait capture", 2880, 6400, 1350, 3000),
            Case("native high resolution portrait", 1440, 3200, 1350, 3000),
            Case("1080p portrait grows short side", 1080, 2400, 1200, 2666),
            Case("720p portrait grows short side", 720, 1600, 1200, 2666),
            Case("small square respects 3x cap", 300, 300, 900, 900),
            Case("medium square remains unchanged", 1600, 1600, 1600, 1600),
            Case("large portrait respects long side", 2000, 4000, 1500, 3000),
        )

        cases.forEach { case ->
            val plan = MlKitJapaneseInputPolicy.plan(case.width, case.height)
            assertEquals(case.name, case.expectedWidth, plan.outputWidth)
            assertEquals(case.name, case.expectedHeight, plan.outputHeight)
            assertTrue(case.name, maxOf(plan.outputWidth, plan.outputHeight) <= 3000)
            assertTrue(case.name, plan.outputWidth > 0 && plan.outputHeight > 0)
        }
    }

    @Test
    fun geometryAxis_tableDriven_usesCharacterFlowInsteadOfOuterBlockShape() {
        data class Case(
            val name: String,
            val points: List<MlKitGeometryPoint>,
            val expected: MlKitGeometryAxis,
        )

        val cases = listOf(
            Case(
                "vertical symbols inside a wide parent block",
                listOf(point(100, 20), point(102, 80), point(98, 140), point(101, 200)),
                MlKitGeometryAxis.VERTICAL,
            ),
            Case(
                "horizontal symbols inside a tall parent block",
                listOf(point(20, 100), point(80, 102), point(140, 98), point(200, 101)),
                MlKitGeometryAxis.HORIZONTAL,
            ),
            Case(
                "diagonal evidence stays unknown",
                listOf(point(20, 20), point(80, 80), point(140, 140)),
                MlKitGeometryAxis.UNKNOWN,
            ),
            Case("single symbol stays unknown", listOf(point(10, 10)), MlKitGeometryAxis.UNKNOWN),
            Case("missing symbols stays unknown", emptyList(), MlKitGeometryAxis.UNKNOWN),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, MlKitJapaneseGeometryPolicy.dominantAxis(case.points))
        }
    }

    private fun point(x: Int, y: Int) = MlKitGeometryPoint(x.toFloat(), y.toFloat())
}
