package com.gameocr.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopFrameChangePolicyTest {

    @Test
    fun beforeOcr_coversFirstDisabledContextExactAndSimilarityCases() {
        data class Case(
            val name: String,
            val previous: LoopFrameFingerprint?,
            val current: LoopFrameFingerprint,
            val enabled: Boolean,
            val threshold: Float,
            val expected: LoopFramePreOcrDecision,
        )

        val base = fingerprint(hash = 11L, sampleValue = 100)
        val cases = listOf(
            Case("first frame", null, base, true, 0.95f, LoopFramePreOcrDecision.PROCESS),
            Case("dedup disabled", base, base, false, 0.95f, LoopFramePreOcrDecision.PROCESS),
            Case(
                "capture context changed",
                base,
                fingerprint(hash = 11L, sampleValue = 100, contextId = 2),
                true,
                0.95f,
                LoopFramePreOcrDecision.PROCESS,
            ),
            Case(
                "dimensions changed",
                base,
                fingerprint(hash = 11L, sampleValue = 100, width = 200),
                true,
                0.95f,
                LoopFramePreOcrDecision.PROCESS,
            ),
            Case(
                "exact frame",
                base,
                fingerprint(hash = 11L, sampleValue = 130),
                true,
                0.95f,
                LoopFramePreOcrDecision.SKIP_EXACT_FRAME,
            ),
            Case(
                "similar frame above threshold",
                base,
                fingerprint(hash = 12L, sampleValue = 110),
                true,
                0.95f,
                LoopFramePreOcrDecision.PROCESS_SIMILAR_FRAME,
            ),
            Case(
                "changed frame below threshold",
                base,
                fingerprint(hash = 12L, sampleValue = 140),
                true,
                0.95f,
                LoopFramePreOcrDecision.PROCESS,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LoopFrameChangePolicy.beforeOcr(
                    previous = case.previous,
                    current = case.current,
                    enabled = case.enabled,
                    similarityThreshold = case.threshold,
                ).decision,
            )
        }
    }

    @Test
    fun shouldSkipTranslation_requiresSimilarFrameAndSameNormalizedOcrText() {
        data class Case(
            val name: String,
            val decision: LoopFramePreOcrDecision,
            val previous: String?,
            val current: String,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("same similar text", LoopFramePreOcrDecision.PROCESS_SIMILAR_FRAME, "hello", "hello", true),
            Case("changed similar text", LoopFramePreOcrDecision.PROCESS_SIMILAR_FRAME, "hello", "world", false),
            Case("no previous text", LoopFramePreOcrDecision.PROCESS_SIMILAR_FRAME, null, "hello", false),
            Case("changed frame", LoopFramePreOcrDecision.PROCESS, "hello", "hello", false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LoopFrameChangePolicy.shouldSkipTranslation(
                    case.decision,
                    case.previous,
                    case.current,
                ),
            )
        }
    }

    @Test
    fun normalizeOcrText_preservesMeaningfulChangesAndIgnoresWhitespaceNoise() {
        assertEquals(
            "Hello world\nNEXT",
            LoopFrameChangePolicy.normalizeOcrText(listOf("  Hello   world ", "\nNEXT\t")),
        )
        assertFalse(
            LoopFrameChangePolicy.normalizeOcrText(listOf("Text")) ==
                LoopFrameChangePolicy.normalizeOcrText(listOf("text")),
        )
    }

    @Test
    fun thresholdAndSimilarity_areBoundedAndDeterministic() {
        data class ThresholdCase(val input: Float, val expected: Float)
        listOf(
            ThresholdCase(0.10f, 0.50f),
            ThresholdCase(0.95f, 0.95f),
            ThresholdCase(1.50f, 0.99f),
            ThresholdCase(Float.NaN, 0.95f),
        ).forEach { case ->
            assertEquals(case.expected, LoopFrameChangePolicy.normalizeThreshold(case.input), 0.0001f)
        }

        assertEquals(1f, LoopFrameChangePolicy.similarity(bytes(80), bytes(80)), 0.0001f)
        assertTrue(LoopFrameChangePolicy.similarity(bytes(80), bytes(90)) > 0.95f)
        assertTrue(LoopFrameChangePolicy.similarity(bytes(80), bytes(120)) < 0.95f)
        assertEquals(0f, LoopFrameChangePolicy.similarity(byteArrayOf(1), byteArrayOf(1, 2)), 0.0001f)
    }

    private fun fingerprint(
        hash: Long,
        sampleValue: Int,
        width: Int = 100,
        height: Int = 200,
        contextId: Int = 1,
    ): LoopFrameFingerprint = LoopFrameFingerprint(
        width = width,
        height = height,
        contextId = contextId,
        exactHash = hash,
        luminanceSample = bytes(sampleValue),
    )

    private fun bytes(value: Int): ByteArray = ByteArray(16) { value.toByte() }
}
