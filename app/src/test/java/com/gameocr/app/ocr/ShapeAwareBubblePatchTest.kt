package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ShapeAwareBubblePatchTest {

    @Test
    fun `patch role table controls whether existing block views are replaced`() {
        data class Case(
            val name: String,
            val modelIndex: Int?,
            val role: ShapeAwareBubblePatch.Role,
            val expectedReplacement: Boolean,
        )
        val cases = listOf(
            Case("shape translation", 2, ShapeAwareBubblePatch.Role.SHAPE_TRANSLATION, true),
            Case("model background repair", 2, ShapeAwareBubblePatch.Role.TEXT_BACKGROUND, false),
            Case("glyph background repair", null, ShapeAwareBubblePatch.Role.TEXT_BACKGROUND, false),
        )

        cases.forEach { case ->
            val patch = ShapeAwareBubblePatch(
                modelBubbleIndex = case.modelIndex,
                bounds = IntRect(0, 0, 2, 2),
                pixels = IntArray(4),
                coordinateScale = 1f,
                blockIndices = listOf(0),
                role = case.role,
            )
            assertEquals(case.name, case.expectedReplacement, patch.replacesBlockViews)
        }
    }

    @Test
    fun `display bounds use floor for origin and ceil for far edge`() {
        data class Case(
            val name: String,
            val bounds: IntRect,
            val scale: Float,
            val expected: IntRect,
        )

        val cases = listOf(
            Case("identity", IntRect(3, 5, 13, 17), 1f, IntRect(3, 5, 13, 17)),
            Case("even two times", IntRect(4, 6, 20, 18), 2f, IntRect(2, 3, 10, 9)),
            Case("odd two times", IntRect(3, 5, 20, 18), 2f, IntRect(1, 2, 10, 9)),
            Case("fractional scale", IntRect(4, 7, 20, 22), 1.5f, IntRect(2, 4, 14, 15)),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                scaledPatchBounds(case.bounds, case.scale),
            )
        }
    }

    @Test
    fun `background composer copies only repaired pixels inside model and image`() {
        data class Case(
            val name: String,
            val imageWidth: Int,
            val imageHeight: Int,
            val repairedPixels: IntArray,
            val repairedMask: BooleanArray,
            val modelMask: BubbleSegmentationPostprocessor.InstanceMask,
            val expected: IntArray,
        )

        val imagePixels = IntArray(12) { index -> 0xff000000.toInt() or (index + 1) }
        val cases = listOf(
            Case(
                name = "model and repaired intersection",
                imageWidth = 4,
                imageHeight = 3,
                repairedPixels = imagePixels,
                repairedMask = booleanArrayOf(
                    false, false, false, false,
                    false, true, true, false,
                    false, true, false, false,
                ),
                modelMask = BubbleSegmentationPostprocessor.InstanceMask(
                    left = 1,
                    top = 1,
                    width = 2,
                    height = 2,
                    pixels = booleanArrayOf(true, false, true, true),
                ),
                expected = intArrayOf(imagePixels[5], 0, imagePixels[9], 0),
            ),
            Case(
                name = "model clipped by image edge",
                imageWidth = 4,
                imageHeight = 3,
                repairedPixels = imagePixels,
                repairedMask = BooleanArray(12) { true },
                modelMask = BubbleSegmentationPostprocessor.InstanceMask(
                    left = -1,
                    top = 1,
                    width = 3,
                    height = 2,
                    pixels = BooleanArray(6) { true },
                ),
                expected = intArrayOf(
                    0, imagePixels[4], imagePixels[5],
                    0, imagePixels[8], imagePixels[9],
                ),
            ),
            Case(
                name = "unrepaired model remains transparent",
                imageWidth = 4,
                imageHeight = 3,
                repairedPixels = imagePixels,
                repairedMask = BooleanArray(12),
                modelMask = BubbleSegmentationPostprocessor.InstanceMask(
                    left = 0,
                    top = 0,
                    width = 2,
                    height = 2,
                    pixels = BooleanArray(4) { true },
                ),
                expected = IntArray(4),
            ),
        )

        cases.forEach { case ->
            assertArrayEquals(
                case.name,
                case.expected,
                ShapeAwareBubblePatchComposer.composeBackground(
                    imageWidth = case.imageWidth,
                    imageHeight = case.imageHeight,
                    repairedPixels = case.repairedPixels,
                    repairedMask = case.repairedMask,
                    modelMask = case.modelMask,
                ),
            )
        }
    }
}
