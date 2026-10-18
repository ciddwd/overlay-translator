package com.gameocr.app.ocr

import org.junit.Assert.*
import org.junit.Test

class TextInkConnectivityRepairTest {
    @Test fun faintBridgesDoNotAttachTextToExteriorArtwork_tableDriven() {
        for (dark in listOf(false, true)) for (scale in listOf(1, 2))
            for (transpose in listOf(false, true)) {
                val width = (if (transpose) 96 else 140) * scale
                val height = (if (transpose) 140 else 96) * scale
                fun x(i: Int) = (if (transpose) i / width else i % width) / scale
                fun y(i: Int) = (if (transpose) i % width else i / width) / scale
                val bg = if (dark) gray(16) else gray(250)
                val ink = if (dark) gray(240) else gray(8)
                val faint = if (dark) gray(42) else gray(224)
                val glyph = BooleanArray(width * height) { x(it) in 35..50 && y(it) in 20..65 }
                val art = BooleanArray(glyph.size) { y(it) >= 71 }
                val bridge = BooleanArray(glyph.size) { x(it) in 45..46 && y(it) in 66..70 }
                val semantic = BooleanArray(glyph.size) { x(it) in 8..131 && y(it) in 8..80 }
                val core = BooleanArray(glyph.size) {
                    (x(it) in 38..47 && y(it) in 24..60) || (x(it) in 10..130 && y(it) in 72..76)
                }
                val source = IntArray(glyph.size) { when {
                    glyph[it] || art[it] -> ink; bridge[it] -> faint; else -> bg
                } }
                val result = TextRegionInkAudit.inspect(width, height, source, core, semantic, core)
                for (i in source.indices) {
                    if (glyph[i]) {
                        assertTrue("dark=$dark scale=$scale transpose=$transpose glyph=$i", result.mask[i])
                        assertFalse(result.protectedPixels[i])
                    }
                    if (art[i]) {
                        assertTrue(result.protectedPixels[i])
                        assertFalse(result.mask[i])
                    }
                }
                assertEquals(TextRegionInkAudit.Reason.PARTIAL, result.reason)
                assertEquals(bg, result.backgroundColor)
            }
    }

    @Test fun faintFringesAndDetachedLowContrastOutlinesStillBelongToTheGlyph_tableDriven() {
        for (gap in 0..2) for (scale in listOf(1, 2)) {
            val size = 96 * scale
            fun inside(i: Int, l: Int, t: Int, r: Int, b: Int) =
                i % size in l * scale until r * scale && i / size in t * scale until b * scale
            val glyph = BooleanArray(size * size) { inside(it, 30, 25, 37, 52) }
            val fringe = BooleanArray(glyph.size) { inside(it, 28-gap, 23-gap, 39+gap, 54+gap) &&
                !inside(it, 30-gap, 25-gap, 37+gap, 52+gap) }
            val semantic = BooleanArray(glyph.size) { inside(it, 8, 8, 88, 88) }
            val source = IntArray(glyph.size) { when {
                glyph[it] -> gray(8); fringe[it] -> gray(224); else -> gray(250)
            } }
            val result = TextRegionInkAudit.inspect(size, size, source, glyph, semantic, glyph)
            for (i in glyph.indices) if (glyph[i] || fringe[i]) {
                assertTrue("gap=$gap scale=$scale pixel=$i", result.mask[i])
            }
            assertEquals(TextRegionInkAudit.Reason.VERIFIED, result.reason)
        }
    }

    @Test fun protectedArtCannotTintTheRepairedTitleBackground_tableDriven() {
        for (dark in listOf(false, true)) {
            val width = 140; val height = 96
            val bg = if (dark) gray(16) else gray(250)
            val ink = if (dark) gray(240) else gray(8)
            val glyph = BooleanArray(width * height) { it % width in 35..50 && it / width in 20..65 }
            val art = BooleanArray(glyph.size) { it / width >= 71 }
            val semantic = BooleanArray(glyph.size) { it % width in 8..131 && it / width in 8..80 }
            val core = BooleanArray(glyph.size) { glyph[it] || (it % width in 10..130 && it / width in 72..76) }
            val source = IntArray(glyph.size) { if (glyph[it] || art[it]) ink else bg }
            // A faint highlight inside the panel must not become a new "missed text" target.
            val highlight = 72 * width + 70
            source[highlight] = if (dark) gray(30) else gray(233)
            val audit = TextRegionInkAudit.inspect(width, height, source, core, semantic, core)
            assertTrue(audit.protectedPixels[highlight])
            val mask = TextPixelMaskBuilder.BlockMask(0, BubbleClusterer.IntRect(0, 0, width, height),
                core, core.count { it }, core, semantic, semantic)
            val repair = LocalTextBackgroundRepairer.repair(width, height, source, listOf(mask)).blocks.single()
            val patch = requireNotNull(repair.patchPixels)
            assertTrue(repair.displayable)
            for (i in source.indices) {
                if (glyph[i]) assertEquals("dark=$dark glyph=$i", bg, patch[i])
                if (art[i] && patch[i] != 0) assertEquals("protected art=$i", source[i], patch[i])
            }
        }
    }

    private fun gray(v: Int) = -0x1000000 or (v shl 16) or (v shl 8) or v
}
