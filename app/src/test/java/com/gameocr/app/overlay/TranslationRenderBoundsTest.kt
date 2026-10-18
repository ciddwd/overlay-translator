package com.gameocr.app.overlay

import org.junit.Assert.*
import org.junit.Test

class TranslationRenderBoundsTest {
    @Test fun narrowLabelsUseFreeHorizontalSpaceBeforeAddingLines_tableDriven() {
        data class Case(val source: OverlayIntRect, val others: List<OverlayIntRect>, val screen: Int,
            val required: Int, val gap: Int, val expected: OverlayIntRect)
        val label = OverlayIntRect(60, 100, 111, 132)
        listOf(
            Case(label, emptyList(), 1440, 64, 8, OverlayIntRect(60, 100, 124, 132)),
            Case(label, emptyList(), 1440, 30, 8, label),
            Case(label, listOf(OverlayIntRect(122, 100, 150, 132)), 1440, 64, 8,
                OverlayIntRect(50, 100, 114, 132)),
            Case(OverlayIntRect(92, 10, 100, 30), emptyList(), 100, 20, 8, OverlayIntRect(80, 10, 100, 30)),
            Case(label, listOf(OverlayIntRect(20, 100, 52, 132), OverlayIntRect(119, 100, 180, 132)),
                1440, 64, 8, label),
            Case(label, listOf(OverlayIntRect(119, 140, 180, 170)), 1440, 64, 8, OverlayIntRect(60, 100, 124, 132)),
            Case(label, listOf(OverlayIntRect(100, 100, 150, 132)), 1440, 64, 8, label),
            Case(OverlayIntRect(-10, 0, 20, 10), emptyList(), 100, 40, 0, OverlayIntRect(0, 0, 40, 10)),
            Case(OverlayIntRect(0, 0, 0, 0), emptyList(), 0, 100, 0, OverlayIntRect(0, 0, 1, 0)),
        ).forEach { case ->
            assertEquals(case.toString(), case.expected,
                translationHorizontalViewport(case.source, case.others, case.screen, case.required, case.gap))
        }
    }

    @Test fun expandedLabelsReserveTheirSpaceForTheFollowingLabel() {
        val first = translationHorizontalViewport(OverlayIntRect(20, 10, 40, 30),
            listOf(OverlayIntRect(80, 10, 100, 30)), 120, 50, 8)
        val second = translationHorizontalViewport(OverlayIntRect(80, 10, 100, 30), listOf(first), 120, 40, 8)
        assertTrue(first.right + 8 <= second.left)
    }

    @Test fun erasurePaddingExtendsPastTheSourceViewAndClipsOnlyAtScreenOrNeighbors_tableDriven() {
        data class Case(val block: OverlayIntRect, val sources: List<OverlayIntRect> = emptyList(),
            val neighbors: List<OverlayIntRect> = emptyList(), val expected: List<OverlayIntRect>)
        val source = OverlayIntRect(20, 20, 60, 60)
        listOf(
            Case(source, expected = listOf(OverlayIntRect(18, 18, 62, 62))),
            Case(OverlayIntRect(0, 0, 20, 20), expected = listOf(OverlayIntRect(0, 0, 22, 22))),
            Case(OverlayIntRect(80, 80, 100, 100), expected = listOf(OverlayIntRect(78, 78, 100, 100))),
            Case(source, neighbors = listOf(OverlayIntRect(0, 20, 19, 60), OverlayIntRect(61, 20, 80, 60),
                OverlayIntRect(20, 0, 60, 19), OverlayIntRect(20, 61, 60, 80)),
                expected = listOf(OverlayIntRect(19, 19, 61, 61))),
            Case(source, sources = listOf(OverlayIntRect(22, 22, 58, 38), OverlayIntRect(22, 42, 58, 58)),
                expected = listOf(OverlayIntRect(20, 20, 60, 40), OverlayIntRect(20, 40, 60, 60))),
            Case(source, sources = listOf(OverlayIntRect(0, 0, 0, 0)), expected = listOf(OverlayIntRect(18, 18, 62, 62))),
            Case(OverlayIntRect(0, 0, 0, 0), expected = emptyList()),
        ).forEach { case ->
            assertEquals(case.toString(), case.expected,
                translationSourceEraseRects(case.block, case.sources, case.neighbors, 100, 100))
        }
        assertEquals(emptyList<OverlayIntRect>(), translationSourceEraseRects(source, emptyList(), emptyList(), 0, 100))
    }

    @Test fun erasureRemainsOnTheOriginalRectAfterTranslationMovesAndExpands() {
        val source = OverlayIntRect(80, 80, 100, 100)
        val sources = listOf(source)
        val before = translationSourceEraseRects(source, sources, emptyList(), 100, 100)
        val translation = translationHorizontalViewport(source, emptyList(), 100, 40, 0)
        assertNotEquals(source, translation)
        assertEquals(listOf(OverlayIntRect(78, 78, 100, 100)), before)
        assertEquals(before, translationSourceEraseRects(source, sources, emptyList(), 100, 100))
        assertEquals(listOf(OverlayIntRect(80, 80, 100, 100)), sources)
    }
}
