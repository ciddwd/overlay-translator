package com.gameocr.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopRoiStabilityPolicyTest {
    @Test
    fun observe_coversCachedFinalOcrAndFallbackPaths() {
        data class Case(
            val name: String,
            val state: LoopRoiStabilityState,
            val current: LoopRoiVisualFingerprint,
            val nowMs: Long,
            val expected: LoopRoiStabilityDecision,
            val expectedChanged: Boolean,
        )

        val frameA = fingerprint(10)
        val frameB = fingerprint(200)
        val initial = LoopRoiStabilityPolicy.start(frameA, 1000L)
        val changed = initial.copy(
            previous = frameB,
            stableSinceElapsedMs = 1200L,
            changedSinceInitialOcr = true,
            changedObservationCount = 1,
        )
        val cases = listOf(
            Case("unchanged before deadline waits", initial, frameA, 1599L,
                LoopRoiStabilityDecision.WAIT, false),
            Case("unchanged at deadline reuses first OCR", initial, frameA, 1600L,
                LoopRoiStabilityDecision.TRANSLATE_CACHED, false),
            Case("changed frame resets stable timer", initial, frameB, 1200L,
                LoopRoiStabilityDecision.WAIT, true),
            Case("changed then stable requests one final ROI OCR", changed, frameB, 1800L,
                LoopRoiStabilityDecision.RUN_FINAL_ROI_OCR, true),
            Case("dynamic ROI waits for third changed observation", changed, frameA, 3000L,
                LoopRoiStabilityDecision.WAIT, true),
            Case("dynamic ROI times out after three observations", changed.copy(changedObservationCount = 2), frameA, 3000L,
                LoopRoiStabilityDecision.FALLBACK_TO_TEXT_STABILITY, true),
            Case("context mismatch counts as change", initial, frameA.copy(contextId = 2), 1200L,
                LoopRoiStabilityDecision.WAIT, true),
        )

        cases.forEach { case ->
            val result = LoopRoiStabilityPolicy.observe(
                state = case.state,
                current = case.current,
                similarityThreshold = 0.95f,
                stableDurationMs = 600L,
                nowElapsedMs = case.nowMs,
            )
            assertEquals(case.name, case.expected, result.decision)
            assertEquals(case.name, case.expectedChanged, result.state.changedSinceInitialOcr)
        }
    }

    @Test
    fun observe_normalizesThresholdDurationAndClockRollback() {
        val frame = fingerprint(10)
        val state = LoopRoiStabilityPolicy.start(frame, 1000L)
        val result = LoopRoiStabilityPolicy.observe(
            state = state,
            current = frame,
            similarityThreshold = Float.NaN,
            stableDurationMs = -1L,
            nowElapsedMs = 900L,
        )
        assertEquals(LoopRoiStabilityDecision.WAIT, result.decision)
        assertTrue(result.similarity >= 0.99f)
    }

    private fun fingerprint(value: Int) = LoopRoiVisualFingerprint(
        contextId = 1,
        roi = LoopTextRect(0, 0, 10, 10),
        luminance = byteArrayOf(value.toByte(), value.toByte()),
        edges = byteArrayOf(value.toByte(), value.toByte()),
    )
}
