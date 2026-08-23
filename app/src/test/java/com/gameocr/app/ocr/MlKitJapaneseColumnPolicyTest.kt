package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitJapaneseColumnPolicyTest {

    @Test
    fun plan_tableDriven_triggersOnlyForStrongVerticalGeometryConflict() {
        data class Case(
            val name: String,
            val glyphs: List<MlKitJapaneseGlyph>,
            val lineOrientations: List<TextOrientation>,
            val expectedColumns: Int,
            val expectedShadow: Boolean,
            val expectedReason: String,
        )

        val vertical = verticalColumns(columnXs = listOf(100, 160), rows = 4)
        val cases = listOf(
            Case(
                name = "vertical glyphs reported as horizontal lines",
                glyphs = vertical,
                lineOrientations = List(8) { TextOrientation.HORIZONTAL_LTR },
                expectedColumns = 2,
                expectedShadow = true,
                expectedReason = "vertical-geometry-conflicts-with-first-pass",
            ),
            Case(
                name = "good vertical first pass stays untouched",
                glyphs = vertical,
                lineOrientations = List(8) { TextOrientation.VERTICAL_RTL },
                expectedColumns = 2,
                expectedShadow = false,
                expectedReason = "first-pass-not-horizontal",
            ),
            Case(
                name = "ordinary horizontal rows stay untouched",
                glyphs = horizontalRows(rowYs = listOf(100, 160), columns = 4),
                lineOrientations = List(8) { TextOrientation.HORIZONTAL_LTR },
                expectedColumns = 0,
                expectedShadow = false,
                expectedReason = "no-vertical-runs",
            ),
            Case(
                name = "dense two-dimensional geometry remains eligible for debug comparison",
                glyphs = verticalColumns(columnXs = listOf(100, 122, 144, 166), rows = 4),
                lineOrientations = List(16) { TextOrientation.HORIZONTAL_LTR },
                expectedColumns = 4,
                expectedShadow = true,
                expectedReason = "vertical-geometry-conflicts-with-first-pass",
            ),
            Case(
                name = "sparse symbols are insufficient",
                glyphs = listOf(glyph(100, 10), glyph(100, 32), glyph(100, 54)),
                lineOrientations = List(3) { TextOrientation.HORIZONTAL_LTR },
                expectedColumns = 0,
                expectedShadow = false,
                expectedReason = "insufficient-glyphs",
            ),
            Case(
                name = "minor vertical fragment cannot override horizontal page",
                glyphs = verticalColumns(listOf(100), rows = 3) + horizontalRows(listOf(180), columns = 7),
                lineOrientations = List(10) { TextOrientation.HORIZONTAL_LTR },
                expectedColumns = 1,
                expectedShadow = false,
                expectedReason = "vertical-evidence-not-dominant",
            ),
        )

        cases.forEach { case ->
            val plan = MlKitJapaneseColumnPolicy.plan(case.glyphs, case.lineOrientations)
            assertEquals(case.name, case.expectedColumns, plan.columns.size)
            assertEquals(case.name, case.expectedShadow, plan.shouldRunShadowRecognition)
            assertEquals(case.name, case.expectedReason, plan.reason)
        }
    }

    @Test
    fun plan_scaleInvariant_keepsNormalizedDecisionAndColumnMembership() {
        val original = verticalColumns(columnXs = listOf(100, 160), rows = 5)
        val doubled = original.map { item ->
            item.copy(
                bounds = MlKitGeometryRect(
                    item.bounds.left * 2,
                    item.bounds.top * 2,
                    item.bounds.right * 2,
                    item.bounds.bottom * 2,
                ),
            )
        }
        val orientations = List(10) { TextOrientation.HORIZONTAL_LTR }

        val originalPlan = MlKitJapaneseColumnPolicy.plan(original, orientations)
        val doubledPlan = MlKitJapaneseColumnPolicy.plan(doubled, orientations)

        assertTrue(originalPlan.shouldRunShadowRecognition)
        assertTrue(doubledPlan.shouldRunShadowRecognition)
        assertEquals(
            originalPlan.columns.map { it.memberIndices.size },
            doubledPlan.columns.map { it.memberIndices.size },
        )
        assertEquals(originalPlan.verticalCoverage, doubledPlan.verticalCoverage, 0.0001f)
        assertEquals(originalPlan.verticalScore, doubledPlan.verticalScore, 0.0001f)
    }

    @Test
    fun plan_invalidBoxesAreIgnored_withoutChangingValidColumn() {
        val valid = verticalColumns(columnXs = listOf(100, 160), rows = 4)
        val invalid = listOf(
            MlKitJapaneseGlyph(rect(0, 0, 0, 10), 99),
            MlKitJapaneseGlyph(rect(20, 20, 30, 20), 100),
        )

        val plan = MlKitJapaneseColumnPolicy.plan(
            glyphs = valid + invalid,
            lineOrientations = List(8) { TextOrientation.HORIZONTAL_LTR },
        )

        assertTrue(plan.shouldRunShadowRecognition)
        assertEquals(2, plan.columns.size)
        assertEquals(listOf(4, 4), plan.columns.map { it.memberIndices.size })
    }

    @Test
    fun cropPlan_tableDriven_clipsPaddingTargetsGlyphSizeAndCapsLongSide() {
        data class Case(
            val name: String,
            val candidate: MlKitJapaneseColumnCandidate,
            val imageWidth: Int,
            val imageHeight: Int,
            val expectedBounds: MlKitGeometryRect,
            val expectedWidth: Int,
            val expectedHeight: Int,
        )

        val cases = listOf(
            Case(
                name = "edge column clips padding and scales ten pixel glyphs",
                candidate = candidate(rect(0, 0, 20, 100), glyphSize = 10f),
                imageWidth = 100,
                imageHeight = 100,
                expectedBounds = rect(0, 0, 24, 100),
                expectedWidth = 48,
                expectedHeight = 200,
            ),
            Case(
                name = "large glyphs keep local detail instead of shrinking twice",
                candidate = candidate(rect(100, 100, 300, 900), glyphSize = 40f),
                imageWidth = 400,
                imageHeight = 1_000,
                expectedBounds = rect(86, 86, 314, 914),
                expectedWidth = 228,
                expectedHeight = 828,
            ),
            Case(
                name = "very long column respects output long side cap",
                candidate = candidate(rect(100, 0, 180, 2_900), glyphSize = 10f),
                imageWidth = 300,
                imageHeight = 3_000,
                expectedBounds = rect(96, 0, 184, 2_904),
                expectedWidth = 48,
                expectedHeight = 1_600,
            ),
        )

        cases.forEach { case ->
            val plan = MlKitJapaneseColumnCropPolicy.plan(
                candidate = case.candidate,
                imageWidth = case.imageWidth,
                imageHeight = case.imageHeight,
            )
            assertEquals(case.name, case.expectedBounds, plan.sourceBounds)
            assertEquals(case.name, case.expectedWidth, plan.outputWidth)
            assertEquals(case.name, case.expectedHeight, plan.outputHeight)
            assertTrue(case.name, maxOf(plan.outputWidth, plan.outputHeight) <= 1_600)
            assertFalse(case.name, plan.sourceBounds.isEmpty)
        }
    }

    private fun verticalColumns(columnXs: List<Int>, rows: Int): List<MlKitJapaneseGlyph> =
        columnXs.flatMapIndexed { columnIndex, x ->
            (0 until rows).map { row -> glyph(x, 20 + row * 22, columnIndex * rows + row) }
        }

    private fun horizontalRows(rowYs: List<Int>, columns: Int): List<MlKitJapaneseGlyph> =
        rowYs.flatMapIndexed { rowIndex, y ->
            (0 until columns).map { column -> glyph(20 + column * 22, y, rowIndex * columns + column) }
        }

    private fun glyph(x: Int, y: Int, line: Int = 0): MlKitJapaneseGlyph =
        MlKitJapaneseGlyph(rect(x, y, x + 10, y + 12), line)

    private fun candidate(bounds: MlKitGeometryRect, glyphSize: Float) = MlKitJapaneseColumnCandidate(
        memberIndices = listOf(0, 1, 2),
        sourceLineIndices = emptyList(),
        bounds = bounds,
        medianGlyphSize = glyphSize,
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
        MlKitGeometryRect(left, top, right, bottom)
}
