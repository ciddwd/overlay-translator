package com.gameocr.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopRoiVisualPolicyTest {
    @Test
    fun similarity_coversExactLuminanceEdgeAndContextCases() {
        data class Case(
            val name: String,
            val previous: LoopRoiVisualFingerprint,
            val current: LoopRoiVisualFingerprint,
            val expected: Float,
        )

        val base = fingerprint(luminance = byteArrayOf(10, 20), edges = byteArrayOf(30, 40))
        val cases = listOf(
            Case("exact", base, base.copy(), 1f),
            Case(
                "luminance-only difference is weighted",
                base,
                fingerprint(luminance = byteArrayOf(20, 30), edges = byteArrayOf(30, 40)),
                1f - (10f / 255f) * 0.35f,
            ),
            Case(
                "edge difference dominates text change",
                base,
                fingerprint(luminance = byteArrayOf(10, 20), edges = byteArrayOf(130.toByte(), 140.toByte())),
                0.35f,
            ),
            Case("context mismatch", base, base.copy(contextId = 2), 0f),
            Case("ROI mismatch", base, base.copy(roi = LoopTextRect(1, 0, 11, 10)), 0f),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LoopRoiVisualPolicy.similarity(case.previous, case.current),
                0.0001f,
            )
        }
    }

    @Test
    fun similarity_rejectsIncompatibleSamples() {
        val base = fingerprint(luminance = byteArrayOf(1), edges = byteArrayOf(1))
        val incompatible = fingerprint(luminance = byteArrayOf(1, 2), edges = byteArrayOf(1, 2))
        assertTrue(LoopRoiVisualPolicy.similarity(base, incompatible) < 0.01f)
    }

    @Test
    fun similarity_detectsSmallLocalizedTextEdgeChangeAtDefaultThreshold() {
        val unchanged = ByteArray(100) { 10 }
        val changed = unchanged.copyOf().apply { this[50] = 100.toByte() }
        val previous = fingerprint(luminance = unchanged, edges = unchanged)
        val current = fingerprint(luminance = unchanged, edges = changed)

        assertTrue(LoopRoiVisualPolicy.similarity(previous, current) < 0.95f)
    }

    private fun fingerprint(
        luminance: ByteArray = byteArrayOf(10),
        edges: ByteArray = byteArrayOf(10),
    ) = LoopRoiVisualFingerprint(
        contextId = 1,
        roi = LoopTextRect(0, 0, 10, 10),
        luminance = luminance,
        edges = edges,
    )
}
