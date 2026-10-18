package com.gameocr.app.ocr

import org.junit.Assert.*
import org.junit.Test

class TextRegionInkAuditTest {
    @Test
    fun detachedShadedInk_isRecoveredWithoutRequiringAnotherDetectorSeed_tableDriven() {
        data class Case(val name: String, val background: Int, val ink: Int, val shaded: Int, val art: Int)
        val cases = listOf(
            Case("textured red", 0xfffce8e7.toInt(), 0xff9c0d22.toInt(), 0xff7b232f.toInt(), 0xff2060d0.toInt()),
            Case("shaded blue", 0xffe7f4fd.toInt(), 0xff1432b4.toInt(), 0xff1e307e.toInt(), 0xffb03020.toInt()),
            Case("shaded green", 0xfffde9e7.toInt(), 0xff0c9632.toInt(), 0xff286e3c.toInt(), 0xff5030c0.toInt()),
            Case("light cyan on dark", gray(20), 0xff28c8dc.toInt(), 0xff5096a3.toInt(), 0xffb03020.toInt()),
        )
        for (case in cases) for (scale in listOf(1, 2)) for (transpose in listOf(false, true)) {
            val size = 96 * scale
            fun inside(i: Int, l: Int, t: Int, r: Int, b: Int): Boolean {
                val x = if (transpose) i / size else i % size
                val y = if (transpose) i % size else i / size
                return x in l * scale until r * scale && y in t * scale until b * scale
            }
            val semantic = BooleanArray(size * size) { inside(it, 8, 8, 88, 88) }
            val core = BooleanArray(semantic.size) { inside(it, 20, 25, 28, 50) }
            val missing = BooleanArray(core.size) { inside(it, 58, 32, 64, 46) }
            val art = BooleanArray(core.size) { inside(it, 58, 60, 64, 70) }
            val source = IntArray(core.size) { when {
                core[it] -> case.ink; missing[it] -> case.shaded; art[it] -> case.art
                else -> case.background
            } }
            val result = TextRegionInkAudit.inspect(size, size, source, core, semantic, core)
            for (i in source.indices) {
                if (missing[i]) assertTrue("${case.name}/$scale/$transpose missing pixel=$i", result.mask[i])
                if (art[i]) assertTrue("${case.name}: different-hue artwork stays protected", result.protectedPixels[i])
            }
            assertEquals(missing.count { it }, result.addedPixels)
        }
    }

    @Test
    fun chromaticMatching_cannotAdmitGrayOrOppositeContrastIslands_tableDriven() {
        for (island in listOf(gray(40), 0xfff0b4b4.toInt(), 0xff2060d0.toInt())) {
            val size = 64
            val semantic = BooleanArray(size * size) { it % size in 8..55 && it / size in 8..55 }
            val core = BooleanArray(semantic.size) { it % size in 15..20 && it / size in 20..32 }
            val unknown = BooleanArray(core.size) { it % size in 40..46 && it / size in 35..42 }
            val source = IntArray(core.size) { when {
                core[it] -> 0xffdc4646.toInt(); unknown[it] -> island
                else -> gray(128)
            } }
            val result = TextRegionInkAudit.inspect(size, size, source, core, semantic, core)
            assertEquals(TextRegionInkAudit.Reason.PARTIAL, result.reason)
            for (i in unknown.indices) if (unknown[i]) {
                assertTrue(result.protectedPixels[i]); assertFalse(result.mask[i])
            }
            assertEquals(0, result.addedPixels)
        }
    }

    @Test
    fun paleGlyphOutlines_areErasedWhetherJoinedOrSeparatedFromTheInk_tableDriven() {
        for (background in listOf(0xffe7f4fd.toInt(), 0xfffde9e7.toInt()))
            for (scale in listOf(1, 2)) for (gap in listOf(0, 1)) {
                val size = 96 * scale
                fun inside(i: Int, l: Int, t: Int, r: Int, b: Int) =
                    i % size in l * scale until r * scale && i / size in t * scale until b * scale
                val semantic = BooleanArray(size * size) { inside(it, 8, 8, 88, 88) }
                val core = BooleanArray(semantic.size) { inside(it, 25, 29, 31, 57) }
                val glyph = BooleanArray(core.size) { inside(it, 24, 28, 32, 58) }
                val outline = BooleanArray(core.size) { inside(it, 21 - gap, 25 - gap, 35 + gap, 61 + gap) &&
                    !inside(it, 24 - gap, 28 - gap, 32 + gap, 58 + gap) }
                val source = IntArray(core.size) { when {
                    glyph[it] -> gray(15)
                    outline[it] -> gray(253)
                    inside(it, 60, 25, 66, 36) -> 0xffe02020.toInt()
                    else -> background
                } }
                val result = TextRegionInkAudit.inspect(size, size, source, core, semantic, core)
                for (i in source.indices) if (glyph[i] || outline[i]) {
                    assertTrue("scale=$scale gap=$gap pixel=$i", result.mask[i])
                    assertFalse(result.protectedPixels[i])
                }
                assertTrue("unseeded colored artwork is still protected", result.protectedPixels[30 * scale * size + 63 * scale])
                assertEquals(background, result.backgroundColor)
                assertTrue(result.outlinePixels > 0)
            }
    }

    @Test
    fun seededWordsWithDifferentInkColors_cannotProtectOneAnother_tableDriven() {
        for (transpose in listOf(false, true)) for (scale in listOf(1, 2)) {
            val size = 96 * scale
            fun inside(i: Int, l: Int, t: Int, r: Int, b: Int): Boolean {
                val x = if (transpose) i / size else i % size
                val y = if (transpose) i % size else i / size
                return x in l * scale until r * scale && y in t * scale until b * scale
            }
            val semantic = BooleanArray(size * size) { inside(it, 8, 8, 88, 88) }
            val red = BooleanArray(semantic.size) { inside(it, 20, 22, 38, 56) }
            val black = BooleanArray(semantic.size) { inside(it, 58, 30, 64, 54) }
            val core = BooleanArray(semantic.size) { red[it] || black[it] }
            val source = IntArray(core.size) { when {
                red[it] -> 0xffa01028.toInt(); black[it] -> gray(12)
                else -> 0xfffde9e7.toInt()
            } }
            val result = TextRegionInkAudit.inspect(size, size, source, core, semantic, core)
            assertEquals(TextRegionInkAudit.Reason.VERIFIED, result.reason)
            assertTrue(result.inkColorCount >= 2)
            for (i in core.indices) if (core[i]) assertTrue("scale=$scale transpose=$transpose pixel=$i", result.mask[i])
        }
    }

    @Test
    fun detectorSeedsOnOutsideArtwork_cannotOverrideTheInkInsideRecognizedText_tableDriven() {
        for (dark in listOf(false, true)) for (scale in listOf(1, 2)) {
            val w = 72 * scale; val h = 96 * scale
            val background = if (dark) gray(20) else gray(250)
            val ink = if (dark) gray(246) else gray(8)
            val semantic = BooleanArray(w * h) { it % w in 8 * scale until 64 * scale && it / w in 8 * scale until 88 * scale }
            val known = BooleanArray(w * h) { it % w in 60 * scale until 63 * scale && it / w in 35 * scale until 45 * scale }
            val source = IntArray(w * h) { background }
            val glyphs = BooleanArray(source.size)
            for (y in 15 * scale until 75 * scale) for (x in 18 * scale until 48 * scale) {
                if ((x / scale in 18..21 || x / scale in 43..46) && y / scale % 15 < 10) {
                    source[y * w + x] = ink
                    glyphs[y * w + x] = true
                }
            }
            for (y in 30 * scale until 52 * scale) for (x in 59 * scale until w) source[y * w + x] = 0xff6080b0.toInt()
            val result = TextRegionInkAudit.inspect(w, h, source, known, semantic, known)
            assertEquals(ink, result.inkColor)
            assertTrue("the actual detached glyphs are confirmed", result.addedPixels >= glyphs.count { it })
            for (i in source.indices) {
                if (glyphs[i]) assertTrue("glyph=$i", result.mask[i])
                if (known[i]) assertFalse("misidentified artwork=$i", result.mask[i])
            }
            assertEquals(TextRegionInkAudit.Reason.PARTIAL, result.reason)
        }
    }

    @Test
    fun detachedGlyphs_areRecoveredWithoutFollowingBordersOrIllustrations_tableDriven() {
        data class Case(val name: String, val background: Int, val ink: Int)
        for (case in listOf(Case("white", gray(250), gray(8)), Case("dark", gray(20), gray(246)),
            Case("colored", 0xffcde6f5.toInt(), 0xff102030.toInt()))) {
            for (scale in listOf(1, 2)) for (transpose in listOf(false, true)) {
                val size = 96 * scale
                fun inside(i: Int, l: Int, t: Int, r: Int, b: Int): Boolean {
                    val x = if (transpose) i / size else i % size
                    val y = if (transpose) i % size else i / size
                    return x in l * scale until r * scale && y in t * scale until b * scale
                }
                val known = BooleanArray(size * size) { inside(it, 20, 25, 25, 48) }
                val missing = BooleanArray(known.size) { inside(it, 63, 30, 68, 52) }
                val semantic = BooleanArray(known.size) { inside(it, 12, 12, 85, 84) }
                val border = BooleanArray(known.size) { inside(it, 81, 0, 83, 96) }
                val art = BooleanArray(known.size) { inside(it, 74, 68, 96, 96) }
                val source = IntArray(known.size) {
                    when { known[it] || missing[it] || border[it] -> case.ink
                        art[it] -> 0xffb08050.toInt(); else -> case.background }
                }
                val before = source.copyOf()
                val result = TextRegionInkAudit.inspect(size, size, source, known, semantic, known)
                assertEquals("${case.name}/$scale/$transpose", TextRegionInkAudit.Reason.VERIFIED, result.reason)
                assertEquals(missing.count { it }, result.addedPixels)
                for (i in result.mask.indices) assertEquals("${case.name}/pixel=$i", known[i] || missing[i], result.mask[i])
                assertArrayEquals(before, source)
            }
        }
    }

    @Test
    fun unexplainedInteriorColor_isProtectedAndPreventsACompleteClaim() {
        val size = 64
        val known = BooleanArray(size * size) { it % size in 15..19 && it / size in 20..30 }
        val semantic = BooleanArray(known.size) { it % size in 8..55 && it / size in 8..55 }
        val source = IntArray(known.size) { if (known[it]) gray(8) else gray(250) }
        for (y in 32..36) for (x in 42..46) source[y * size + x] = 0xffe02020.toInt()
        val result = TextRegionInkAudit.inspect(size, size, source, known, semantic, known)
        assertEquals(TextRegionInkAudit.Reason.PARTIAL, result.reason)
        assertEquals(25, result.uncertainPixels)
        assertEquals(0, result.addedPixels)
        assertTrue(result.protectedPixels[34 * size + 44])
        assertFalse(result.mask[34 * size + 44])
    }

    @Test
    fun aComponentCrossingTheTextBoundary_cannotBeVerifiedEvenWhenItContainsDetectorSeeds() {
        val size = 64
        val source = IntArray(size * size) { gray(250) }
        val core = BooleanArray(source.size)
        val semantic = BooleanArray(source.size) { it % size in 8..55 && it / size in 8..55 }
        for (y in 0 until size) for (x in 35..37) {
            source[y * size + x] = gray(8)
            if (y in 20..24) core[y * size + x] = true
        }
        val result = TextRegionInkAudit.inspect(size, size, source, core, semantic, core)
        assertEquals(TextRegionInkAudit.Reason.PARTIAL, result.reason)
        assertTrue(result.uncertainPixels > 0)
        assertArrayEquals(core, result.mask)
    }

    @Test
    fun unreliableEvidence_returnsTheOriginalTargets_tableDriven() {
        data class Case(val name: String, val mode: Int, val expected: TextRegionInkAudit.Reason)
        for (case in listOf(Case("empty region", 0, TextRegionInkAudit.Reason.INSUFFICIENT_SAMPLES),
            Case("all masked", 1, TextRegionInkAudit.Reason.INSUFFICIENT_SAMPLES),
            Case("pattern", 2, TextRegionInkAudit.Reason.NO_DOMINANT_BACKGROUND),
            Case("low contrast", 3, TextRegionInkAudit.Reason.LOW_CONTRAST),
            Case("gradient", 4, TextRegionInkAudit.Reason.NO_DOMINANT_BACKGROUND))) {
            val size = 64
            val core = BooleanArray(size * size) { it % size in 20..24 && it / size in 20..30 }
            val semantic = BooleanArray(core.size) { case.mode != 0 }
            val erase = if (case.mode == 1) semantic else core
            val source = IntArray(core.size) {
                when { core[it] -> gray(if (case.mode == 3) 235 else 8)
                    case.mode == 2 -> gray(if ((it % size + it / size) % 2 == 0) 250 else 8)
                    case.mode == 4 -> gray(it % size * 4)
                    else -> gray(250) }
            }
            val result = TextRegionInkAudit.inspect(size, size, source, core, semantic, erase)
            assertEquals(case.name, case.expected, result.reason)
            assertEquals(case.name, 0, result.addedPixels)
            assertArrayEquals(erase, result.mask)
        }
    }

    @Test
    fun malformedDimensionsAndMaskLengths_areRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TextRegionInkAudit.inspect(0, 1, IntArray(0), BooleanArray(0), BooleanArray(0), BooleanArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextRegionInkAudit.inspect(2, 2, IntArray(4), BooleanArray(3), BooleanArray(4), BooleanArray(4))
        }
    }

    private fun gray(value: Int): Int = -0x1000000 or (value shl 16) or (value shl 8) or value
}
