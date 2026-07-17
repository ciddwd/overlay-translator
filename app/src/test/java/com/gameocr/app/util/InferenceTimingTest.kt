package com.gameocr.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InferenceTimingTest {

    @Test
    fun stageTiming_coversPrecisionClockReversalAndOverAccountedCases() {
        data class Case(
            val name: String,
            val startNs: Long,
            val endNs: Long,
            val totalUs: Long,
            val stagesUs: List<Long>,
            val expectedElapsedUs: Long,
            val expectedTotalUs: Long,
            val expectedAccountedUs: Long,
            val expectedUnaccountedUs: Long,
        )

        listOf(
            Case(
                name = "normal microsecond breakdown",
                startNs = 1_000_000L,
                endNs = 4_456_789L,
                totalUs = 3_456L,
                stagesUs = listOf(1_000L, 2_000L, 400L),
                expectedElapsedUs = 3_456L,
                expectedTotalUs = 3_456L,
                expectedAccountedUs = 3_400L,
                expectedUnaccountedUs = 56L,
            ),
            Case(
                name = "sub microsecond truncates safely",
                startNs = 10L,
                endNs = 999L,
                totalUs = 0L,
                stagesUs = emptyList(),
                expectedElapsedUs = 0L,
                expectedTotalUs = 0L,
                expectedAccountedUs = 0L,
                expectedUnaccountedUs = 0L,
            ),
            Case(
                name = "clock reversal and negative stages are clamped",
                startNs = 9_000L,
                endNs = 1_000L,
                totalUs = -5L,
                stagesUs = listOf(-1L, 2L),
                expectedElapsedUs = 0L,
                expectedTotalUs = 0L,
                expectedAccountedUs = 2L,
                expectedUnaccountedUs = 0L,
            ),
            Case(
                name = "over accounted stages never produce negative other time",
                startNs = 0L,
                endNs = 10_000L,
                totalUs = 10L,
                stagesUs = listOf(7L, 8L),
                expectedElapsedUs = 10L,
                expectedTotalUs = 10L,
                expectedAccountedUs = 15L,
                expectedUnaccountedUs = 0L,
            ),
        ).forEach { case ->
            assertEquals(
                "${case.name} elapsed",
                case.expectedElapsedUs,
                InferenceTiming.elapsedUs(case.startNs, case.endNs),
            )
            val summary = InferenceTiming.stageSummary(case.totalUs, case.stagesUs)
            assertEquals("${case.name} total", case.expectedTotalUs, summary.totalUs)
            assertEquals("${case.name} accounted", case.expectedAccountedUs, summary.accountedUs)
            assertEquals("${case.name} unaccounted", case.expectedUnaccountedUs, summary.unaccountedUs)
        }
    }

    @Test
    fun generation_coversRuntimeTimingCases() {
        data class Case(
            val name: String,
            val queuedAtMs: Long,
            val startedAtMs: Long,
            val firstOutputAtMs: Long?,
            val finishedAtMs: Long,
            val outputPieces: Int,
            val expectedQueueMs: Long,
            val expectedFirstOutputMs: Long?,
            val expectedTotalMs: Long,
            val expectedRate: Double?,
        )

        val cases = listOf(
            Case("normal", 100, 150, 400, 1150, 4, 50, 250, 1000, 4.0),
            Case("no output", 100, 150, null, 650, 0, 50, null, 500, null),
            Case("zero duration", 100, 100, 100, 100, 1, 0, 0, 0, null),
            Case("clock reversal", 200, 150, 140, 100, 2, 0, 0, 0, null),
            Case("first output after finish", 0, 10, 200, 110, 2, 10, 100, 100, 20.0),
        )

        cases.forEach { case ->
            val actual = InferenceTiming.generation(
                queuedAtMs = case.queuedAtMs,
                startedAtMs = case.startedAtMs,
                firstOutputAtMs = case.firstOutputAtMs,
                finishedAtMs = case.finishedAtMs,
                outputPieces = case.outputPieces,
            )

            assertEquals(case.name, case.expectedQueueMs, actual.queueMs)
            assertEquals(case.name, case.expectedFirstOutputMs, actual.firstOutputMs)
            assertEquals(case.name, case.expectedTotalMs, actual.totalMs)
            if (case.expectedRate == null) {
                assertNull(case.name, actual.outputPiecesPerSecond)
            } else {
                assertEquals(case.name, case.expectedRate, actual.outputPiecesPerSecond!!, 0.0001)
            }
        }
    }
}
