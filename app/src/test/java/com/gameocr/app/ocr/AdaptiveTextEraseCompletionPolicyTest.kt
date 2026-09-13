package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveTextEraseCompletionPolicyTest {

    @Test
    fun refine_tableDriven_expandsOnlyAcrossAContinuousBackground() {
        data class Case(
            val name: String,
            val source: IntArray,
            val expectedReliable: Boolean,
            val expectedStrategy: AdaptiveTextEraseCompletionPolicy.Strategy,
        )

        val semantic = BooleanArray(SIZE).apply { fill(this, IntRect(8, 8, 32, 32)) }
        val support = BooleanArray(SIZE).apply { fill(this, IntRect(16, 14, 24, 26)) }
        val seed = BooleanArray(SIZE).apply { fill(this, IntRect(18, 16, 22, 24)) }
        val flat = IntArray(SIZE) { gray(246) }.apply {
            seed.indices.filter { seed[it] }.forEach { this[it] = gray(8) }
        }
        val checker = IntArray(SIZE) { index ->
            val x = index % WIDTH
            val y = index / WIDTH
            gray(if ((x + y) % 2 == 0) 35 else 220)
        }
        val discontinuous = flat.copyOf().apply {
            for (y in 7..32) {
                this[y * WIDTH + 7] = gray(20)
                this[y * WIDTH + 32] = gray(20)
            }
            for (x in 7..32) {
                this[7 * WIDTH + x] = gray(20)
                this[32 * WIDTH + x] = gray(20)
            }
        }

        listOf(
            Case(
                name = "flat speech bubble completes the semantic text region",
                source = flat,
                expectedReliable = true,
                expectedStrategy = AdaptiveTextEraseCompletionPolicy.Strategy.ADAPTIVE_GROWTH,
            ),
            Case(
                name = "busy artwork rejects broad completion",
                source = checker,
                expectedReliable = false,
                expectedStrategy = AdaptiveTextEraseCompletionPolicy.Strategy.REJECTED,
            ),
            Case(
                name = "a boundary crossing is rejected even when each side is flat",
                source = discontinuous,
                expectedReliable = false,
                expectedStrategy = AdaptiveTextEraseCompletionPolicy.Strategy.REJECTED,
            ),
        ).forEach { case ->
            val result = AdaptiveTextEraseCompletionPolicy.refine(
                width = WIDTH,
                height = HEIGHT,
                sourceArgb = case.source,
                seedMask = seed,
                supportMask = support,
                semanticMask = semantic,
            )

            assertEquals(case.name, case.expectedReliable, result.reliable)
            assertEquals(case.name, case.expectedStrategy, result.strategy)
            if (case.expectedReliable) {
                assertTrue(case.name, result.semanticCoverage >= 0.98f)
            } else {
                assertTrue(case.name, result.semanticCoverage < 0.98f)
            }
        }
    }

    @Test
    fun refine_observedSupportAlreadyCoversSemanticRegion_withoutImageGuessing() {
        val semantic = BooleanArray(SIZE).apply { fill(this, IntRect(15, 15, 24, 24)) }
        val result = AdaptiveTextEraseCompletionPolicy.refine(
            width = WIDTH,
            height = HEIGHT,
            sourceArgb = IntArray(SIZE) { index -> gray(index % 256) },
            seedMask = semantic,
            supportMask = semantic,
            semanticMask = semantic,
        )

        assertTrue(result.reliable)
        assertEquals(
            AdaptiveTextEraseCompletionPolicy.Strategy.OBSERVED_SUPPORT,
            result.strategy,
        )
        assertTrue(result.mask.contentEquals(semantic))
    }

    @Test
    fun refine_neverExpandsOutsideSemanticRegionExceptExistingSeedAndSupport() {
        val semantic = BooleanArray(SIZE).apply { fill(this, IntRect(8, 8, 32, 32)) }
        val support = BooleanArray(SIZE).apply { fill(this, IntRect(16, 16, 24, 24)) }
        val result = AdaptiveTextEraseCompletionPolicy.refine(
            width = WIDTH,
            height = HEIGHT,
            sourceArgb = IntArray(SIZE) { gray(245) },
            seedMask = support,
            supportMask = support,
            semanticMask = semantic,
        )

        result.mask.indices.forEach { index ->
            if (!semantic[index]) assertFalse("outside semantic region", result.mask[index])
        }
    }

    private fun fill(mask: BooleanArray, bounds: IntRect) {
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) mask[y * WIDTH + x] = true
        }
    }

    private fun gray(value: Int): Int =
        (255 shl 24) or (value shl 16) or (value shl 8) or value

    private companion object {
        const val WIDTH = 40
        const val HEIGHT = 40
        const val SIZE = WIDTH * HEIGHT
    }
}
