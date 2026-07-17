package com.gameocr.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InferenceDiagnosticsTest {

    @Test
    fun summarizeDecoderSteps_coversDistributionAndSlowStepCases() {
        data class Case(
            val name: String,
            val samples: List<DecoderStepTimingSample>,
            val maxSlowest: Int,
            val expected: DecoderStepTimingSummary,
        )

        listOf(
            Case(
                name = "empty",
                samples = emptyList(),
                maxSlowest = 3,
                expected = DecoderStepTimingSummary(),
            ),
            Case(
                name = "single step",
                samples = listOf(DecoderStepTimingSample(0, 1, 12L)),
                maxSlowest = 3,
                expected = DecoderStepTimingSummary(
                    count = 1,
                    averageUs = 12L,
                    minUs = 12L,
                    p50Us = 12L,
                    p90Us = 12L,
                    p95Us = 12L,
                    maxUs = 12L,
                    firstQuarterAverageUs = 12L,
                    lastQuarterAverageUs = 12L,
                    slowest = listOf(DecoderStepTimingSample(0, 1, 12L)),
                ),
            ),
            Case(
                name = "unsorted values use nearest rank and generation order quarters",
                samples = listOf(
                    DecoderStepTimingSample(0, 1, 40L),
                    DecoderStepTimingSample(1, 2, 10L),
                    DecoderStepTimingSample(2, 3, 30L),
                    DecoderStepTimingSample(3, 4, 20L),
                ),
                maxSlowest = 3,
                expected = DecoderStepTimingSummary(
                    count = 4,
                    averageUs = 25L,
                    minUs = 10L,
                    p50Us = 20L,
                    p90Us = 40L,
                    p95Us = 40L,
                    maxUs = 40L,
                    firstQuarterAverageUs = 40L,
                    lastQuarterAverageUs = 20L,
                    slowest = listOf(
                        DecoderStepTimingSample(0, 1, 40L),
                        DecoderStepTimingSample(2, 3, 30L),
                        DecoderStepTimingSample(3, 4, 20L),
                    ),
                ),
            ),
            Case(
                name = "negative timings clamp and slowest limit clamps",
                samples = listOf(
                    DecoderStepTimingSample(0, 1, -5L),
                    DecoderStepTimingSample(1, 2, 0L),
                    DecoderStepTimingSample(2, 3, 5L),
                ),
                maxSlowest = -1,
                expected = DecoderStepTimingSummary(
                    count = 3,
                    averageUs = 1L,
                    minUs = 0L,
                    p50Us = 0L,
                    p90Us = 5L,
                    p95Us = 5L,
                    maxUs = 5L,
                    firstQuarterAverageUs = 0L,
                    lastQuarterAverageUs = 5L,
                    slowest = emptyList(),
                ),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                InferenceDiagnostics.summarizeDecoderSteps(
                    samples = case.samples,
                    maxSlowest = case.maxSlowest,
                ),
            )
        }
    }

    @Test
    fun runtimeDelta_coversCpuGcThreadAndClockCases() {
        data class Case(
            val name: String,
            val start: InferenceRuntimeSnapshot,
            val end: InferenceRuntimeSnapshot,
            val expected: InferenceRuntimeDelta,
        )

        val normalStart = InferenceRuntimeSnapshot(
            elapsedRealtimeNs = 1_000_000L,
            processCpuMs = 10L,
            callerThreadCpuNs = 2_000_000L,
            callerThreadId = 7,
            callerThreadName = "worker-1",
            callerThreadPriority = 0,
            gcCount = 5L,
            gcTimeMs = 20L,
            blockingGcCount = 1L,
            blockingGcTimeMs = 3L,
            javaHeapUsedBytes = 100L,
            nativeHeapAllocatedBytes = 200L,
            thermalStatus = 0,
        )
        val normalEnd = InferenceRuntimeSnapshot(
            elapsedRealtimeNs = 3_000_000L,
            processCpuMs = 15L,
            callerThreadCpuNs = 3_000_000L,
            callerThreadId = 7,
            callerThreadName = "worker-1",
            callerThreadPriority = 0,
            gcCount = 7L,
            gcTimeMs = 29L,
            blockingGcCount = 2L,
            blockingGcTimeMs = 8L,
            javaHeapUsedBytes = 130L,
            nativeHeapAllocatedBytes = 180L,
            thermalStatus = 1,
        )

        listOf(
            Case(
                name = "normal multi core and runtime counters",
                start = normalStart,
                end = normalEnd,
                expected = InferenceRuntimeDelta(
                    wallUs = 2_000L,
                    processCpuMs = 5L,
                    processCpuCorePermille = 2_500,
                    callerThreadCpuUs = 1_000L,
                    callerThreadCpuWallPermille = 500,
                    callerThreadStartId = 7,
                    callerThreadEndId = 7,
                    callerThreadStartName = "worker-1",
                    callerThreadEndName = "worker-1",
                    callerThreadStartPriority = 0,
                    callerThreadEndPriority = 0,
                    gcCount = 2L,
                    gcTimeMs = 9L,
                    blockingGcCount = 1L,
                    blockingGcTimeMs = 5L,
                    javaHeapDeltaBytes = 30L,
                    nativeHeapDeltaBytes = -20L,
                    javaHeapEndBytes = 130L,
                    nativeHeapEndBytes = 180L,
                    thermalStart = 0,
                    thermalEnd = 1,
                ),
            ),
            Case(
                name = "clock reversal counter reversal and thread switch",
                start = normalEnd,
                end = normalStart.copy(
                    callerThreadId = 8,
                    callerThreadName = "worker-2",
                    callerThreadPriority = 10,
                    gcCount = null,
                    thermalStatus = null,
                ),
                expected = InferenceRuntimeDelta(
                    wallUs = 0L,
                    processCpuMs = 0L,
                    processCpuCorePermille = null,
                    callerThreadCpuUs = null,
                    callerThreadCpuWallPermille = null,
                    callerThreadStartId = 7,
                    callerThreadEndId = 8,
                    callerThreadStartName = "worker-1",
                    callerThreadEndName = "worker-2",
                    callerThreadStartPriority = 0,
                    callerThreadEndPriority = 10,
                    gcCount = null,
                    gcTimeMs = 0L,
                    blockingGcCount = 0L,
                    blockingGcTimeMs = 0L,
                    javaHeapDeltaBytes = -30L,
                    nativeHeapDeltaBytes = 20L,
                    javaHeapEndBytes = 100L,
                    nativeHeapEndBytes = 200L,
                    thermalStart = 1,
                    thermalEnd = null,
                ),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                InferenceDiagnostics.runtimeDelta(case.start, case.end),
            )
        }
    }

    @Test
    fun thermalStatusName_coversPlatformAndFallbackValues() {
        listOf(
            null to "unsupported",
            0 to "none",
            1 to "light",
            2 to "moderate",
            3 to "severe",
            4 to "critical",
            5 to "emergency",
            6 to "shutdown",
            99 to "unknown(99)",
        ).forEach { (status, expected) ->
            assertEquals(status?.toString() ?: "null", expected, InferenceDiagnostics.thermalStatusName(status))
        }
    }

    @Test
    fun runtimeDelta_zeroWallLeavesRatiosUnavailable() {
        val snapshot = InferenceRuntimeSnapshot(
            elapsedRealtimeNs = 5L,
            processCpuMs = 2L,
            callerThreadCpuNs = 7L,
            callerThreadId = 1,
        )
        val delta = InferenceDiagnostics.runtimeDelta(snapshot, snapshot)

        assertNull(delta.processCpuCorePermille)
        assertNull(delta.callerThreadCpuWallPermille)
    }
}
