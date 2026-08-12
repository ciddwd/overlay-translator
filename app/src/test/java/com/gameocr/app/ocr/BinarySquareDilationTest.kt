package com.gameocr.app.ocr

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BinarySquareDilationTest {
    @Test
    fun dilate_tableDriven_matchesNaiveSquareStructuringElement() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val radius: Int,
            val input: BooleanArray,
        )

        val random = Random(42)
        val cases = listOf(
            Case("single center radius zero", 3, 3, 0, mask(3, 3, 1 to 1)),
            Case("single center radius one", 5, 4, 1, mask(5, 4, 2 to 2)),
            Case("corner clips at bounds", 5, 4, 2, mask(5, 4, 0 to 0)),
            Case("radius exceeds short side", 3, 2, 4, mask(3, 2, 2 to 1)),
            Case(
                "deterministic sparse mask",
                31,
                19,
                3,
                BooleanArray(31 * 19) { random.nextInt(7) == 0 },
            ),
        )

        cases.forEach { case ->
            assertArrayEquals(
                case.name,
                naive(case.input, case.width, case.height, case.radius),
                BinarySquareDilation.dilate(
                    input = case.input,
                    width = case.width,
                    height = case.height,
                    radius = case.radius,
                ),
            )
        }
    }

    private fun mask(width: Int, height: Int, vararg points: Pair<Int, Int>): BooleanArray =
        BooleanArray(width * height).also { output ->
            points.forEach { (x, y) -> output[y * width + x] = true }
        }

    private fun naive(
        input: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
    ): BooleanArray = BooleanArray(input.size).also { output ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!input[y * width + x]) continue
                for (dy in -radius..radius) {
                    val nextY = y + dy
                    if (nextY !in 0 until height) continue
                    for (dx in -radius..radius) {
                        val nextX = x + dx
                        if (nextX in 0 until width) output[nextY * width + nextX] = true
                    }
                }
            }
        }
    }
}
