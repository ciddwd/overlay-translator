package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalOverlayLayoutTest {

    @Test
    fun adaptive_vertical_overflow_slot_table_driven_expands_without_crossing_neighbors() {
        data class Case(
            val name: String,
            val rect: OverlayIntRect,
            val neighbors: List<OverlayIntRect> = emptyList(),
            val screenWidth: Int = 1000,
            val rightToLeft: Boolean,
            val requiredWidth: Int,
            val expected: VerticalOverlaySlot,
        )

        val cases = listOf(
            Case(
                name = "Japanese column grows left",
                rect = OverlayIntRect(900, 100, 920, 200),
                rightToLeft = true,
                requiredWidth = 56,
                expected = VerticalOverlaySlot(864, 920),
            ),
            Case(
                name = "left edge Japanese column borrows right side",
                rect = OverlayIntRect(0, 100, 13, 200),
                rightToLeft = true,
                requiredWidth = 42,
                expected = VerticalOverlaySlot(0, 42),
            ),
            Case(
                name = "right edge LTR column borrows left side",
                rect = OverlayIntRect(987, 100, 1000, 200),
                rightToLeft = false,
                requiredWidth = 42,
                expected = VerticalOverlaySlot(958, 1000),
            ),
            Case(
                name = "dense neighbors cap expansion and preserve gap",
                rect = OverlayIntRect(100, 100, 120, 200),
                neighbors = listOf(
                    OverlayIntRect(70, 100, 90, 200),
                    OverlayIntRect(140, 100, 160, 200),
                ),
                rightToLeft = true,
                requiredWidth = 56,
                expected = VerticalOverlaySlot(98, 132),
            ),
            Case(
                name = "smaller requirement never shrinks source box",
                rect = OverlayIntRect(300, 100, 360, 200),
                rightToLeft = false,
                requiredWidth = 20,
                expected = VerticalOverlaySlot(300, 360),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                adaptiveVerticalOverflowSlot(
                    rect = case.rect,
                    allRects = case.neighbors + case.rect,
                    screenWidth = case.screenWidth,
                    rightToLeft = case.rightToLeft,
                    minGapPx = 8,
                    requiredWidthPx = case.requiredWidth,
                ),
            )
        }
    }

    @Test
    fun vertical_overlay_slot_table_driven_rtl_collision_bounds() {
        data class Case(
            val name: String,
            val rect: OverlayIntRect,
            val allRects: List<OverlayIntRect>,
            val expected: VerticalOverlaySlot
        )

        val rightBubble = OverlayIntRect(777, 1324, 1051, 1986)
        val leftBubble = OverlayIntRect(436, 1323, 710, 2034)
        val leftEdgeBubble = OverlayIntRect(0, 1149, 106, 1586)
        val midBubble = OverlayIntRect(428, 919, 590, 1559)
        val topLabel = OverlayIntRect(594, 747, 781, 786)
        val cases = listOf(
            Case(
                name = "right-bubble-expands-left-only-until-left-neighbor-gap",
                rect = rightBubble,
                allRects = listOf(rightBubble, leftBubble, topLabel),
                expected = VerticalOverlaySlot(left = 718, right = 1051)
            ),
            Case(
                name = "leftmost-bubble-is-capped-instead-of-using-screen-left-space",
                rect = leftBubble,
                allRects = listOf(rightBubble, leftBubble, topLabel),
                expected = VerticalOverlaySlot(left = 368, right = 710)
            ),
            Case(
                name = "non-overlapping-label-does-not-disable-width-cap",
                rect = rightBubble,
                allRects = listOf(rightBubble, topLabel),
                expected = VerticalOverlaySlot(left = 709, right = 1051)
            ),
            Case(
                name = "left-edge-rtl-bubble-expands-inward-instead-of-offscreen",
                rect = leftEdgeBubble,
                allRects = listOf(leftEdgeBubble, midBubble),
                expected = VerticalOverlaySlot(left = 0, right = 132)
            )
        )

        cases.forEach { case ->
            val actual = verticalOverlaySlot(
                rect = case.rect,
                allRects = case.allRects,
                screenWidth = 1440,
                rightToLeft = true,
                minGapPx = 8
            )
            assertEquals(case.name, case.expected, actual)
        }
    }

    @Test
    fun vertical_overlay_slot_ltr_uses_right_neighbor_as_boundary() {
        val left = OverlayIntRect(100, 100, 180, 400)
        val right = OverlayIntRect(230, 110, 300, 390)

        val slot = verticalOverlaySlot(
            rect = left,
            allRects = listOf(left, right),
            screenWidth = 500,
            rightToLeft = false,
            minGapPx = 10
        )

        assertEquals(VerticalOverlaySlot(left = 100, right = 200), slot)
    }

    @Test
    fun vertical_overlay_slot_ltr_right_edge_expands_inward() {
        val rightEdge = OverlayIntRect(394, 100, 500, 400)

        val slot = verticalOverlaySlot(
            rect = rightEdge,
            allRects = listOf(rightEdge),
            screenWidth = 500,
            rightToLeft = false,
            minGapPx = 8
        )

        assertEquals(VerticalOverlaySlot(left = 368, right = 500), slot)
    }

    @Test
    fun vertical_overlay_slot_keeps_readable_width_in_dense_rtl_columns() {
        val rightColumn = OverlayIntRect(1004, 1688, 1049, 2202)
        val leftColumn = OverlayIntRect(947, 1688, 995, 2204)

        val slot = verticalOverlaySlot(
            rect = rightColumn,
            allRects = listOf(rightColumn, leftColumn),
            screenWidth = 1440,
            rightToLeft = true,
            minGapPx = 24,
            minReadableWidthPx = 64
        )

        assertEquals(VerticalOverlaySlot(left = 985, right = 1049), slot)
    }

    @Test
    fun vertical_overlay_slot_keeps_readable_width_in_dense_ltr_columns() {
        val leftColumn = OverlayIntRect(391, 1688, 436, 2202)
        val rightColumn = OverlayIntRect(445, 1688, 493, 2204)

        val slot = verticalOverlaySlot(
            rect = leftColumn,
            allRects = listOf(leftColumn, rightColumn),
            screenWidth = 1440,
            rightToLeft = false,
            minGapPx = 24,
            minReadableWidthPx = 64
        )

        assertEquals(VerticalOverlaySlot(left = 391, right = 455), slot)
    }
}
