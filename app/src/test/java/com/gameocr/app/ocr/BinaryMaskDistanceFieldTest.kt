package com.gameocr.app.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryMaskDistanceFieldTest {

    @Test
    fun squaredEuclidean_tableDriven_matchesBruteForce() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val sources: List<Pair<Int, Int>>,
        )

        val cases = listOf(
            Case("single center pixel", 7, 5, listOf(3 to 2)),
            Case("two opposite corners", 8, 6, listOf(0 to 0, 7 to 5)),
            Case("horizontal source segment", 9, 7, (2..6).map { x -> x to 3 }),
            Case("asymmetric sparse mask", 6, 9, listOf(1 to 7, 4 to 1, 5 to 8)),
        )

        for (case in cases) {
            val mask = BooleanArray(case.width * case.height)
            case.sources.forEach { (x, y) -> mask[y * case.width + x] = true }
            val expected = IntArray(mask.size) { index ->
                val x = index % case.width
                val y = index / case.width
                case.sources.minOf { (sourceX, sourceY) ->
                    val dx = x - sourceX
                    val dy = y - sourceY
                    dx * dx + dy * dy
                }
            }
            assertArrayEquals(
                case.name,
                expected,
                BinaryMaskDistanceField.squaredEuclidean(case.width, case.height, mask),
            )
        }
    }

    @Test
    fun squaredEuclidean_withoutSource_marksEveryPixelUnreachable() {
        val result = BinaryMaskDistanceField.squaredEuclidean(
            width = 4,
            height = 3,
            sourceMask = BooleanArray(12),
        )
        result.forEach { distance ->
            assertEquals(BinaryMaskDistanceField.UNREACHABLE, distance)
        }
    }
}
