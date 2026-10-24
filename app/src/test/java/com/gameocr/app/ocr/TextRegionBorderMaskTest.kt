package com.gameocr.app.ocr

import org.junit.Assert.*
import org.junit.Test

class TextRegionBorderMaskTest {
    @Test
    fun thinFrameWithGlyphJoins_isSeparatedAcrossSizesAndOrientations_tableDriven() {
        for (scale in listOf(1, 2)) for (transpose in listOf(false, true)) {
            val width = (if (transpose) 112 else 80) * scale
            val height = (if (transpose) 80 else 112) * scale
            fun x(i: Int) = (if (transpose) i / width else i % width) / scale
            fun y(i: Int) = (if (transpose) i % width else i / width) / scale
            val semantic = BooleanArray(width * height) { x(it) in 8..71 && y(it) in 8..103 }
            val line = BooleanArray(semantic.size) { x(it) in 11..13 }
            val glyph = BooleanArray(semantic.size) { x(it) in 14..25 && y(it) in 32..41 }
            val candidate = BooleanArray(semantic.size) { line[it] || glyph[it] }
            val before = candidate.copyOf()
            val result = TextRegionBorderMask.detect(width, height, candidate, semantic)
            for (i in result.indices) {
                if (line[i]) assertTrue("frame pixel=$i scale=$scale transposed=$transpose", result[i])
                if (x(i) in 18..25 && y(i) in 32..41) assertFalse("glyph body=$i", result[i])
            }
            assertArrayEquals(before, candidate)
        }
    }

    @Test
    fun tallInteriorStrokesCentralLinesAndBroadArtwork_areNotFrames_tableDriven() {
        val width = 80; val height = 112
        val semantic = BooleanArray(width * height) { it % width in 8..71 && it / width in 8..103 }
        for (mode in 0..2) {
            val candidate = BooleanArray(semantic.size) { i ->
                val x = i % width; val y = i / width
                when (mode) {
                    0 -> x in 10..12 && y in 20..90
                    1 -> x in 39..41
                    else -> x in 8..23
                }
            }
            assertFalse("mode=$mode", TextRegionBorderMask.detect(width,height,candidate,semantic).any { it })
        }
    }

    @Test
    fun emptyRegionAndMalformedDimensions_haveDefinedResults() {
        assertFalse(TextRegionBorderMask.detect(3,2,BooleanArray(6) { true },BooleanArray(6)).any { it })
        assertThrows(IllegalArgumentException::class.java) {
            TextRegionBorderMask.detect(0,2,BooleanArray(0),BooleanArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextRegionBorderMask.detect(3,2,BooleanArray(5),BooleanArray(6))
        }
    }
}
