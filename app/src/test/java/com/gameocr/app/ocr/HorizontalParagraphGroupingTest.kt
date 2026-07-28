package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalParagraphGroupingTest {

    private data class Case(
        val name: String,
        val rects: List<MergeDebugRect>,
        val bubbleGroupIds: List<Int?> = List(rects.size) { null },
        val expectedGroups: List<List<Int>>,
    )

    @Test
    fun groupHorizontalParagraphRects_tableDriven_preservesDenseReadingOrderWithoutOverlapSplits() {
        val cases = listOf(
            Case(
                name = "ordinary separated paragraph",
                rects = listOf(
                    MergeDebugRect(10, 10, 210, 60),
                    MergeDebugRect(12, 70, 230, 120),
                    MergeDebugRect(8, 130, 190, 180),
                ),
                expectedGroups = listOf(listOf(0, 1, 2)),
            ),
            Case(
                name = "latest dense score table does not split odd and even rows",
                rects = listOf(
                    MergeDebugRect(276, 1650, 488, 1737),
                    MergeDebugRect(189, 1690, 399, 1783),
                    MergeDebugRect(189, 1750, 539, 1847),
                    MergeDebugRect(182, 1806, 360, 1884),
                    MergeDebugRect(178, 1858, 354, 1937),
                    MergeDebugRect(168, 1908, 348, 1993),
                    MergeDebugRect(163, 1961, 342, 2044),
                    MergeDebugRect(156, 2014, 335, 2094),
                    MergeDebugRect(147, 2066, 329, 2148),
                ),
                expectedGroups = listOf((0..8).toList()),
            ),
            Case(
                name = "same row independent labels remain separate",
                rects = listOf(
                    MergeDebugRect(10, 10, 110, 60),
                    MergeDebugRect(150, 12, 260, 62),
                ),
                expectedGroups = listOf(listOf(0), listOf(1)),
            ),
            Case(
                name = "large vertical gap remains separate",
                rects = listOf(
                    MergeDebugRect(10, 10, 210, 60),
                    MergeDebugRect(12, 180, 230, 230),
                ),
                expectedGroups = listOf(listOf(0), listOf(1)),
            ),
            Case(
                name = "different detected bubbles remain separate despite intersecting boxes",
                rects = listOf(
                    MergeDebugRect(10, 10, 220, 150),
                    MergeDebugRect(20, 80, 210, 210),
                ),
                bubbleGroupIds = listOf(4, 7),
                expectedGroups = listOf(listOf(0), listOf(1)),
            ),
            Case(
                name = "detected bubble does not absorb unmatched nearby text",
                rects = listOf(
                    MergeDebugRect(10, 10, 220, 150),
                    MergeDebugRect(20, 120, 210, 220),
                ),
                bubbleGroupIds = listOf(4, null),
                expectedGroups = listOf(listOf(0), listOf(1)),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedGroups,
                groupHorizontalParagraphRects(
                    rects = case.rects,
                    bubbleGroupIds = case.bubbleGroupIds,
                    verticalGapRatio = 0.8f,
                    horizontalOverlapRatio = 0.3f,
                ),
            )
        }
    }
}
