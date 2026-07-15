package com.gameocr.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InferenceTimingTest {

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
