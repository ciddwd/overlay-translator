package com.gameocr.app.ocr

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class ArgbChannelMedianTest {

    @Test
    fun fromIndexedColors_tableDriven_matchesSortedUpperChannelMedian() {
        data class Case(
            val name: String,
            val source: IntArray,
            val indices: IntArray,
        )

        val random = Random(0x5eed)
        val randomColors = IntArray(257) { random.nextInt() }
        listOf(
            Case("single opaque color", intArrayOf(argb(255, 12, 34, 56)), intArrayOf(0)),
            Case(
                "odd unsorted channels",
                intArrayOf(
                    argb(255, 240, 30, 90),
                    argb(64, 10, 220, 20),
                    argb(128, 80, 100, 250),
                ),
                intArrayOf(2, 0, 1),
            ),
            Case(
                "even population uses upper median",
                intArrayOf(
                    argb(1, 10, 20, 30),
                    argb(2, 40, 50, 60),
                    argb(3, 70, 80, 90),
                    argb(4, 100, 110, 120),
                ),
                intArrayOf(3, 0, 2, 1),
            ),
            Case(
                "duplicate channels",
                intArrayOf(
                    argb(255, 8, 8, 8),
                    argb(255, 8, 8, 8),
                    argb(255, 248, 248, 248),
                    argb(255, 8, 8, 8),
                ),
                intArrayOf(0, 1, 2, 3),
            ),
            Case("deterministic random sample", randomColors, randomColors.indices.toList().shuffled(random).toIntArray()),
        ).forEach { case ->
            assertEquals(
                case.name,
                sortedUpperMedian(case.source, case.indices),
                ArgbChannelMedian.fromIndexedColors(case.source, case.indices),
            )
        }
    }

    private fun sortedUpperMedian(source: IntArray, indices: IntArray): Int {
        fun channel(shift: Int): Int = indices
            .map { index -> source[index] ushr shift and 0xff }
            .sorted()[indices.size / 2]
        return argb(channel(24), channel(16), channel(8), channel(0))
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
