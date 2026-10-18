package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MangaRepairPreparationTest {
    @Test
    fun deferredFragmentWithinNeighboringRepair_retainsItsOriginalPixels() = runBlocking {
        for (bg in listOf(0xfff8f8f8.toInt(), 0xffcfe8f4.toInt())) {
            val size = 64
            val member = IntRect(24, 18, 42, 44)
            val deferred = IntRect(36, 20, 42, 36)
            val candidate = BooleanArray(size * size) { i -> i % size in 28..31 && i / size in 22..39 }
            val source = IntArray(size * size) { i ->
                if (candidate[i] || (i % size in 37..39 && i / size in 25..28)) 0xff080808.toInt() else bg
            }
            val model = BubbleSegmentationPostprocessor.InstanceMask(0, 0, size, size, BooleanArray(size * size) { true })
            val batch = MangaDelayedMaskDebugSessionManager.Batch(1,
                MangaDelayedMaskDebugSessionManager.Input(size, size, source, candidate, listOf(member),
                    listOf(BubbleModelRegrouper.Group(BubbleModelRegrouper.Source.MODEL, 0, member, member, listOf(0))),
                    listOf(model), protectedSourceBounds = listOf(deferred)),
                listOf(DelayedTextEraseMaskBuilder.ConfirmedBlock(0, listOf(member), member)), emptyList(), 1f)
            val prepared = MangaDelayedMaskDebugSessionManager().prepare(batch, setOf(0))
            val patch = prepared.textBackgroundPatches.single()
            assertEquals(0, patch.pixels[(26 - patch.bounds.top) * patch.bounds.width + 38 - patch.bounds.left])
            assertNotEquals(0, patch.pixels[(26 - patch.bounds.top) * patch.bounds.width + 29 - patch.bounds.left])
            assertEquals(0xff080808.toInt(), source[26 * size + 38])
        }
    }

    @Test
    fun preparation_completesColorGlyphsOnceAndKeepsRescueForUnrepairableSources() = runBlocking {
        data class Case(val background: Int, val ink: Int, val wholeImageInk: Boolean = false)
        listOf(
            Case(0xffe8f6fe.toInt(), 0xff080810.toInt()),
            Case(0xff202838.toInt(), 0xfff8f8fe.toInt()),
            Case(0xfffefefe.toInt(), 0xff080810.toInt()),
            Case(0xff202838.toInt(), 0xff202838.toInt(), wholeImageInk = true),
        ).forEach { case ->
            val size = 64
            val member = if (case.wholeImageInk) IntRect(0, 0, size, size) else IntRect(24, 18, 36, 44)
            val candidate = BooleanArray(size * size) { i ->
                case.wholeImageInk || (i % size in 28..31 && i / size in 22..39)
            }
            val source = IntArray(size * size) { i -> if (candidate[i]) case.ink else case.background }
            val sourceBefore = source.copyOf()
            val model = BubbleSegmentationPostprocessor.InstanceMask(0, 0, size, size, BooleanArray(size * size) { true })
            val batch = MangaDelayedMaskDebugSessionManager.Batch(
                sessionId = 1,
                input = MangaDelayedMaskDebugSessionManager.Input(size, size, source, candidate,
                    listOf(member), listOf(BubbleModelRegrouper.Group(BubbleModelRegrouper.Source.MODEL,
                        0, member, member, listOf(0))), listOf(model)),
                blocks = listOf(DelayedTextEraseMaskBuilder.ConfirmedBlock(0, listOf(member), member)),
                textBlocks = emptyList(), coordinateScale = 1f,
            )
            val prepared = MangaDelayedMaskDebugSessionManager().prepare(batch, setOf(0))
            assertArrayEquals(sourceBefore, source)
            assertEquals(1, prepared.result.acceptedBlockCount)
            if (case.wholeImageInk) {
                assertEquals(0, prepared.textRepairResult.displayableBlockCount)
                assertEquals("failed glyph repair still tries original model rescue", 1, prepared.localRepairResult.cropCount)
                assertTrue(prepared.backgroundPatches.isEmpty())
            } else {
                assertEquals(1, prepared.textRepairResult.displayableBlockCount)
                assertEquals(0, prepared.textRepairResult.residualPixelCount)
                assertEquals("completed glyphs are not repaired a second time", 0, prepared.localRepairResult.cropCount)
                assertEquals(1, prepared.textBackgroundPatches.size)
                val repaired = prepared.textRepairResult.blocks.single()
                val layoutGroups = ShapeAwareBubbleBackgroundPolicy.layoutBlockIndicesByModel(
                    prepared.result.decisions, setOf(0), setOf(0))
                val background = ShapeAwareBubbleBackgroundPolicy.compose(model, null,
                    layoutGroups.getValue(0), mapOf(0 to ShapeAwareBubbleBackgroundPolicy.TextRepair(
                        0, repaired.mask.bounds, repaired.patchPixels, repaired.displayable)))
                assertNotNull(background)
            }
        }
    }
}
