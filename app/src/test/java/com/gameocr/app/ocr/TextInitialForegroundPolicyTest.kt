package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class TextInitialForegroundPolicyTest {
    @Test
    fun uncertainInkBoundary_recoversSafeInkBeforeRepairAndProtectsUnexplainedColor() {
        val bg = 0xfff8f8f8.toInt()
        val ink = 0xff080808.toInt()
        val core = BooleanArray(S * S) { i -> i % S in 20..24 && i / S in 18..30 }
        val semantic = BooleanArray(S * S) { i -> i % S in 12..36 && i / S in 12..36 }
        val source = IntArray(S * S) { bg }
        core.indices.filter { core[it] }.forEach { source[it] = ink }
        for (y in 21..25) for (x in 25..27) source[y * S + x] = 0xff505050.toInt()
        source[16 * S + 22] = 0xff90b050.toInt() // unexplained colored boundary, not missed ink
        for (i in 11..37) {
            source[11 * S + i] = ink; source[37 * S + i] = ink
            source[i * S + 11] = ink; source[i * S + 37] = ink
        }
        val mask = TextPixelMaskBuilder.BlockMask(0, IntRect(0, 0, S, S), core,
            core.count { it }, core, core, semantic)
        val repair = LocalTextBackgroundRepairer.repair(S, S, source, listOf(mask)).blocks.single()
        assertEquals(TextInitialForegroundPolicy.Reason.BACKGROUND_NOT_FLAT, repair.firstPassReason)
        assertTrue(repair.regionAuditAddedPixels > 0)
        assertFalse("the safe detached ink is already included in the first repair", repair.residualRepairAttempted)
        assertEquals(0, repair.residualPixels)
        assertTrue(repair.displayable)
        assertEquals(0, requireNotNull(repair.patchPixels)[11 * S + 20])
        assertEquals(0, requireNotNull(repair.patchPixels)[16 * S + 22])
        assertTrue(requireNotNull(repair.patchPixels)[23 * S + 27] != 0)
        assertFalse(repair.fullyRepaired)
    }

    @Test
    fun unreliableRegionBackground_keepsTheResidualRepairFallback_tableDriven() {
        for (dark in listOf(false, true)) {
            val bg = if (dark) 0xff181818.toInt() else 0xfff8f8f8.toInt()
            val ink = if (dark) 0xfff8f8f8.toInt() else 0xff080808.toInt()
            val core = BooleanArray(S * S) { it % S in 20..24 && it / S in 18..30 }
            val semantic = BooleanArray(S * S) { it % S in 8..40 && it / S in 8..40 }
            val source = IntArray(S * S) { bg }
            for (i in source.indices) {
                if (semantic[i] && (i % S <= 15 || i % S >= 30)) source[i] =
                    if (i % 2 == 0) 0xff305080.toInt() else 0xff80a050.toInt()
                if (core[i]) source[i] = ink
            }
            for (y in 21..25) for (x in 25..27) source[y * S + x] =
                if (dark) 0xffb0b0b0.toInt() else 0xff505050.toInt()
            source[16 * S + 22] = 0xff90b050.toInt()
            val mask = TextPixelMaskBuilder.BlockMask(0, IntRect(0, 0, S, S), core,
                core.count { it }, core, core, semantic)
            val repair = LocalTextBackgroundRepairer.repair(S, S, source, listOf(mask)).blocks.single()
            assertEquals(TextRegionInkAudit.Reason.NO_DOMINANT_BACKGROUND, repair.regionAuditReason)
            assertEquals(TextInitialForegroundPolicy.Reason.BACKGROUND_NOT_FLAT, repair.firstPassReason)
            assertTrue("unreliable backgrounds use verified strokes or retain residual repair",
                repair.strokeMaskPixels > 0 || repair.residualRepairAttempted)
            assertEquals(0, repair.residualPixels)
            assertTrue(repair.displayable)
            assertTrue(requireNotNull(repair.patchPixels)[23 * S + 27] != 0)
        }
    }

    @Test
    fun contaminatedRing_table_recoversStrokeEndsBeforeRepairWithoutErasingOuterArt() {
        data class Case(val name: String, val bg: Int, val ink: Int)
        listOf(Case("white balloon", 0xfff8f8f8.toInt(), 0xff080808.toInt()),
            Case("colored balloon", 0xffcfe8f4.toInt(), 0xff102030.toInt()),
            Case("light ink on dark panel", 0xff202838.toInt(), 0xfff8f8f8.toInt())).forEach { c ->
            val core = BooleanArray(S * S) { i -> i % S in 20..24 && i / S in 18..30 }
            val semantic = BooleanArray(S * S) { i -> i % S in 12..36 && i / S in 12..36 }
            val source = IntArray(S * S) { c.bg }
            core.indices.filter { core[it] }.forEach { source[it] = c.ink }
            for (y in 21..25) for (x in 25..27) source[y * S + x] = c.ink
            for (x in 6..42) source[10 * S + x] = c.ink
            val coverage = TextRepairSolidCoverage.plan(S, S, core, core, 1f)
            val initial = TextInitialForegroundPolicy.plan(S, S, source, core, core, semantic, coverage.repairMask)
            assertEquals(c.name, TextInitialForegroundPolicy.Reason.PLANNED, initial.reason)
            assertTrue(c.name, initial.excludedInkSamples > 0)
            val mask = TextPixelMaskBuilder.BlockMask(0, IntRect(0, 0, S, S), core, core.count { it }, core, core, semantic)
            val repaired = LocalTextBackgroundRepairer.repair(S, S, source, listOf(mask)).blocks.single()
            assertTrue(c.name, repaired.firstPassAddedPixels > 0)
            assertFalse(c.name, repaired.residualRepairAttempted)
            assertTrue(c.name, repaired.displayable)
            assertEquals(c.name, c.bg, requireNotNull(repaired.patchPixels)[23 * S + 27])
            assertEquals(c.name, 0, requireNotNull(repaired.patchPixels)[10 * S + 20])
        }
    }

    @Test
    fun firstPass_recoversDetachedInkOnFlatColorAndPreservesBorders() {
        for (dark in listOf(false, true)) {
            val bg = if (dark) 0xff202838.toInt() else 0xffe8f6fe.toInt()
            val ink = if (dark) 0xfffefefe.toInt() else 0xff080810.toInt()
            val core = BooleanArray(S * S) { i -> i % S in 20..24 && i / S in 18..30 }
            val semantic = BooleanArray(S * S) { i -> i % S in 8..40 && i / S in 8..40 }
            val source = IntArray(S * S) { bg }
            core.indices.filter { core[it] }.forEach { source[it] = ink }
            for (y in 23..26) for (x in 35..36) source[y * S + x] = ink
            for (x in 5..43) source[7 * S + x] = ink
            val mask = TextPixelMaskBuilder.BlockMask(0, IntRect(0, 0, S, S), core, core.count { it }, core, core, semantic)
            val repair = LocalTextBackgroundRepairer.repair(S, S, source, listOf(mask)).blocks.single()
            assertEquals(TextInitialForegroundPolicy.Reason.PLANNED, repair.firstPassReason)
            assertTrue(repair.firstPassAddedPixels >= 8)
            assertFalse("flat missed glyphs are covered before the first repair", repair.residualRepairAttempted)
            assertEquals(0, repair.residualPixels)
            assertEquals(bg, requireNotNull(repair.patchPixels)[24 * S + 35])
            assertEquals("outside border is not a target", 0, requireNotNull(repair.patchPixels)[7 * S + 20])
            assertTrue(repair.displayable)
        }
    }

    @Test
    fun preflightTable_rejectsUnreliableBackgroundOrForeground() {
        data class Case(val name: String, val fullMask: Boolean = false,
            val patterned: Boolean = false, val lowContrast: Boolean = false, val reason: TextInitialForegroundPolicy.Reason)
        listOf(
            Case("no clean samples", fullMask = true, reason = TextInitialForegroundPolicy.Reason.INSUFFICIENT_SAMPLES),
            Case("complex color background", patterned = true, reason = TextInitialForegroundPolicy.Reason.BACKGROUND_NOT_FLAT),
            Case("unconfirmed ink contrast", lowContrast = true, reason = TextInitialForegroundPolicy.Reason.LOW_CONTRAST),
        ).forEach { c ->
            val core = BooleanArray(S * S) { i -> c.fullMask || (i % S in 20..24 && i / S in 18..30) }
            val source = IntArray(S * S) { i ->
                if (core[i]) if (c.lowContrast) 0xffeeeeee.toInt() else 0xff101010.toInt()
                else if (c.patterned && i % 2 == 0) 0xff305080.toInt() else 0xfff0f0f0.toInt()
            }
            val coverage = TextRepairSolidCoverage.plan(S, S, core, core, 1f)
            val plan = TextInitialForegroundPolicy.plan(S, S, source, core, core, core, coverage.repairMask)
            assertEquals(c.name, c.reason, plan.reason)
            assertNull(c.name, plan.foreground)
        }
    }

    private companion object { const val S = 48 }
}
