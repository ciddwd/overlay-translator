package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class ReusableBackgroundPatchPolicyTest {

    @Test
    fun `background patch selection table reuses repair without covering final shape text`() {
        data class Case(
            val name: String,
            val shapeModelIndices: List<Int>,
            val modelBackgroundIndices: List<Int>,
            val textBlockIndices: List<Int>,
            val expectedModels: List<Int>,
            val expectedTextBlocks: List<Int>,
        )
        val cases = listOf(
            Case(
                name = "waiting state keeps every prepared background",
                shapeModelIndices = emptyList(),
                modelBackgroundIndices = listOf(1, 2),
                textBlockIndices = listOf(7),
                expectedModels = listOf(1, 2),
                expectedTextBlocks = listOf(7),
            ),
            Case(
                name = "final shape replaces only matching model background",
                shapeModelIndices = listOf(2),
                modelBackgroundIndices = listOf(1, 2, 3),
                textBlockIndices = listOf(7, 8),
                expectedModels = listOf(1, 3),
                expectedTextBlocks = listOf(7, 8),
            ),
            Case(
                name = "unmatched shape leaves prepared backgrounds untouched",
                shapeModelIndices = listOf(9),
                modelBackgroundIndices = listOf(1, 2),
                textBlockIndices = emptyList(),
                expectedModels = listOf(1, 2),
                expectedTextBlocks = emptyList(),
            ),
        )

        cases.forEach { case ->
            val selected = reusableBackgroundPatches(
                shapeTranslationPatches = case.shapeModelIndices.map(::shapePatch),
                modelBackgroundPatches = case.modelBackgroundIndices.map(::modelBackgroundPatch),
                textBackgroundPatches = case.textBlockIndices.map(::textBackgroundPatch),
            )
            assertEquals(
                case.name,
                case.expectedModels,
                selected.mapNotNull { it.modelBubbleIndex },
            )
            assertEquals(
                case.name,
                case.expectedTextBlocks,
                selected.filter { it.modelBubbleIndex == null }.flatMap { it.blockIndices },
            )
        }
    }

    private fun shapePatch(modelIndex: Int): ShapeAwareBubblePatch = patch(
        modelIndex = modelIndex,
        blockIndex = modelIndex,
        role = ShapeAwareBubblePatch.Role.SHAPE_TRANSLATION,
    )

    private fun modelBackgroundPatch(modelIndex: Int): ShapeAwareBubblePatch = patch(
        modelIndex = modelIndex,
        blockIndex = modelIndex,
        role = ShapeAwareBubblePatch.Role.TEXT_BACKGROUND,
    )

    private fun textBackgroundPatch(blockIndex: Int): ShapeAwareBubblePatch = patch(
        modelIndex = null,
        blockIndex = blockIndex,
        role = ShapeAwareBubblePatch.Role.TEXT_BACKGROUND,
    )

    private fun patch(
        modelIndex: Int?,
        blockIndex: Int,
        role: ShapeAwareBubblePatch.Role,
    ): ShapeAwareBubblePatch = ShapeAwareBubblePatch(
        modelBubbleIndex = modelIndex,
        bounds = IntRect(0, 0, 1, 1),
        pixels = intArrayOf(0),
        coordinateScale = 1f,
        blockIndices = listOf(blockIndex),
        role = role,
    )
}
