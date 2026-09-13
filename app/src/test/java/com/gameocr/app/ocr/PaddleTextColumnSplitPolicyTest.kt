package com.gameocr.app.ocr

import org.junit.Assert.*
import org.junit.Test

class PaddleTextColumnSplitPolicyTest {
    private fun fixture(scale: Int = 1, columns: Int = 2, bridge: Float = .35f, horizontal: Boolean = false): Array<FloatArray> {
        val w = (columns * 10 + 4) * scale
        val h = 65 * scale
        val map = Array(h) { y -> FloatArray(w) { x ->
            if (y / scale in 8..55 && (0 until columns).any { x / scale in 3 + it * 10..8 + it * 10 }) .95f
            else if (y / scale == 30 && x / scale in 3 until columns * 10 - 1) bridge else 0f
        } }
        return if (!horizontal) map else Array(w) { y -> FloatArray(h) { x -> map[x][y] } }
    }
    private fun members(map: Array<FloatArray>) = map.indices.flatMap { y ->
        map[y].indices.filter { map[y][it] >= .25f }.map { y * map[0].size + it }
    }.toIntArray()

    @Test fun stable_columns_keep_all_low_threshold_pixels_across_scales() {
        for (scale in listOf(1, 2, 3)) for (columns in listOf(2, 3)) {
            val map = fixture(scale, columns)
            val pixels = members(map)
            val split = PaddleTextColumnSplitPolicy.split(map, pixels, .25f) { _, _, _ -> true }
            assertNotNull("scale=$scale columns=$columns", split)
            assertEquals(columns, split!!.members.size)
            assertEquals(pixels.toSet(), split.members.flatMap { it.toList() }.toSet())
            assertEquals(pixels.size, split.members.sumOf { it.size })
        }
    }
    @Test fun ambiguous_geometry_and_real_ink_crossings_are_not_split() {
        data class Case(val name: String, val map: Array<FloatArray>, val clear: Boolean = true)
        listOf(
            Case("single column", fixture(columns = 1)),
            Case("horizontal text", fixture(horizontal = true)),
            Case("separator cuts punctuation", fixture(), false),
            Case("square glyph", Array(20) { FloatArray(20) { .95f } }),
            Case("flat noise", Array(60) { FloatArray(24) { .26f } }),
            Case("empty", arrayOf(floatArrayOf())),
        ).forEach { c ->
            assertNull(c.name, PaddleTextColumnSplitPolicy.split(c.map, members(c.map), .25f) { _, _, _ -> c.clear })
        }
        assertNull(PaddleTextColumnSplitPolicy.split(fixture(), intArrayOf(-1), .25f) { _, _, _ -> true })
        assertNull(PaddleTextColumnSplitPolicy.split(fixture(), members(fixture()), Float.NaN) { _, _, _ -> true })
    }
    @Test fun separator_validation_covers_polarity_noise_ink_and_ambiguous_background() {
        data class Case(val name: String, val values: IntArray, val clear: Boolean)
        listOf(
            Case("white gap", IntArray(100) { 255 }, true),
            Case("black gap", IntArray(100), true),
            Case("minor noise", IntArray(100) { if (it == 5) 50 else 250 }, true),
            Case("dark ink", IntArray(100) { if (it < 10) 10 else 250 }, false),
            Case("light ink", IntArray(100) { if (it < 10) 250 else 10 }, false),
            Case("uncertain grey", IntArray(100) { 130 }, false),
            Case("empty", intArrayOf(), false),
        ).forEach { assertEquals(it.name, it.clear, PaddleTextColumnSplitPolicy.clearSeparator(it.values)) }
    }

    @Test fun a_faint_third_column_is_not_swallowed_by_a_strong_seed() {
        val map = fixture(columns = 3)
        for (y in map.indices) for (x in 23..28) if (map[y][x] >= .25f) map[y][x] = .32f
        assertNull(PaddleTextColumnSplitPolicy.split(map, members(map), .25f) { _, _, _ -> true })
    }
}
