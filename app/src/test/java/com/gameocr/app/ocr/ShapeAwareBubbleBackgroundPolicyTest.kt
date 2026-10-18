package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class ShapeAwareBubbleBackgroundPolicyTest {
    @Test
    fun layoutRoutingTable_usesCompletedGlyphRepairsWithoutRelaxingEraseValidation() {
        data class Case(val name: String, val reason: DelayedTextEraseMaskBuilder.Reason,
            val complete: Boolean = true, val success: Boolean = true,
            val models: List<Int> = listOf(0), val accepted: Boolean = false,
            val expected: Boolean)
        listOf(
            Case("validated model repair remains usable", DelayedTextEraseMaskBuilder.Reason.ACCEPTED,
                complete = false, accepted = true, expected = true),
            Case("coverage mismatch with completed local repair", DelayedTextEraseMaskBuilder.Reason.MODEL_MASK_COVERAGE_LOW, expected = true),
            Case("incomplete local repair", DelayedTextEraseMaskBuilder.Reason.MODEL_MASK_COVERAGE_LOW, complete = false, expected = false),
            Case("translation failed", DelayedTextEraseMaskBuilder.Reason.MODEL_MASK_COVERAGE_LOW, success = false, expected = false),
            Case("ambiguous model", DelayedTextEraseMaskBuilder.Reason.MODEL_MASK_COVERAGE_LOW, models = listOf(0, 1), expected = false),
            Case("no model", DelayedTextEraseMaskBuilder.Reason.MODEL_MASK_COVERAGE_LOW, models = emptyList(), expected = false),
            Case("no group", DelayedTextEraseMaskBuilder.Reason.MODEL_GROUP_UNAVAILABLE, expected = false),
            Case("no trusted mask", DelayedTextEraseMaskBuilder.Reason.MODEL_MASK_UNAVAILABLE, expected = false),
            Case("unmatched member", DelayedTextEraseMaskBuilder.Reason.MEMBER_UNMATCHED, expected = false),
            Case("ambiguous member", DelayedTextEraseMaskBuilder.Reason.MEMBER_AMBIGUOUS, expected = false),
        ).forEach { case ->
            val decision = DelayedTextEraseMaskBuilder.Decision(0, case.accepted, case.reason,
                memberIndices = listOf(0), modelBubbleIndices = case.models)
            val groups = ShapeAwareBubbleBackgroundPolicy.layoutBlockIndicesByModel(listOf(decision),
                if (case.success) setOf(0) else emptySet(), if (case.complete) setOf(0) else emptySet())
            assertEquals(case.name, if (case.expected) mapOf(0 to setOf(0)) else emptyMap(), groups)
            assertEquals("erase decision is preserved", case.accepted, decision.accepted)
        }
        // Exact rejection ratios from the four remaining balloons in the saved page.
        val decisions = listOf(0.208f, 0.807f, 0.346f, 0.367f).mapIndexed { index, coverage ->
            DelayedTextEraseMaskBuilder.Decision(index, false,
                DelayedTextEraseMaskBuilder.Reason.MODEL_MASK_COVERAGE_LOW,
                memberIndices = listOf(index), modelBubbleIndices = listOf(index), minimumModelCoverage = coverage)
        }
        assertEquals((0..3).associateWith { setOf(it) },
            ShapeAwareBubbleBackgroundPolicy.layoutBlockIndicesByModel(decisions, (0..3).toSet(), (0..3).toSet()))
    }

    @Test
    fun modelRepairTable_doesNotRepeatCompletedGlyphWork() {
        data class Case(val blocks: Set<Int>, val complete: Set<Int>, val expected: Boolean)
        listOf(
            Case(emptySet(), emptySet(), false),
            Case(setOf(0), setOf(0), false),
            Case(setOf(0, 1), setOf(0, 1, 2), false),
            Case(setOf(0, 1), setOf(0), true),
            Case(setOf(0), setOf(1), true),
        ).forEach { case -> assertEquals(case.expected,
            ShapeAwareBubbleBackgroundPolicy.needsModelRepair(case.blocks, case.complete)) }
    }

    @Test
    fun reuse_requiresEveryAssociatedTextRepairToBeComplete() {
        data class Case(val name: String, val blocks: Set<Int>, val repairs: List<ShapeAwareBubbleBackgroundPolicy.TextRepair>, val accepted: Boolean)
        fun repair(index: Int, complete: Boolean = true, pixels: IntArray? = intArrayOf(-1)) =
            ShapeAwareBubbleBackgroundPolicy.TextRepair(index, IntRect(1, 1, 2, 2), pixels, complete)
        val model = BubbleSegmentationPostprocessor.InstanceMask(0, 0, 3, 3, BooleanArray(9) { true })
        listOf(
            Case("complete", setOf(0), listOf(repair(0)), true),
            Case("missing", setOf(0, 1), listOf(repair(0)), false),
            Case("partial", setOf(0), listOf(repair(0, complete = false)), false),
            Case("null pixels", setOf(0), listOf(repair(0, pixels = null)), false),
            Case("empty pixels", setOf(0), listOf(repair(0, pixels = intArrayOf(0))), false),
            Case("no associated blocks", emptySet(), listOf(repair(0)), false),
            Case("neighbor does not rescue missing repair", setOf(0), listOf(repair(1)), false),
            Case("two complete blocks", setOf(0, 1), listOf(repair(0), repair(1)), true),
        ).forEach { case ->
            assertEquals(case.name, case.accepted, ShapeAwareBubbleBackgroundPolicy.compose(
                model, null, case.blocks, case.repairs.associateBy { it.blockIndex },
            ) != null)
        }
        val existing = IntArray(9) { -1 }
        assertArrayEquals(existing, ShapeAwareBubbleBackgroundPolicy.compose(model, existing, setOf(0), emptyMap()))
    }

    @Test
    fun reuse_clipsRepairsToTheActualBubbleAndKeepsColorsAndTransparency() {
        val model = BubbleSegmentationPostprocessor.InstanceMask(10, 20, 3, 2,
            booleanArrayOf(true, false, true, true, true, false))
        val colors = intArrayOf(0xffa0c0e0.toInt(), 0xffb0d0f0.toInt(), 0xffc0e0f0.toInt(), 0xffd0e0ff.toInt())
        val repair = ShapeAwareBubbleBackgroundPolicy.TextRepair(0, IntRect(9, 20, 13, 21), colors, true)
        assertArrayEquals(intArrayOf(colors[1], 0, colors[3], 0, 0, 0),
            ShapeAwareBubbleBackgroundPolicy.compose(model, null, setOf(0), mapOf(0 to repair)))
    }

    @Test
    fun textContrast_usesOpaqueRepairedPixelsOnly() {
        data class Case(val pixels: IntArray, val expected: Int)
        listOf(
            Case(intArrayOf(0, 0xffe8f6fe.toInt()), 0xff000000.toInt()),
            Case(intArrayOf(0, 0xff192940.toInt()), -1),
            Case(intArrayOf(0x00ffffff, 0xff101020.toInt()), -1),
            Case(intArrayOf(), 0xff000000.toInt()),
        ).forEach { case -> assertEquals(case.expected, ShapeAwareBubbleBackgroundPolicy.textColor(case.pixels)) }
    }

    @Test
    fun coloredGlyphRepair_canDriveBubbleSizedLayoutWithoutBlanketBackgroundFill() {
        // Exercise the actual repair -> transparent background -> layout pipeline on flat and
        // gradient color, including a nearby balloon border which forbids rectangle completion.
        for (gradient in listOf(false, true)) for (dark in listOf(false, true)) {
            val size = 32
            val glyph = BooleanArray(size * size) { index -> index % size in 14..17 && index / size in 12..21 }
            val source = IntArray(size * size) { index ->
                val shade = if (gradient) index / size else 0
                if (dark) rgb(20 + shade, 30 + shade, 45 + shade) else rgb(200 + shade, 220 + shade, 250)
            }
            val ink = if (dark) -1 else 0xff050508.toInt()
            for (i in source.indices) if (glyph[i]) source[i] = ink
            for (i in 2..29) {
                source[2 * size + i] = ink; source[29 * size + i] = ink
                source[i * size + 2] = ink; source[i * size + 29] = ink
            }
            val semantic = BooleanArray(source.size) { i -> i % size in 3..28 && i / size in 3..28 }
            val mask = TextPixelMaskBuilder.BlockMask(0, IntRect(0, 0, size, size), glyph,
                glyph.count { it }, glyph, glyph, semantic)
            val repair = LocalTextBackgroundRepairer.repair(size, size, source, listOf(mask)).blocks.single()
            val label = "gradient=$gradient dark=$dark"
            assertTrue(label, repair.displayable)
            assertEquals(label, 0, repair.residualPixels)
            assertFalse(label, repair.completion.reliable)
            val model = BubbleSegmentationPostprocessor.InstanceMask(0, 0, 96, 96,
                BooleanArray(96 * 96) { i -> i % 96 in 2..93 && i / 96 in 2..93 })
            val background = requireNotNull(ShapeAwareBubbleBackgroundPolicy.compose(model, null, setOf(0),
                mapOf(0 to ShapeAwareBubbleBackgroundPolicy.TextRepair(0, IntRect(32, 32, 64, 64),
                    repair.patchPixels, repair.displayable))))
            assertTrue(label, background[48 * 96 + 47] ushr 24 != 0)
            assertEquals("$label artwork outside glyphs stays transparent", 0, background[35 * 96 + 35])
            assertEquals("$label balloon border stays transparent", 0, background[34 * 96 + 42])
            val layout = ShapeAwareTextLayout.layout(96, 96, model.pixels, "试译文本",
                ShapeAwareTextLayout.Orientation.VERTICAL_RIGHT_TO_LEFT, 8, 36,
                ShapeAwareTextLayout.TextMeasurer { text, font -> text.length * font.toFloat() })
            assertTrue(label, layout.accepted)
            assertTrue("$label uses balloon rather than original glyph box", layout.fontSizePx >= 24)
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
}
