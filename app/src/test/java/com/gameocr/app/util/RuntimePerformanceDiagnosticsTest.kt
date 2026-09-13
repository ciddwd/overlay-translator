package com.gameocr.app.util

import com.gameocr.app.data.LogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformanceDiagnosticsTest {
    @Test
    fun anomalyPolicy_tableDriven_comparesAgainstRecentMedian() {
        data class Case(
            val name: String,
            val samples: List<Long>,
            val elapsedMs: Long,
            val minimumDeltaMs: Long,
            val expectedAnomaly: Boolean,
            val expectedBaselineMs: Long?,
        )

        listOf(
            Case("no baseline", emptyList(), 10_000, 1_000, false, null),
            Case("two samples are insufficient", listOf(900, 1_000), 4_000, 1_000, false, null),
            Case("normal variance", listOf(900, 1_000, 1_100), 1_500, 500, false, 1_000),
            Case("relative but trivial slowdown", listOf(10, 11, 12), 25, 100, false, 11),
            Case("clear slowdown", listOf(900, 1_000, 1_100), 3_000, 1_500, true, 1_000),
            Case("invalid historic samples ignored", listOf(-1, 900, 1_000, 1_100), 3_000, 1_500, true, 1_000),
        ).forEach { case ->
            val decision = RuntimePerformanceAnomalyPolicy.evaluate(
                recentSamplesMs = case.samples,
                elapsedMs = case.elapsedMs,
                minimumSlowdownMs = case.minimumDeltaMs,
            )
            assertEquals(case.name, case.expectedAnomaly, decision.anomalous)
            assertEquals(case.name, case.expectedBaselineMs, decision.baselineMs)
        }
    }

    @Test
    fun operationKeys_tableDriven_separateUnlikeWorkloadsWithoutContent() {
        data class Case(val name: String, val actual: String, val expected: String)

        listOf(
            Case(
                "small bitmap",
                RuntimePerformanceKeyPolicy.bitmap("ML_KIT_JAPANESE", 100, 100),
                "ML_KIT_JAPANESE/pixels-2^13",
            ),
            Case(
                "full hd bitmap",
                RuntimePerformanceKeyPolicy.bitmap("ML_KIT_JAPANESE", 1080, 1920),
                "ML_KIT_JAPANESE/pixels-2^20",
            ),
            Case(
                "translation batch",
                RuntimePerformanceKeyPolicy.text("OPENAI/batch", 9, 240),
                "OPENAI/batch/items-2^3/chars-2^7",
            ),
            Case(
                "empty input",
                RuntimePerformanceKeyPolicy.text("OPENAI/single", 0, 0),
                "OPENAI/single/items-2^0/chars-2^0",
            ),
        ).forEach { case -> assertEquals(case.name, case.expected, case.actual) }
    }

    @Test
    fun resourceSampler_tableDriven_computesProcessCpuAndMemory() {
        data class Point(val wallMs: Long, val cpuMs: Long, val pssKb: Int)

        listOf(
            "normal interval" to listOf(Point(1_000, 100, 512_000), Point(2_000, 350, 614_400)),
            "zero wall interval" to listOf(Point(5_000, 200, 1_024), Point(5_000, 250, 2_048)),
            "cpu clock reset" to listOf(Point(8_000, 400, 1_024), Point(9_000, 100, 3_072)),
        ).forEach { (name, points) ->
            var index = 0
            val sampler = RuntimeResourceSampler(
                elapsedRealtimeMs = { points[index].wallMs },
                processCpuTimeMs = { points[index].cpuMs },
                totalPssKb = { points[index].pssKb.toLong() },
            )
            val first = sampler.sample()
            index = 1
            val second = sampler.sample()

            assertNull(name, first.cpuCoreUsage)
            when (name) {
                "normal interval" -> assertEquals(name, 0.25f, second.cpuCoreUsage ?: -1f, 0.001f)
                else -> assertNull(name, second.cpuCoreUsage)
            }
            assertEquals(name, (points[1].pssKb + 512) / 1024, second.memoryMb)
        }
    }

    @Test
    fun resourceSampler_tableDriven_usesTwoSecondRollingCoreAverage() {
        data class Point(val wallMs: Long, val cpuMs: Long)
        data class Case(
            val name: String,
            val points: List<Point>,
            val expectedCoreUsage: List<Float?>,
        )

        listOf(
            Case(
                name = "weighted rolling window",
                points = listOf(
                    Point(0, 0),
                    Point(1_000, 1_000),
                    Point(2_000, 3_000),
                    Point(3_000, 4_000),
                    Point(4_000, 4_500),
                ),
                expectedCoreUsage = listOf(null, 1.0f, 1.5f, 1.5f, 0.75f),
            ),
            Case(
                name = "long interval is clipped to window",
                points = listOf(Point(0, 0), Point(4_000, 8_000)),
                expectedCoreUsage = listOf(null, 2.0f),
            ),
            Case(
                name = "invalid clock clears history",
                points = listOf(Point(1_000, 1_000), Point(1_000, 1_200), Point(2_000, 1_700)),
                expectedCoreUsage = listOf(null, null, 0.5f),
            ),
        ).forEach { case ->
            var index = 0
            val sampler = RuntimeResourceSampler(
                elapsedRealtimeMs = { case.points[index].wallMs },
                processCpuTimeMs = { case.points[index].cpuMs },
                totalPssKb = { 0L },
            )

            case.expectedCoreUsage.forEachIndexed { pointIndex, expected ->
                index = pointIndex
                val actual = sampler.sample().cpuCoreUsage
                if (expected == null) {
                    assertNull(case.name, actual)
                } else {
                    assertEquals(case.name, expected, actual ?: -1f, 0.001f)
                }
            }
        }
    }

    @Test
    fun performanceValueFormatter_tableDriven_formatsCompactValues() {
        data class Case(val name: String, val actual: String, val expected: String)

        listOf(
            Case("missing duration", RuntimePerformanceValueFormatter.duration(null), "—"),
            Case("milliseconds", RuntimePerformanceValueFormatter.duration(842), "842 ms"),
            Case("seconds", RuntimePerformanceValueFormatter.duration(2_370), "2.37 s"),
            Case("tokens", RuntimePerformanceValueFormatter.tokens(39), "39 tokens"),
            Case("estimated tokens", RuntimePerformanceValueFormatter.estimatedTokens(30), "~30 tokens"),
            Case("missing tokens", RuntimePerformanceValueFormatter.tokens(null), "—"),
            Case("token rate", RuntimePerformanceValueFormatter.tokensPerSecond(26.57), "26.6 tok/s"),
            Case(
                "estimated token rate",
                RuntimePerformanceValueFormatter.estimatedTokensPerSecond(49.08),
                "~49.1 tok/s",
            ),
            Case("missing token rate", RuntimePerformanceValueFormatter.tokensPerSecond(null), "—"),
            Case("memory", RuntimePerformanceValueFormatter.memory(612), "612 MB"),
            Case("cpu cores", RuntimePerformanceValueFormatter.cpuCores(1.84f), "1.8"),
        ).forEach { case -> assertEquals(case.name, case.expected, case.actual) }
    }

    @Test
    fun llmMetrics_tableDriven_usesDecodeWindowAndRejectsInvalidValues() {
        data class Case(
            val name: String,
            val initMs: Double?,
            val firstTokenMs: Long?,
            val totalMs: Long,
            val inputTokens: Int?,
            val outputTokens: Int?,
            val reportedRate: Double?,
            val expected: RuntimeLlmInferenceMetrics,
        )

        listOf(
            Case(
                name = "streamed decode rate excludes first token",
                initMs = 0.0,
                firstTokenMs = 611,
                totalMs = 2_041,
                inputTokens = 30,
                outputTokens = 39,
                reportedRate = null,
                expected = RuntimeLlmInferenceMetrics(
                    initMs = 0.0,
                    prefillMs = 611.0,
                    decodeMs = 1_430.0,
                    totalMs = 2_041.0,
                    inputTokens = 30,
                    outputTokens = 39,
                    prefillTokensPerSecond = 30_000.0 / 611.0,
                    decodeTokensPerSecond = 38_000.0 / 1_430.0,
                ),
            ),
            Case(
                name = "non stream does not invent phase timings",
                initMs = null,
                firstTokenMs = null,
                totalMs = 2_000,
                inputTokens = 30,
                outputTokens = 40,
                reportedRate = null,
                expected = RuntimeLlmInferenceMetrics(null, null, null, 2_000.0, 30, 40, null, null),
            ),
            Case(
                name = "provider rate wins",
                initMs = null,
                firstTokenMs = 500,
                totalMs = 2_000,
                inputTokens = 30,
                outputTokens = 40,
                reportedRate = 31.25,
                expected = RuntimeLlmInferenceMetrics(null, 500.0, 1_500.0, 2_000.0, 30, 40, 60.0, 31.25),
            ),
            Case(
                name = "invalid values become unavailable",
                initMs = -1.0,
                firstTokenMs = -1,
                totalMs = -5,
                inputTokens = -2,
                outputTokens = -3,
                reportedRate = Double.NaN,
                expected = RuntimeLlmInferenceMetrics(null, null, null, 0.0, null, null, null, null),
            ),
        ).forEach { case ->
            val actual = RuntimeLlmInferenceMetricsPolicy.resolve(
                initMs = case.initMs,
                firstTokenMs = case.firstTokenMs,
                totalMs = case.totalMs,
                inputTokens = case.inputTokens,
                outputTokens = case.outputTokens,
                reportedTokensPerSecond = case.reportedRate,
            )
            assertEquals(case.name, case.expected.initMs, actual.initMs)
            assertEquals(case.name, case.expected.prefillMs, actual.prefillMs)
            assertEquals(case.name, case.expected.decodeMs, actual.decodeMs)
            assertEquals(case.name, case.expected.totalMs, actual.totalMs, 0.001)
            assertEquals(case.name, case.expected.inputTokens, actual.inputTokens)
            assertEquals(case.name, case.expected.outputTokens, actual.outputTokens)
            if (case.expected.prefillTokensPerSecond == null) {
                assertNull(case.name, actual.prefillTokensPerSecond)
            } else {
                assertEquals(
                    case.name,
                    case.expected.prefillTokensPerSecond,
                    actual.prefillTokensPerSecond ?: -1.0,
                    0.001,
                )
            }
            if (case.expected.decodeTokensPerSecond == null) {
                assertNull(case.name, actual.decodeTokensPerSecond)
            } else {
                assertEquals(
                    case.name,
                    case.expected.decodeTokensPerSecond,
                    actual.decodeTokensPerSecond ?: -1.0,
                    0.001,
                )
            }
        }
    }

    @Test
    fun performanceOverlay_translationMetricsAreClearedPerRequestAndRecordedWhenEnabled() {
        var wallMs = 1_000L
        var cpuMs = 100L
        val diagnostics = RuntimePerformanceDiagnostics(
            LogRepository(),
            RuntimeResourceSampler(
                elapsedRealtimeMs = { wallMs.also { wallMs += 100L } },
                processCpuTimeMs = { cpuMs.also { cpuMs += 10L } },
                totalPssKb = { 128L * 1024L },
            ),
        )
        diagnostics.setPerformanceOverlayEnabled(true)
        diagnostics.recordLlmInference(
            firstTokenMs = 600,
            totalMs = 2_000,
            inputTokens = 30,
            outputTokens = 39,
            initMs = 1.0,
        )

        val recorded = diagnostics.performanceSnapshot.value
        assertEquals(1.0, recorded.translationInitMs ?: -1.0, 0.001)
        assertEquals(600.0, recorded.translationPrefillMs ?: -1.0, 0.001)
        assertEquals(1_400.0, recorded.translationDecodeMs ?: -1.0, 0.001)
        assertEquals(2_000.0, recorded.translationInferenceTotalMs ?: -1.0, 0.001)
        assertEquals(30, recorded.translationInputTokens)
        assertEquals(39, recorded.translationOutputTokens)
        assertNotNull(recorded.translationPrefillTokensPerSecond)
        assertNotNull(recorded.translationDecodeTokensPerSecond)

        diagnostics.beginTranslation()
        val cleared = diagnostics.performanceSnapshot.value
        assertNull(cleared.translationElapsedMs)
        assertNull(cleared.translationInferenceTotalMs)
        assertNull(cleared.translationInitMs)
        assertNull(cleared.translationPrefillMs)
        assertNull(cleared.translationDecodeMs)
        assertNull(cleared.translationInputTokens)
        assertNull(cleared.translationOutputTokens)
        assertNull(cleared.translationPrefillTokensPerSecond)
        assertNull(cleared.translationDecodeTokensPerSecond)

        diagnostics.setPerformanceOverlayEnabled(false)
        diagnostics.recordLlmInference(100, 200, 3, 4)
        assertEquals(RuntimePerformanceSnapshot(), diagnostics.performanceSnapshot.value)
    }

    @Test
    fun performanceOverlay_recordsExactNativePhasesAndThroughput() {
        val diagnostics = RuntimePerformanceDiagnostics(
            LogRepository(),
            RuntimeResourceSampler(
                elapsedRealtimeMs = { 1_000L },
                processCpuTimeMs = { 100L },
                totalPssKb = { 128L * 1024L },
            ),
        )
        diagnostics.setPerformanceOverlayEnabled(true)

        diagnostics.recordLlmInferencePhases(
            initMs = 0.1,
            prefillMs = 611.0,
            decodeMs = 1_430.0,
            totalMs = 2_041.1,
            inputTokens = 30,
            outputTokens = 39,
        )

        val snapshot = diagnostics.performanceSnapshot.value
        assertEquals(0.1, snapshot.translationInitMs ?: -1.0, 0.001)
        assertEquals(611.0, snapshot.translationPrefillMs ?: -1.0, 0.001)
        assertEquals(1_430.0, snapshot.translationDecodeMs ?: -1.0, 0.001)
        assertEquals(2_041.1, snapshot.translationInferenceTotalMs ?: -1.0, 0.001)
        assertEquals(30_000.0 / 611.0, snapshot.translationPrefillTokensPerSecond ?: -1.0, 0.001)
        assertEquals(39_000.0 / 1_430.0, snapshot.translationDecodeTokensPerSecond ?: -1.0, 0.001)
    }

    @Test
    fun performanceOverlay_recordsOnlyOcrAndTranslation_tableDriven() {
        var wallMs = 1_000L
        var cpuMs = 100L
        val diagnostics = RuntimePerformanceDiagnostics(
            LogRepository(),
            RuntimeResourceSampler(
                elapsedRealtimeMs = { wallMs.also { wallMs += 100L } },
                processCpuTimeMs = { cpuMs.also { cpuMs += 20L } },
                totalPssKb = { (256 * 1024).toLong() },
            ),
        )
        diagnostics.setPerformanceOverlayEnabled(true)

        listOf(
            RuntimePerformanceStage.CAPTURE to 50L,
            RuntimePerformanceStage.OCR to 840L,
            RuntimePerformanceStage.TRANSLATION to 2_370L,
        ).forEachIndexed { index, (stage, elapsed) ->
            diagnostics.observe(
                stage = stage,
                operationKey = stage.name,
                elapsedMs = elapsed,
                nowElapsedMs = 20_000L + index,
            )
        }

        val snapshot = diagnostics.performanceSnapshot.value
        assertEquals(840L, snapshot.ocrElapsedMs)
        assertEquals(2_370L, snapshot.translationElapsedMs)

        diagnostics.setPerformanceOverlayEnabled(false)
        assertEquals(RuntimePerformanceSnapshot(), diagnostics.performanceSnapshot.value)
    }

    @Test
    fun wakeContext_tableDriven_claimsOnlyFirstNetworkRequestAndExpires() {
        val diagnostics = RuntimePerformanceDiagnostics(LogRepository())
        diagnostics.onScreenWake(
            screenOffDurationMs = 12_000,
            idleConnectionsCleared = 3,
            nowElapsedMs = 1_000,
        )

        val first = diagnostics.claimFirstNetworkRequestAfterWake(nowElapsedMs = 1_250)
        assertNotNull(first)
        assertEquals(250L, first?.wakeAgeMs)
        assertEquals(12_000L, first?.screenOffDurationMs)
        assertEquals(3, first?.idleConnectionsCleared)
        assertNull(diagnostics.claimFirstNetworkRequestAfterWake(nowElapsedMs = 1_300))
        assertNotNull(diagnostics.currentWakeContext(nowElapsedMs = 60_999))
        assertNull(diagnostics.currentWakeContext(nowElapsedMs = 61_001))

        diagnostics.onScreenWake(1_000, 1, nowElapsedMs = 70_000)
        diagnostics.onScreenOff()
        assertNull(diagnostics.currentWakeContext(nowElapsedMs = 70_001))
    }

    @Test
    fun observe_recordsOneAppVisibleWarningWithWakeCorrelation() {
        val logs = LogRepository()
        val diagnostics = RuntimePerformanceDiagnostics(logs)
        diagnostics.onScreenWake(
            screenOffDurationMs = 20_000,
            idleConnectionsCleared = 1,
            nowElapsedMs = 1_000,
        )
        listOf(900L, 1_000L, 1_100L).forEachIndexed { index, elapsed ->
            diagnostics.observe(
                stage = RuntimePerformanceStage.OCR,
                operationKey = "ML_KIT_JAPANESE/pixels-2^20",
                elapsedMs = elapsed,
                nowElapsedMs = 1_100L + index,
            )
        }
        diagnostics.observe(
            stage = RuntimePerformanceStage.OCR,
            operationKey = "ML_KIT_JAPANESE/pixels-2^20",
            elapsedMs = 3_000,
            nowElapsedMs = 1_500,
        )

        val warning = logs.entries.value.single()
        assertEquals(LogRepository.Level.WARN, warning.level)
        assertEquals(LogRepository.Category.OCR, warning.category)
        assertTrue(warning.message.contains("性能异常 OCR本次 3000ms"))
        assertTrue(warning.message.contains("近期中位数 1000ms"))
        assertTrue(warning.message.contains("亮屏后 500ms"))

        diagnostics.observe(
            stage = RuntimePerformanceStage.OCR,
            operationKey = "ML_KIT_JAPANESE/pixels-2^20",
            elapsedMs = 3_500,
            nowElapsedMs = 2_000,
        )
        assertEquals("warning is rate limited", 1, logs.entries.value.size)
    }

    @Test
    fun observe_doesNotCompareDifferentOperationKeys() {
        val logs = LogRepository()
        val diagnostics = RuntimePerformanceDiagnostics(logs)
        repeat(3) { index ->
            diagnostics.observe(
                RuntimePerformanceStage.CAPTURE,
                "small",
                100L + index,
                nowElapsedMs = index.toLong(),
            )
        }
        diagnostics.observe(
            RuntimePerformanceStage.CAPTURE,
            "large",
            5_000,
            nowElapsedMs = 10,
        )
        assertTrue(logs.entries.value.isEmpty())
    }
}
