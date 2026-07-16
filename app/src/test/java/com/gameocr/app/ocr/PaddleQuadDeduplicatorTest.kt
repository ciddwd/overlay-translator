package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleQuadDeduplicatorTest {

    @Test
    fun duplicate_policy_is_table_driven() {
        data class Case(
            val name: String,
            val first: DBPostprocessor.Quad,
            val second: DBPostprocessor.Quad,
            val expectedDuplicate: Boolean,
        )

        val cases = listOf(
            Case(
                name = "same box",
                first = quad(0f, 0f, 100f, 100f),
                second = quad(0f, 0f, 100f, 100f),
                expectedDuplicate = true,
            ),
            Case(
                name = "high overlap",
                first = quad(0f, 0f, 100f, 100f),
                second = quad(5f, 5f, 105f, 105f),
                expectedDuplicate = true,
            ),
            Case(
                name = "close center and similar area",
                first = quad(0f, 0f, 100f, 100f),
                second = quad(8f, -2f, 98f, 108f),
                expectedDuplicate = true,
            ),
            Case(
                name = "close center but very different area",
                first = quad(0f, 0f, 100f, 100f),
                second = quad(35f, 35f, 65f, 65f),
                expectedDuplicate = false,
            ),
            Case(
                name = "separate boxes",
                first = quad(0f, 0f, 100f, 100f),
                second = quad(150f, 0f, 250f, 100f),
                expectedDuplicate = false,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedDuplicate,
                paddleQuadsAreDuplicate(case.first, case.second),
            )
        }
    }

    @Test
    fun dedupe_keeps_distinct_boxes_and_offsets_tiles() {
        val base = quad(0f, 0f, 100f, 100f)
        val duplicateFromTile = quad(0f, 0f, 100f, 100f).offsetBy(5f, 5f)
        val distinctFromTile = quad(0f, 0f, 100f, 100f).offsetBy(200f, 0f)

        val result = dedupePaddleQuads(listOf(base, duplicateFromTile, distinctFromTile))

        assertEquals(2, result.size)
        assertEquals(50f, result[0].centerX, 0.001f)
        assertEquals(250f, result[1].centerX, 0.001f)
    }

    @Test
    fun dedupe_preserves_the_first_duplicate_candidate() {
        val preferred = quad(5f, 5f, 105f, 105f)
        val fallback = quad(0f, 0f, 100f, 100f)

        val result = dedupePaddleQuads(listOf(preferred, fallback))

        assertEquals(1, result.size)
        assertEquals(55f, result.single().centerX, 0.001f)
        assertEquals(55f, result.single().centerY, 0.001f)
    }

    private fun quad(left: Float, top: Float, right: Float, bottom: Float): DBPostprocessor.Quad =
        DBPostprocessor.Quad(
            p0 = paddlePointF(left, top),
            p1 = paddlePointF(right, top),
            p2 = paddlePointF(right, bottom),
            p3 = paddlePointF(left, bottom),
        )
}
