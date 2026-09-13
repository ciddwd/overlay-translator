package com.gameocr.app.ocr

import com.gameocr.app.data.PaddleDetectionProfile
import com.gameocr.app.data.PaddleModelVersion
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PaddleMultiScaleDetectionPolicyTest {
    private fun q(l: Float, t: Float, r: Float, b: Float) = DBPostprocessor.Quad(
        paddlePointF(l, t), paddlePointF(r, t), paddlePointF(r, b), paddlePointF(l, b),
    )
    private val fragments = listOf(q(10f, 60f, 40f, 90f), q(60f, 60f, 90f, 90f))
    private val parent = q(0f, 0f, 100f, 100f)

    @Test fun containment_uses_detector_support_but_does_not_tolerate_cut_text() {
        val crop = q(0f, 0f, 100f, 100f)
        data class Case(val name: String, val support: DBPostprocessor.Quad?, val replaced: Int)
        val cases = listOf(
            Case("padding outside parent", q(20f, 80f, 40f, 98f), 2),
            Case("real text partly cut", q(20f, 88f, 80f, 108f), 0),
            Case("neighbour below parent", q(20f, 105f, 80f, 125f), 0),
            Case("metadata unavailable stays conservative", null, 0),
        )
        cases.forEach { case ->
            val child = q(10f, 70f, 50f, 120f).copy(support = case.support)
            val other = q(60f, 70f, 90f, 100f)
            val result = PaddleMultiScaleDetectionPolicy.select(listOf(child, other), listOf(crop))
            assertEquals(case.name, case.replaced, result.replacedCount)
        }
        val child = q(10f, 70f, 50f, 120f).copy(support = q(20f, 80f, 40f, 98f))
        val other = q(60f, 70f, 90f, 100f)
        val neighbour = q(70f, 75f, 150f, 130f).copy(support = q(80f, 85f, 140f, 120f))
        assertEquals(0, PaddleMultiScaleDetectionPolicy.select(listOf(child, other, neighbour), listOf(crop)).replacedCount)
        val shifted = child.offsetBy(500f, 1000f)
        assertEquals(520f, shifted.support!!.p0.x, 0.001f)
        assertEquals(2, PaddleMultiScaleDetectionPolicy.select(listOf(shifted, other.offsetBy(500f, 1000f)), listOf(crop.offsetBy(500f, 1000f))).replacedCount)
    }

    @Test fun geometry_cases_preserve_small_text_and_reject_ambiguous_parents() {
        data class Case(val name: String, val normal: List<DBPostprocessor.Quad>, val coarse: List<DBPostprocessor.Quad>, val replaced: Int)
        val small = q(130f, 10f, 210f, 30f)
        val cases = listOf(
            Case("two fragments", fragments, listOf(parent), 2),
            Case("small neighbour preserved", fragments + small, listOf(parent, small), 2),
            Case("no coarse detection", fragments, emptyList(), 0),
            Case("empty image", emptyList(), listOf(parent), 0),
            Case("same box", listOf(parent), listOf(parent), 0),
            Case("ordinary padding", fragments, fragments.map { it.offsetBy(1f, 1f) }, 0),
            Case("two text rows", listOf(q(10f, 5f, 90f, 35f), q(10f, 60f, 90f, 90f)), listOf(parent), 0),
            Case("unequal complete rows", listOf(q(10f, 5f, 61f, 35f), q(10f, 60f, 90f, 90f)), listOf(parent), 0),
            Case("upper stroke and lower fragments", listOf(q(55f, 5f, 90f, 30f), q(10f, 60f, 90f, 90f)), listOf(parent), 2),
            Case("single icon is not fragmented text", listOf(q(20f, 60f, 80f, 90f)), listOf(parent), 0),
            Case("neighbour partly overlapped", fragments + q(90f, 20f, 140f, 50f), listOf(parent), 0),
            Case("too much background", fragments, listOf(q(-100f, -100f, 200f, 200f)), 0),
            Case("distant columns", listOf(q(10f, 60f, 40f, 90f), q(260f, 60f, 290f, 90f)), listOf(q(0f, 0f, 300f, 100f)), 0),
            Case("duplicate parents", fragments, listOf(parent, parent), 2),
            Case("unrelated new region", fragments, listOf(q(300f, 0f, 400f, 100f)), 0),
            Case("zero size", fragments, listOf(q(0f, 0f, 0f, 0f)), 0),
            Case("invalid coordinates", fragments, listOf(q(Float.NaN, 0f, 100f, 100f)), 0),
        )
        cases.forEach { case ->
            val result = PaddleMultiScaleDetectionPolicy.select(case.normal, case.coarse)
            assertEquals(case.name, case.replaced, result.replacedCount)
            assertEquals(case.name, case.normal.size - result.replacedCount + result.recovered.size, result.quads.size)
            if (case.replaced == 0) assertEquals(case.name, case.normal, result.quads)
            if (case.normal.any { it === small }) {
                assertTrue(case.name, result.quads.any { it === small })
            }
        }
    }

    @Test fun selection_is_scale_translation_and_rotation_invariant() {
        for (scale in listOf(0.25f, 1f, 4f)) for (degrees in listOf(0, 15, -30, 90, 180, 270)) {
            val angle = Math.toRadians(degrees.toDouble())
            fun transform(quad: DBPostprocessor.Quad): DBPostprocessor.Quad {
                val points = listOf(quad.p0, quad.p1, quad.p2, quad.p3).map {
                    paddlePointF(
                        ((it.x * cos(angle) - it.y * sin(angle)) * scale + 700).toFloat(),
                        ((it.x * sin(angle) + it.y * cos(angle)) * scale + 300).toFloat(),
                    )
                }
                return DBPostprocessor.Quad(points[0], points[1], points[2], points[3])
            }
            val result = PaddleMultiScaleDetectionPolicy.select(fragments.map(::transform), listOf(transform(parent)))
            assertEquals("scale=$scale rotation=$degrees", 2, result.replacedCount)
        }
    }

    @Test fun half_scale_is_bounded_and_enabled_only_for_validated_model() {
        for (version in PaddleModelVersion.entries) {
            val plan = PaddleDetectionSizing.plan(1440, 3200, PaddleDetectionProfile.FAST)
            assertEquals(version.name, if (version == PaddleModelVersion.V5_KOREAN) 480 else null,
                PaddleMultiScaleDetectionPolicy.coarseLimit(version, plan))
        }
        for (size in listOf(32 to 32, 500 to 24)) {
            val plan = PaddleDetectionSizing.plan(size.first, size.second, PaddleDetectionProfile.FAST)
            assertNull(PaddleMultiScaleDetectionPolicy.coarseLimit(PaddleModelVersion.V5_KOREAN, plan))
        }
        val plan = PaddleDetectionSizing.plan(886, 806, PaddleDetectionProfile.FAST)
        val limit = PaddleMultiScaleDetectionPolicy.coarseLimit(PaddleModelVersion.V5_KOREAN, plan)!!
        assertEquals(448, limit)
        val coarse = PaddleDetectionSizing.plan(886, 806, PaddleDetectionProfile.FAST, limit)
        assertTrue(coarse.inputPixels < plan.inputPixels / 3)
        assertEquals(886f / coarse.targetWidth, coarse.scaleX, 0.0001f)
        assertEquals(806f / coarse.targetHeight, coarse.scaleY, 0.0001f)
    }

    @Test fun supplemental_mask_is_clipped_to_accepted_recovery_and_keeps_existing_pixels() {
        for (allowed in listOf<List<DBPostprocessor.Quad>?>(null, emptyList(), listOf(q(2f, 2f, 6f, 6f)))) {
            val mask = MangaProbabilityMaskAccumulator(10, 10)
            mask.merge(arrayOf(floatArrayOf(1f)), 1f, 1f, 0, 0, .25f)
            mask.merge(Array(5) { FloatArray(5) { 1f } }, 2f, 2f, 0, 0, .25f, allowed)
            val pixels = mask.snapshot()
            assertTrue(pixels[0])
            val expected = when { allowed == null -> 100; allowed.isEmpty() -> 1; else -> 17 }
            assertEquals(expected, pixels.count { it })
        }
    }
}
