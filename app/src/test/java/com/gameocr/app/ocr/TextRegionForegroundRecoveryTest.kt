package com.gameocr.app.ocr

import org.junit.Assert.*
import org.junit.Test

class TextRegionForegroundRecoveryTest {
    @Test
    fun recover_tableDriven_findsDisconnectedInkButKeepsBordersAndOtherRegions() {
        data class Case(val name: String, val background: Int, val ink: Int)
        listOf(
            Case("dark on light", 0xfffdfdfd.toInt(), 0xff060606.toInt()),
            Case("light on dark", 0xff141419.toInt(), 0xfff5f5f8.toInt()),
            Case("color text", 0xff9ccfee.toInt(), 0xff103db8.toInt()),
        ).forEach { case ->
            for (scale in listOf(1, 2)) for (transpose in listOf(false, true)) {
                val width = 96 * scale
                val height = width
                fun inside(index: Int, l: Int, t: Int, r: Int, b: Int): Boolean {
                    val x = if (transpose) index / width else index % width
                    val y = if (transpose) index % width else index / width
                    return x in l * scale until r * scale && y in t * scale until b * scale
                }
                val known = BooleanArray(width * height) { inside(it, 12, 20, 20, 44) }
                val missing = BooleanArray(known.size) { inside(it, 62, 25, 69, 46) }
                val border = BooleanArray(known.size) { inside(it, 79, 0, 82, 96) }
                val outside = BooleanArray(known.size) { inside(it, 87, 25, 91, 40) }
                val region = BooleanArray(known.size) { inside(it, 8, 8, 83, 72) }
                val source = IntArray(known.size) {
                    if (known[it] || missing[it] || border[it] || outside[it]) case.ink else case.background
                }
                val before = source.copyOf()
                val result = TextRegionForegroundRecovery.recover(
                    width, height, source, known, region, IntArray(32) { case.background }, true,
                )
                assertEquals(case.name, missing.count { it }, result.addedPixels)
                result.mask.indices.forEach { index ->
                    assertEquals(case.name, known[index] || missing[index], result.mask[index])
                }
                assertArrayEquals(before, source)
            }
        }
    }

    @Test
    fun recover_tableDriven_doesNotAssumeComplexBackgroundsAreFlat() {
        data class Case(val samples: IntArray, val flat: Boolean, val reason: TextRegionForegroundRecovery.Reason)
        val known = BooleanArray(64).apply { this[10] = true }
        val source = IntArray(64) { if (it % 2 == 0) 0xff080808.toInt() else 0xfffafafa.toInt() }
        listOf(
            Case(IntArray(32) { -1 }, false, TextRegionForegroundRecovery.Reason.BACKGROUND_NOT_FLAT),
            Case(intArrayOf(-1), true, TextRegionForegroundRecovery.Reason.INSUFFICIENT_SAMPLES),
            Case(source, true, TextRegionForegroundRecovery.Reason.BACKGROUND_NOT_FLAT),
        ).forEach { case ->
            val result = TextRegionForegroundRecovery.recover(8, 8, source, known, BooleanArray(64) { true }, case.samples, case.flat)
            assertEquals(case.reason, result.reason)
            assertArrayEquals(known, result.mask)
            assertEquals(0, result.addedPixels)
        }
    }
}
