package com.gameocr.app.ocr

import org.junit.Assert.*
import org.junit.Test
import com.gameocr.app.ocr.PaddleRecognitionCropPolicy.Bounds

class PaddleRecognitionCropPolicyTest {
    private fun pixels(scale: Int = 1, background: Int = 255, ink: Int = 0, border: Boolean = true): IntArray {
        fun color(v: Int) = -0x1000000 or (v shl 16) or (v shl 8) or v
        val w = 200 * scale; val h = 100 * scale
        return IntArray(w * h) { i ->
            val x = i % w / scale; val y = i / w / scale
            val stroke = x in 10..189 && y in 25..74 && x % 30 in 5..16
            val dot = x in 183..187 && y in 79..82
            color(if (stroke || dot || (border && y >= 95)) ink else background)
        }
    }

    @Test fun retains_all_ink_and_detached_punctuation_across_scales_and_polarities() {
        for (scale in listOf(1, 2, 4)) for ((bg, ink) in listOf(255 to 0, 0 to 255, 230 to 25)) {
            val result = PaddleRecognitionCropPolicy.propose(200 * scale, 100 * scale,
                pixels(scale, bg, ink), Bounds(20 * scale, 40 * scale, 180 * scale, 60 * scale))
            assertNotNull("scale=$scale bg=$bg", result)
            result!!
            assertTrue(result.top <= 25 * scale)
            assertTrue(result.bottom > 82 * scale)
            assertTrue(result.right > 187 * scale)
            assertTrue(result.bottom < 95 * scale)
        }
    }

    @Test fun unsafe_or_unhelpful_cases_keep_the_original_crop() {
        val support = Bounds(20, 40, 180, 60)
        data class Case(val name: String, val data: IntArray, val bounds: Bounds = support)
        val connectedToEdge = pixels().also { p -> for (y in 45..99) for (x in 35..40) p[y * 200 + x] = -0x1000000 }
        val nearEdgeInk = pixels().also { p -> p[3 * 200 + 90] = -0x1000000; p[91 * 200 + 90] = -0x1000000 }
        val cases = listOf(
            Case("blank", IntArray(20000) { -1 }),
            Case("weak contrast", pixels(background = 130, ink = 105)),
            Case("stroke connected to crop edge", connectedToEdge),
            Case("interior isolated marks must not be dropped", nearEdgeInk),
            Case("support outside crop", pixels(), Bounds(-1, 40, 180, 60)),
            Case("empty support", pixels(), Bounds(20, 40, 20, 60)),
            Case("no background samples", pixels(), Bounds(0, 0, 200, 100)),
        )
        cases.forEach { assertNull(it.name, PaddleRecognitionCropPolicy.propose(200, 100, it.data, it.bounds)) }
        assertNull(PaddleRecognitionCropPolicy.propose(100, 200, pixels(), support))
        assertNull(PaddleRecognitionCropPolicy.propose(Int.MAX_VALUE, 200, pixels(), support))
        assertNull(PaddleRecognitionCropPolicy.propose(200, 100, IntArray(1), support))
    }

    @Test fun confidence_gates_never_retry_reliable_empty_or_invalid_outputs() {
        data class Case(val score: Float, val retry: Boolean)
        listOf(Case(0f, false), Case(.01f, true), Case(.69f, true), Case(.7f, false),
            Case(1f, false), Case(-1f, false), Case(Float.NaN, false), Case(Float.POSITIVE_INFINITY, false))
            .forEach { assertEquals(it.toString(), it.retry, PaddleRecognitionCropPolicy.shouldRefine(it.score)) }
        data class Acceptance(val old: Float, val candidate: Float, val use: Boolean)
        listOf(Acceptance(.5f, .8f, true), Acceptance(.69f, .75f, false), Acceptance(.5f, .74f, false),
            Acceptance(.8f, 1f, false), Acceptance(.5f, Float.NaN, false), Acceptance(.5f, 1.1f, false))
            .forEach { assertEquals(it.toString(), it.use, PaddleRecognitionCropPolicy.accept(it.old, it.candidate)) }
    }

    @Test fun budget_bounds_number_and_total_pixels_without_integer_overflow() {
        val budget = PaddleRecognitionCropPolicy.Budget()
        repeat(4) { assertTrue(budget.reserve(200, 100)) }
        assertFalse(budget.reserve(200, 100))
        val pixels = PaddleRecognitionCropPolicy.Budget()
        assertFalse(pixels.reserve(-1, 100))
        assertFalse(pixels.reserve(Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(pixels.reserve(1000, 1000))
        assertFalse(pixels.reserve(1, 1))
    }
}
