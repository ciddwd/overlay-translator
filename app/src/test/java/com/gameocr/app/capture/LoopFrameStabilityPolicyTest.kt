package com.gameocr.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class LoopFrameStabilityPolicyTest {

    @Test
    fun beforeOcr_coversTypingStableProcessedAndContextCases() {
        data class Case(
            val name: String,
            val state: LoopFrameStabilityState,
            val current: LoopFrameFingerprint,
            val enabled: Boolean,
            val nowMs: Long,
            val expectedDecision: LoopFrameStabilityDecision,
            val expectedStableSinceMs: Long,
            val expectedProcessedHash: Long?,
            val allowTextProbe: Boolean = false,
        )

        val frameA = fingerprint(hash = 10L)
        val frameB = fingerprint(hash = 11L)
        val observingA = LoopFrameStabilityState(previous = frameA, stableSinceElapsedMs = 1000L)
        val processedA = LoopFrameStabilityPolicy.markProcessed(observingA, frameA)
        val cases = listOf(
            Case("disabled processes immediately", LoopFrameStabilityState(), frameA, false, 1000L,
                LoopFrameStabilityDecision.PROCESS, 1000L, null),
            Case("first frame starts observation", LoopFrameStabilityState(), frameA, true, 1000L,
                LoopFrameStabilityDecision.WAIT_FOR_STABLE_FRAME, 1000L, null),
            Case(
                name = "first frame immediately probes OCR when ROI optimization is available",
                state = LoopFrameStabilityState(),
                current = frameA,
                enabled = true,
                nowMs = 1000L,
                expectedDecision = LoopFrameStabilityDecision.PROBE_TEXT_STABILITY,
                expectedStableSinceMs = 1000L,
                expectedProcessedHash = null,
                allowTextProbe = true,
            ),
            Case("typing change resets timer", observingA, frameB, true, 1400L,
                LoopFrameStabilityDecision.WAIT_FOR_STABLE_FRAME, 1400L, null),
            Case("same frame before delay waits", observingA, frameA, true, 1599L,
                LoopFrameStabilityDecision.WAIT_FOR_STABLE_FRAME, 1000L, null),
            Case("same frame at delay processes", observingA, frameA, true, 1600L,
                LoopFrameStabilityDecision.PROCESS, 1000L, null),
            Case("same frame after delay processes", observingA, frameA, true, 1900L,
                LoopFrameStabilityDecision.PROCESS, 1000L, null),
            Case("processed frame is skipped", processedA, frameA, true, 1900L,
                LoopFrameStabilityDecision.SKIP_ALREADY_PROCESSED, 1000L, 10L),
            Case("clock rollback cannot satisfy delay", observingA, frameA, true, 900L,
                LoopFrameStabilityDecision.WAIT_FOR_STABLE_FRAME, 1000L, null),
            Case(
                name = "dynamic frame immediately requests text probe for ROI seeding",
                state = observingA.copy(lastTextProbeElapsedMs = 1000L),
                current = frameB,
                enabled = true,
                allowTextProbe = true,
                nowMs = 1200L,
                expectedDecision = LoopFrameStabilityDecision.PROBE_TEXT_STABILITY,
                expectedStableSinceMs = 1200L,
                expectedProcessedHash = null,
            ),
            Case(
                "context change resets observation and processed hash",
                processedA,
                fingerprint(hash = 10L, contextId = 2),
                true,
                2000L,
                LoopFrameStabilityDecision.WAIT_FOR_STABLE_FRAME,
                2000L,
                null,
            ),
        )

        cases.forEach { case ->
            val result = LoopFrameStabilityPolicy.beforeOcr(
                state = case.state,
                current = case.current,
                enabled = case.enabled,
                allowTextStabilityProbe = case.allowTextProbe,
                stableDurationMs = 600L,
                nowElapsedMs = case.nowMs,
            )
            assertEquals(case.name, case.expectedDecision, result.decision)
            assertEquals(case.name, case.expectedStableSinceMs, result.state.stableSinceElapsedMs)
            assertEquals(case.name, case.expectedProcessedHash, result.state.lastProcessedExactHash)
        }
    }

    @Test
    fun afterOcr_waitsForStableTextAndSkipsAlreadyTranslatedText() {
        data class Case(
            val name: String,
            val state: LoopFrameStabilityState,
            val trigger: LoopFrameStabilityDecision,
            val text: String,
            val nowMs: Long,
            val expected: LoopFramePostOcrDecision,
        )

        val frame = fingerprint(20L)
        val firstText = LoopFrameStabilityState(
            previous = frame,
            previousOcrText = "Hello",
            textStableSinceElapsedMs = 1000L,
        )
        val processed = LoopFrameStabilityPolicy.markProcessed(firstText, frame, "Hello")
        val cases = listOf(
            Case("stable frame translates immediately", LoopFrameStabilityState(previous = frame),
                LoopFrameStabilityDecision.PROCESS, "Hello", 1000L, LoopFramePostOcrDecision.TRANSLATE),
            Case("first text probe waits", LoopFrameStabilityState(previous = frame),
                LoopFrameStabilityDecision.PROBE_TEXT_STABILITY, "Hel", 1000L,
                LoopFramePostOcrDecision.WAIT_FOR_STABLE_TEXT),
            Case("growing text resets wait", firstText,
                LoopFrameStabilityDecision.PROBE_TEXT_STABILITY, "Hello world", 1400L,
                LoopFramePostOcrDecision.WAIT_FOR_STABLE_TEXT),
            Case("same text before delay waits", firstText,
                LoopFrameStabilityDecision.PROBE_TEXT_STABILITY, "Hello", 1599L,
                LoopFramePostOcrDecision.WAIT_FOR_STABLE_TEXT),
            Case("same text at delay translates", firstText,
                LoopFrameStabilityDecision.PROBE_TEXT_STABILITY, "Hello", 1600L,
                LoopFramePostOcrDecision.TRANSLATE),
            Case("already translated text skips", processed,
                LoopFrameStabilityDecision.PROBE_TEXT_STABILITY, "Hello", 2200L,
                LoopFramePostOcrDecision.SKIP_ALREADY_PROCESSED),
            Case("stable empty frame skips", LoopFrameStabilityState(previous = frame),
                LoopFrameStabilityDecision.PROCESS, "", 1000L,
                LoopFramePostOcrDecision.SKIP_ALREADY_PROCESSED),
        )

        cases.forEach { case ->
            val result = LoopFrameStabilityPolicy.afterOcr(
                state = case.state,
                current = frame,
                trigger = case.trigger,
                normalizedOcrText = case.text,
                enabled = true,
                stableDurationMs = 600L,
                nowElapsedMs = case.nowMs,
            )
            assertEquals(case.name, case.expected, result.decision)
        }
    }

    @Test
    fun durationAndPollingIntervals_areNormalizedForAllBoundaryCases() {
        data class DurationCase(val input: Long, val expected: Long)
        listOf(
            DurationCase(-1L, 200L),
            DurationCase(200L, 200L),
            DurationCase(600L, 600L),
            DurationCase(2000L, 2000L),
            DurationCase(5000L, 2000L),
        ).forEach { case ->
            assertEquals(
                case.expected,
                LoopFrameStabilityPolicy.normalizeStableDuration(case.input),
            )
        }

        data class PollCase(val interval: Long, val enabled: Boolean, val expected: Long)
        listOf(
            PollCase(2000L, false, 2000L),
            PollCase(2000L, true, 200L),
            PollCase(100L, true, 100L),
            PollCase(0L, false, 2000L),
            PollCase(0L, true, 200L),
        ).forEach { case ->
            assertEquals(
                case.expected,
                LoopFrameStabilityPolicy.pollingIntervalMs(case.interval, case.enabled),
            )
        }
    }

    @Test
    fun afterOcr_integratesJitterToleranceWithoutTreatingGrowingTextAsStable() {
        data class Case(
            val name: String,
            val previous: String,
            val current: String,
            val maxObservedLength: Int,
            val stableSinceMs: Long,
            val nowMs: Long,
            val expectedDecision: LoopFramePostOcrDecision,
            val expectedStableSinceMs: Long,
        )

        val stable = "A Dark Forest Man with Red Eyes You're in for one painful ride, Dante."
        listOf(
            Case(
                name = "minor OCR substitution reaches stable deadline",
                previous = stable,
                current = stable.replace("Dante", "Dant0"),
                maxObservedLength = stable.filterNot(Char::isWhitespace).length,
                stableSinceMs = 1000L,
                nowMs = 1600L,
                expectedDecision = LoopFramePostOcrDecision.TRANSLATE,
                expectedStableSinceMs = 1000L,
            ),
            Case(
                name = "continued text growth restarts deadline",
                previous = "The train is",
                current = "The train is coming",
                maxObservedLength = "Thetrainis".length,
                stableSinceMs = 1000L,
                nowMs = 1600L,
                expectedDecision = LoopFramePostOcrDecision.WAIT_FOR_STABLE_TEXT,
                expectedStableSinceMs = 1600L,
            ),
        ).forEach { case ->
            val result = LoopFrameStabilityPolicy.afterOcr(
                state = LoopFrameStabilityState(
                    previous = fingerprint(30L),
                    previousOcrText = case.previous,
                    textStableSinceElapsedMs = case.stableSinceMs,
                    maxObservedOcrTextLength = case.maxObservedLength,
                ),
                current = fingerprint(31L),
                trigger = LoopFrameStabilityDecision.PROBE_TEXT_STABILITY,
                normalizedOcrText = case.current,
                enabled = true,
                stableDurationMs = 600L,
                nowElapsedMs = case.nowMs,
            )
            assertEquals(case.name, case.expectedDecision, result.decision)
            assertEquals(case.name, case.expectedStableSinceMs, result.state.textStableSinceElapsedMs)
        }
    }

    @Test
    fun afterOcr_skipsPreviouslyTranslatedTextWithOneCharacterOcrNoise() {
        val processedText = "A Dark Forest\n???\nMan with Red Eyes\n0\n" +
            "You're in for one hell of a painful ride from here on out, Dante.\nSKIP D>"
        val noisyText = processedText.replace("\n0\n", "\n이\n0\n")
        val frame = fingerprint(40L)
        val state = LoopFrameStabilityPolicy.markProcessed(
            state = LoopFrameStabilityState(previous = frame),
            current = frame,
            normalizedOcrText = processedText,
        )

        val result = LoopFrameStabilityPolicy.afterOcr(
            state = state,
            current = fingerprint(41L),
            trigger = LoopFrameStabilityDecision.PROBE_TEXT_STABILITY,
            normalizedOcrText = noisyText,
            enabled = true,
            stableDurationMs = 600L,
            nowElapsedMs = 2000L,
        )

        assertEquals(LoopFramePostOcrDecision.SKIP_ALREADY_PROCESSED, result.decision)
        assertEquals(LoopOcrTextRelation.OCR_JITTER, result.textObservation?.relation)
        assertEquals(processedText, result.state.lastProcessedOcrText)
    }

    private fun fingerprint(
        hash: Long,
        contextId: Int = 1,
    ): LoopFrameFingerprint = LoopFrameFingerprint(
        width = 100,
        height = 200,
        contextId = contextId,
        exactHash = hash,
        luminanceSample = ByteArray(16),
    )
}
