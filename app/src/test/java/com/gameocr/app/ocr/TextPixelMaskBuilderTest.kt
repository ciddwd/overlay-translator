package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPixelMaskBuilderTest {

    @Test
    fun build_tableDriven_extractsOnlyGeometryRelatedTextComponents() {
        data class Case(
            val name: String,
            val sourceBoxes: List<IntRect>,
            val candidateRects: List<IntRect>,
            val expectedReason: TextPixelMaskBuilder.Reason,
        )
        val cases = listOf(
            Case(
                name = "two glyph components inside one OCR box",
                sourceBoxes = listOf(IntRect(20, 20, 40, 40)),
                candidateRects = listOf(IntRect(24, 24, 27, 36), IntRect(31, 24, 34, 36)),
                expectedReason = TextPixelMaskBuilder.Reason.ACCEPTED,
            ),
            Case(
                name = "vertical columns from multiple source boxes",
                sourceBoxes = listOf(IntRect(48, 12, 58, 34), IntRect(34, 12, 44, 34)),
                candidateRects = listOf(IntRect(51, 15, 54, 30), IntRect(37, 15, 40, 30)),
                expectedReason = TextPixelMaskBuilder.Reason.ACCEPTED,
            ),
            Case(
                name = "small text keeps a scale floor for fragmented detector pixels",
                sourceBoxes = listOf(IntRect(20, 20, 24, 24)),
                candidateRects = listOf(IntRect(19, 19, 24, 24)),
                expectedReason = TextPixelMaskBuilder.Reason.ACCEPTED,
            ),
            Case(
                name = "candidate far from OCR geometry",
                sourceBoxes = listOf(IntRect(20, 20, 40, 40)),
                candidateRects = listOf(IntRect(65, 45, 70, 50)),
                expectedReason = TextPixelMaskBuilder.Reason.TEXT_CORE_EMPTY,
            ),
            Case(
                name = "oversized connected artwork is rejected by relative area",
                sourceBoxes = listOf(IntRect(20, 20, 30, 30)),
                candidateRects = listOf(IntRect(15, 15, 36, 36)),
                expectedReason = TextPixelMaskBuilder.Reason.TEXT_CORE_EMPTY,
            ),
            Case(
                name = "missing source geometry",
                sourceBoxes = emptyList(),
                candidateRects = listOf(IntRect(20, 20, 24, 24)),
                expectedReason = TextPixelMaskBuilder.Reason.NO_SOURCE_BOXES,
            ),
        )

        cases.forEachIndexed { index, case ->
            val candidate = BooleanArray(WIDTH * HEIGHT)
            case.candidateRects.forEach { fill(candidate, it) }
            val result = TextPixelMaskBuilder.build(
                width = WIDTH,
                height = HEIGHT,
                candidateTextMask = candidate,
                confirmedBlocks = listOf(
                    DelayedTextEraseMaskBuilder.ConfirmedBlock(index, case.sourceBoxes),
                ),
            )

            assertEquals(case.name, case.expectedReason, result.decisions.single().reason)
            assertEquals(
                case.name,
                case.expectedReason == TextPixelMaskBuilder.Reason.ACCEPTED,
                result.decisions.single().accepted,
            )
            assertEquals(case.name, result.decisions.single().accepted, result.masks.size == 1)
        }
    }

    @Test
    fun build_tableDriven_isResolutionIndependentAndKeepsMaskInsideLocalCrop() {
        data class Case(val name: String, val scale: Int)
        val cases = listOf(Case("base resolution", 1), Case("double resolution", 2))

        cases.forEach { case ->
            val width = 80 * case.scale
            val height = 60 * case.scale
            val source = IntRect(20, 15, 40, 35).scaled(case.scale)
            val glyph = IntRect(25, 20, 29, 31).scaled(case.scale)
            val candidate = BooleanArray(width * height)
            fill(candidate, glyph, width)
            val result = TextPixelMaskBuilder.build(
                width = width,
                height = height,
                candidateTextMask = candidate,
                confirmedBlocks = listOf(
                    DelayedTextEraseMaskBuilder.ConfirmedBlock(0, listOf(source)),
                ),
            )

            val decision = result.decisions.single()
            val mask = result.masks.single()
            assertTrue(case.name, decision.accepted)
            assertTrue("${case.name}: dilation adds antialias coverage", mask.outputPixels > mask.selectedCorePixels)
            assertEquals(
                "${case.name}: exact foreground reference pixels are retained",
                mask.selectedCorePixels,
                mask.corePixels.count { it },
            )
            mask.corePixels.indices.filter { mask.corePixels[it] }.forEach { index ->
                assertTrue("${case.name}: foreground core stays inside erase mask", mask.pixels[index])
            }
            assertEquals("${case.name}: decision reports local output", mask.outputPixels, decision.outputPixels)
            assertFalse("${case.name}: local mask is not a filled rectangle", mask.pixels.all { it })
            assertTrue("${case.name}: crop contains source left", mask.bounds.left <= source.left)
            assertTrue("${case.name}: crop contains source right", mask.bounds.right >= source.right)
            val sourceCenterX = (source.left + source.right) / 2 - mask.bounds.left
            val sourceCenterY = (source.top + source.bottom) / 2 - mask.bounds.top
            assertTrue(
                "${case.name}: OCR geometry is retained for residual checks",
                mask.supportPixels[sourceCenterY * mask.bounds.width + sourceCenterX],
            )
            assertFalse(
                "${case.name}: crop sampling margin is not text support",
                mask.supportPixels.first(),
            )
        }
    }

    private fun IntRect.scaled(scale: Int) = IntRect(
        left * scale,
        top * scale,
        right * scale,
        bottom * scale,
    )

    private fun fill(mask: BooleanArray, bounds: IntRect, width: Int = WIDTH) {
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) mask[y * width + x] = true
        }
    }

    private companion object {
        const val WIDTH = 80
        const val HEIGHT = 60
    }
}
