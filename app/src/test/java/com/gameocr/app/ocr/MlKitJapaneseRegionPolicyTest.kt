package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitJapaneseRegionPolicyTest {

    @Test
    fun expand_tableDriven_usesCompleteLinesAndMergesOnlySharedLineRegions() {
        data class Case(
            val name: String,
            val columns: List<MlKitJapaneseColumnCandidate>,
            val lineBounds: List<MlKitGeometryRect>,
            val expectedRegions: Int,
            val expectedBounds: List<MlKitGeometryRect>,
            val expectedSourceLines: List<List<Int>>,
        )

        val cases = listOf(
            Case(
                name = "columns sharing a split line become one complete region",
                columns = listOf(
                    candidate(100, 100, 120, 300, lines = listOf(0, 1), memberOffset = 0),
                    candidate(130, 120, 150, 280, lines = listOf(1, 2), memberOffset = 10),
                ),
                lineBounds = listOf(
                    rect(80, 80, 160, 140),
                    rect(70, 130, 170, 200),
                    rect(90, 190, 180, 260),
                ),
                expectedRegions = 1,
                expectedBounds = listOf(rect(70, 80, 180, 300)),
                expectedSourceLines = listOf(listOf(0, 1, 2)),
            ),
            Case(
                name = "disjoint source lines remain separate regions",
                columns = listOf(
                    candidate(10, 10, 30, 100, lines = listOf(0), memberOffset = 0),
                    candidate(200, 200, 230, 320, lines = listOf(1), memberOffset = 10),
                ),
                lineBounds = listOf(
                    rect(5, 5, 50, 110),
                    rect(190, 190, 250, 330),
                ),
                expectedRegions = 2,
                expectedBounds = listOf(rect(190, 190, 250, 330), rect(5, 5, 50, 110)),
                expectedSourceLines = listOf(listOf(1), listOf(0)),
            ),
            Case(
                name = "missing and empty line bounds preserve symbol candidate bounds",
                columns = listOf(
                    candidate(40, 50, 80, 180, lines = listOf(1, 9), memberOffset = 0),
                ),
                lineBounds = listOf(rect(0, 0, 0, 0), rect(0, 0, 0, 20)),
                expectedRegions = 1,
                expectedBounds = listOf(rect(40, 50, 80, 180)),
                expectedSourceLines = listOf(listOf(1, 9)),
            ),
            Case(
                name = "no columns produce no regions",
                columns = emptyList(),
                lineBounds = listOf(rect(0, 0, 10, 10)),
                expectedRegions = 0,
                expectedBounds = emptyList(),
                expectedSourceLines = emptyList(),
            ),
        )

        cases.forEach { case ->
            val regions = MlKitJapaneseRegionPolicy.expand(case.columns, case.lineBounds)
            assertEquals(case.name, case.expectedRegions, regions.size)
            assertEquals(case.name, case.expectedBounds, regions.map { it.bounds })
            assertEquals(case.name, case.expectedSourceLines, regions.map { it.sourceLineIndices })
        }
    }

    private fun candidate(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        lines: List<Int>,
        memberOffset: Int,
    ) = MlKitJapaneseColumnCandidate(
        memberIndices = listOf(memberOffset, memberOffset + 1, memberOffset + 2),
        sourceLineIndices = lines,
        bounds = rect(left, top, right, bottom),
        medianGlyphSize = 20f,
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
        MlKitGeometryRect(left, top, right, bottom)
}
