package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayedTextEraseMaskBuilderTest {
    @Test
    fun componentCoverageCache_keepsDifferentModelMasksAndFramesIndependent() {
        for (reverse in listOf(false, true)) {
            val members = listOf(IntRect(8, 8, 18, 18), IntRect(22, 8, 32, 18))
            val candidate = BooleanArray(WIDTH * HEIGHT).apply {
                for (x in 12..28) point(this, WIDTH, x, 12)
            }
            val blocks = members.mapIndexed { index, bounds -> confirmedBlock(bounds, index) }
                .let { if (reverse) it.reversed() else it }
            val result = DelayedTextEraseMaskBuilder.build(WIDTH, HEIGHT, candidate, members,
                listOf(modelGroup(listOf(0), 0), modelGroup(listOf(1), 1)),
                listOf(filledMask(IntRect(0, 0, WIDTH, HEIGHT)), filledMask(IntRect(22, 8, 34, 18))), blocks)
            assertTrue(result.decisions.single { it.blockIndex == 0 }.accepted)
            assertEquals(DelayedTextEraseMaskBuilder.Reason.MODEL_MASK_COVERAGE_LOW,
                result.decisions.single { it.blockIndex == 1 }.reason)
        }
    }

    @Test
    fun incrementalPixelCounts_matchUnionForRepeatedAndOverlappingBlocks() {
        for (repeats in listOf(1, 2, 8)) {
            val source = IntRect(8, 8, 18, 18)
            val candidate = BooleanArray(WIDTH * HEIGHT).apply {
                point(this, WIDTH, 12, 12); point(this, WIDTH, 13, 12)
            }
            val result = DelayedTextEraseMaskBuilder.build(WIDTH, HEIGHT, candidate,
                listOf(source), listOf(modelGroup(listOf(0))), listOf(filledMask(IntRect(0, 0, WIDTH, HEIGHT))),
                (0 until repeats).map { confirmedBlock(source, blockIndex = it) })
            assertEquals(repeats, result.acceptedBlockCount)
            assertEquals(result.mask.count { it }, result.decisions.sumOf { it.outputPixels })
            assertTrue(result.decisions.first().outputPixels > 0)
            result.decisions.drop(1).forEach { assertEquals(0, it.outputPixels) }
        }
    }


    private data class SafetyCase(
        val name: String,
        val memberBounds: List<IntRect>,
        val groups: List<BubbleModelRegrouper.Group>,
        val modelMasks: List<BubbleSegmentationPostprocessor.InstanceMask>,
        val block: DelayedTextEraseMaskBuilder.ConfirmedBlock,
        val drawCandidate: (BooleanArray, Int) -> Unit,
        val expectedReason: DelayedTextEraseMaskBuilder.Reason,
    )

    @Test
    fun build_tableDriven_rejectsUnsafeBlocksAndKeepsLegacyFallback() {
        val modelMask = filledMask(IntRect(4, 4, 28, 28))
        val modelGroup = modelGroup(memberIndices = listOf(0))
        val cases = listOf(
            SafetyCase(
                name = "source box has no OCR member",
                memberBounds = listOf(IntRect(8, 8, 18, 18)),
                groups = listOf(modelGroup),
                modelMasks = listOf(modelMask),
                block = confirmedBlock(IntRect(32, 8, 42, 18)),
                drawCandidate = { mask, width -> point(mask, width, 35, 12) },
                expectedReason = DelayedTextEraseMaskBuilder.Reason.MEMBER_UNMATCHED,
            ),
            SafetyCase(
                name = "two equally matching members are ambiguous",
                memberBounds = listOf(
                    IntRect(8, 8, 18, 18),
                    IntRect(8, 8, 18, 18),
                ),
                groups = listOf(modelGroup(memberIndices = listOf(0, 1))),
                modelMasks = listOf(modelMask),
                block = confirmedBlock(IntRect(8, 8, 18, 18)),
                drawCandidate = { mask, width -> point(mask, width, 12, 12) },
                expectedReason = DelayedTextEraseMaskBuilder.Reason.MEMBER_AMBIGUOUS,
            ),
            SafetyCase(
                name = "legacy regroup has no trusted model bubble",
                memberBounds = listOf(IntRect(8, 8, 18, 18)),
                groups = listOf(
                    BubbleModelRegrouper.Group(
                        memberIndices = listOf(0),
                        cropBounds = IntRect(6, 6, 20, 20),
                        contentBounds = IntRect(8, 8, 18, 18),
                        source = BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                        modelBubbleIndex = null,
                    )
                ),
                modelMasks = listOf(modelMask),
                block = confirmedBlock(IntRect(8, 8, 18, 18)),
                drawCandidate = { mask, width -> point(mask, width, 12, 12) },
                expectedReason = DelayedTextEraseMaskBuilder.Reason.MODEL_GROUP_UNAVAILABLE,
            ),
            SafetyCase(
                name = "candidate text core is empty",
                memberBounds = listOf(IntRect(8, 8, 18, 18)),
                groups = listOf(modelGroup),
                modelMasks = listOf(modelMask),
                block = confirmedBlock(IntRect(8, 8, 18, 18)),
                drawCandidate = { _, _ -> },
                expectedReason = DelayedTextEraseMaskBuilder.Reason.TEXT_CORE_EMPTY,
            ),
            SafetyCase(
                name = "model mask misses most text core",
                memberBounds = listOf(IntRect(8, 8, 18, 18)),
                groups = listOf(modelGroup),
                modelMasks = listOf(singlePixelMask(12, 12)),
                block = confirmedBlock(IntRect(8, 8, 18, 18)),
                drawCandidate = { mask, width ->
                    for (y in 10..13) for (x in 10..13) point(mask, width, x, y)
                },
                expectedReason = DelayedTextEraseMaskBuilder.Reason.MODEL_MASK_COVERAGE_LOW,
            ),
        )

        cases.forEach { case ->
            val candidate = BooleanArray(WIDTH * HEIGHT)
            case.drawCandidate(candidate, WIDTH)
            val result = DelayedTextEraseMaskBuilder.build(
                width = WIDTH,
                height = HEIGHT,
                candidateTextMask = candidate,
                memberBounds = case.memberBounds,
                modelGroups = case.groups,
                modelMasks = case.modelMasks,
                confirmedBlocks = listOf(case.block),
            )

            val decision = result.decisions.single()
            assertEquals(case.name, case.expectedReason, decision.reason)
            assertTrue("${case.name}: unsafe block uses legacy fallback", decision.useLegacyFallback)
            assertFalse(
                "${case.name}: rejected block produces no erase pixels",
                result.mask.any { it },
            )
        }
    }

    @Test
    fun build_tableDriven_onlySuccessfulTranslatedBlocksContributePixels() {
        data class TranslationCase(
            val name: String,
            val confirmed: List<DelayedTextEraseMaskBuilder.ConfirmedBlock>,
            val expectedLeftVisible: Boolean,
            val expectedRightVisible: Boolean,
        )
        val left = IntRect(6, 8, 18, 22)
        val right = IntRect(30, 8, 42, 22)
        val cases = listOf(
            TranslationCase("both translations succeeded", listOf(
                confirmedBlock(left, blockIndex = 0),
                confirmedBlock(right, blockIndex = 1),
            ), true, true),
            TranslationCase(
                "second translation failed",
                listOf(confirmedBlock(left, blockIndex = 0)),
                true,
                false,
            ),
            TranslationCase("all translations failed", emptyList(), false, false),
        )

        cases.forEach { case ->
            val candidate = BooleanArray(WIDTH * HEIGHT).apply {
                point(this, WIDTH, 12, 14)
                point(this, WIDTH, 36, 14)
            }
            val result = DelayedTextEraseMaskBuilder.build(
                width = WIDTH,
                height = HEIGHT,
                candidateTextMask = candidate,
                memberBounds = listOf(left, right),
                modelGroups = listOf(
                    modelGroup(memberIndices = listOf(0), modelIndex = 0),
                    modelGroup(memberIndices = listOf(1), modelIndex = 1),
                ),
                modelMasks = listOf(
                    filledMask(IntRect(4, 5, 22, 25)),
                    filledMask(IntRect(27, 5, 46, 25)),
                ),
                confirmedBlocks = case.confirmed,
            )

            assertEquals(case.name, case.expectedLeftVisible, result.mask[14 * WIDTH + 12])
            assertEquals(case.name, case.expectedRightVisible, result.mask[14 * WIDTH + 36])
        }
    }

    @Test
    fun build_tableDriven_dynamicDilationUsesSourceTextSizeAndStaysInsideBubble() {
        data class DilationCase(
            val name: String,
            val source: IntRect,
            val coreX: Int,
            val coreY: Int,
            val expectedRadius: Int,
        )
        val cases = listOf(
            DilationCase("small text", IntRect(8, 8, 20, 20), 14, 14, 1),
            DilationCase("medium text", IntRect(6, 6, 34, 34), 20, 20, 2),
            DilationCase("large text", IntRect(2, 2, 48, 48), 25, 25, 3),
        )

        cases.forEach { case ->
            val candidate = BooleanArray(WIDTH * HEIGHT).apply {
                point(this, WIDTH, case.coreX, case.coreY)
            }
            val result = DelayedTextEraseMaskBuilder.build(
                width = WIDTH,
                height = HEIGHT,
                candidateTextMask = candidate,
                memberBounds = listOf(case.source),
                modelGroups = listOf(modelGroup(memberIndices = listOf(0))),
                modelMasks = listOf(filledMask(IntRect(0, 0, WIDTH, HEIGHT))),
                confirmedBlocks = listOf(confirmedBlock(case.source)),
            )

            assertEquals(case.name, DelayedTextEraseMaskBuilder.Reason.ACCEPTED, result.decisions.single().reason)
            assertTrue(
                "${case.name}: expected radius ${case.expectedRadius}",
                result.mask[case.coreY * WIDTH + case.coreX + case.expectedRadius],
            )
            assertFalse(
                "${case.name}: radius must not exceed dynamic limit",
                result.mask[case.coreY * WIDTH + case.coreX + case.expectedRadius + 1],
            )
        }

        val candidate = BooleanArray(WIDTH * HEIGHT).apply { point(this, WIDTH, 10, 10) }
        val clipped = DelayedTextEraseMaskBuilder.build(
            width = WIDTH,
            height = HEIGHT,
            candidateTextMask = candidate,
            memberBounds = listOf(IntRect(2, 2, 48, 48)),
            modelGroups = listOf(modelGroup(memberIndices = listOf(0))),
            modelMasks = listOf(filledMask(IntRect(10, 10, 12, 12))),
            confirmedBlocks = listOf(confirmedBlock(IntRect(2, 2, 48, 48))),
        )
        assertFalse("dilation cannot leave the model bubble mask", clipped.mask[10 * WIDTH + 9])
        assertTrue("core remains inside the model bubble mask", clipped.mask[10 * WIDTH + 10])
    }

    @Test
    fun build_componentFiltering_dropsUnrelatedLargeConnectedRegion() {
        val source = IntRect(10, 10, 20, 20)
        val candidate = BooleanArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH / 2) point(candidate, WIDTH, x, y)
        }
        val result = DelayedTextEraseMaskBuilder.build(
            width = WIDTH,
            height = HEIGHT,
            candidateTextMask = candidate,
            memberBounds = listOf(source),
            modelGroups = listOf(modelGroup(memberIndices = listOf(0))),
            modelMasks = listOf(filledMask(IntRect(0, 0, WIDTH, HEIGHT))),
            confirmedBlocks = listOf(confirmedBlock(source)),
        )

        assertEquals(
            DelayedTextEraseMaskBuilder.Reason.TEXT_CORE_EMPTY,
            result.decisions.single().reason,
        )
        assertFalse(result.mask.any { it })
    }

    private fun confirmedBlock(
        source: IntRect,
        blockIndex: Int = 0,
    ) = DelayedTextEraseMaskBuilder.ConfirmedBlock(
        blockIndex = blockIndex,
        sourceBoxes = listOf(source),
    )

    private fun modelGroup(
        memberIndices: List<Int>,
        modelIndex: Int = 0,
    ) = BubbleModelRegrouper.Group(
        memberIndices = memberIndices,
        cropBounds = IntRect(0, 0, WIDTH, HEIGHT),
        contentBounds = IntRect(0, 0, WIDTH, HEIGHT),
        source = BubbleModelRegrouper.Source.MODEL,
        modelBubbleIndex = modelIndex,
    )

    private fun filledMask(bounds: IntRect): BubbleSegmentationPostprocessor.InstanceMask {
        val width = bounds.width
        val height = bounds.height
        return BubbleSegmentationPostprocessor.InstanceMask(
            left = bounds.left,
            top = bounds.top,
            width = width,
            height = height,
            pixels = BooleanArray(width * height) { true },
        )
    }

    private fun singlePixelMask(
        x: Int,
        y: Int,
    ): BubbleSegmentationPostprocessor.InstanceMask =
        BubbleSegmentationPostprocessor.InstanceMask(
            left = x,
            top = y,
            width = 1,
            height = 1,
            pixels = booleanArrayOf(true),
        )

    private fun point(mask: BooleanArray, width: Int, x: Int, y: Int) {
        mask[y * width + x] = true
    }

    private companion object {
        const val WIDTH = 50
        const val HEIGHT = 50
    }
}
