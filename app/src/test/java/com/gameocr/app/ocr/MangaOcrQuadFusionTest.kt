package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrQuadFusionTest {

    @Test
    fun tiled_supplement_policy_is_table_driven() {
        data class Case(
            val name: String,
            val tiled: DBPostprocessor.Quad,
            val guardGap: Int,
            val expectedCount: Int,
        )

        val base = listOf(quad(100, 100, 160, 300))
        val cases = listOf(
            Case("exact-duplicate", quad(100, 100, 160, 300), 0, 1),
            Case("partial-overlap-cannot-bridge", quad(40, 80, 120, 220), 0, 1),
            Case("touching-edge-cannot-bridge", quad(160, 120, 220, 280), 0, 1),
            Case("inside-configured-guard-gap", quad(178, 120, 238, 280), 18, 1),
            Case("outside-configured-guard-gap", quad(179, 120, 239, 280), 18, 2),
            Case("independent-new-text-is-kept", quad(400, 100, 460, 300), 0, 2),
        )

        cases.forEach { case ->
            val result = fuseMangaOcrQuads(base, listOf(case.tiled), case.guardGap)
            assertEquals(case.name, case.expectedCount, result.size)
        }
    }

    @Test
    fun captured_bridges_do_not_expand_or_join_distinct_bubbles() {
        val base = listOf(
            quad(326, 38, 506, 222),
            quad(213, 121, 266, 372),
            quad(256, 122, 315, 440),
            quad(299, 122, 360, 318),
        )
        val tiled = listOf(
            quad(344, 52, 501, 217),
            quad(49, 41, 237, 241), // False positive that expanded the bubble to x=49.
            quad(367, 77, 498, 216),
            quad(303, 116, 359, 309),
            quad(213, 117, 263, 370),
            quad(303, 225, 360, 320),
            quad(256, 112, 314, 437),
            quad(210, 227, 267, 373),
            quad(257, 225, 314, 438),
            quad(700, 700, 760, 900), // A genuinely independent tiled-only detection.
        )

        val fused = fuseMangaOcrQuads(base, tiled, anchorGuardGap = 0)
        val bubbles = BubbleClusterer.cluster(
            rects = fused.map { it.axisAlignedBounds().toIntRect() },
            imgW = 1439,
            imgH = 2037,
            gap = 0,
        )

        assertEquals(3, bubbles.size)
        assertEquals(IntRect(326, 38, 506, 222), bubbles[0].contentRect)
        assertEquals(IntRect(213, 121, 360, 440), bubbles[1].contentRect)
        assertEquals(IntRect(700, 700, 760, 900), bubbles.last().contentRect)
    }

    private fun quad(left: Int, top: Int, right: Int, bottom: Int): DBPostprocessor.Quad =
        DBPostprocessor.Quad(
            p0 = paddlePointF(left.toFloat(), top.toFloat()),
            p1 = paddlePointF(right.toFloat(), top.toFloat()),
            p2 = paddlePointF(right.toFloat(), bottom.toFloat()),
            p3 = paddlePointF(left.toFloat(), bottom.toFloat()),
        )

    private fun IntArray.toIntRect(): IntRect = IntRect(this[0], this[1], this[2], this[3])
}
