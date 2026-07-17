package com.gameocr.app.util

import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class DecoderStepTimingSample(
    val stepIndex: Int,
    val inputLength: Int,
    val runUs: Long,
)

internal data class DecoderStepTimingSummary(
    val count: Int = 0,
    val averageUs: Long = 0L,
    val minUs: Long = 0L,
    val p50Us: Long = 0L,
    val p90Us: Long = 0L,
    val p95Us: Long = 0L,
    val maxUs: Long = 0L,
    val firstQuarterAverageUs: Long = 0L,
    val lastQuarterAverageUs: Long = 0L,
    val slowest: List<DecoderStepTimingSample> = emptyList(),
)

internal data class InferenceRuntimeSnapshot(
    val elapsedRealtimeNs: Long,
    val processCpuMs: Long,
    val callerThreadCpuNs: Long?,
    val callerThreadId: Int? = null,
    val callerThreadName: String? = null,
    val callerThreadPriority: Int? = null,
    val gcCount: Long? = null,
    val gcTimeMs: Long? = null,
    val blockingGcCount: Long? = null,
    val blockingGcTimeMs: Long? = null,
    val javaHeapUsedBytes: Long = 0L,
    val nativeHeapAllocatedBytes: Long = 0L,
    val thermalStatus: Int? = null,
)

internal data class InferenceRuntimeDelta(
    val wallUs: Long,
    val processCpuMs: Long,
    val processCpuCorePermille: Int?,
    val callerThreadCpuUs: Long?,
    val callerThreadCpuWallPermille: Int?,
    val callerThreadStartId: Int?,
    val callerThreadEndId: Int?,
    val callerThreadStartName: String?,
    val callerThreadEndName: String?,
    val callerThreadStartPriority: Int?,
    val callerThreadEndPriority: Int?,
    val gcCount: Long?,
    val gcTimeMs: Long?,
    val blockingGcCount: Long?,
    val blockingGcTimeMs: Long?,
    val javaHeapDeltaBytes: Long,
    val nativeHeapDeltaBytes: Long,
    val javaHeapEndBytes: Long,
    val nativeHeapEndBytes: Long,
    val thermalStart: Int?,
    val thermalEnd: Int?,
)

internal object InferenceDiagnostics {
    fun summarizeDecoderSteps(
        samples: List<DecoderStepTimingSample>,
        maxSlowest: Int = 3,
    ): DecoderStepTimingSummary {
        if (samples.isEmpty()) return DecoderStepTimingSummary()

        val safeSamples = samples.map { sample ->
            sample.copy(runUs = sample.runUs.coerceAtLeast(0L))
        }
        val sortedRunUs = safeSamples.map(DecoderStepTimingSample::runUs).sorted()
        val quarterSize = ceil(safeSamples.size / 4.0).toInt().coerceAtLeast(1)
        return DecoderStepTimingSummary(
            count = safeSamples.size,
            averageUs = averageUs(sortedRunUs),
            minUs = sortedRunUs.first(),
            p50Us = nearestRank(sortedRunUs, 0.50),
            p90Us = nearestRank(sortedRunUs, 0.90),
            p95Us = nearestRank(sortedRunUs, 0.95),
            maxUs = sortedRunUs.last(),
            firstQuarterAverageUs = averageUs(
                safeSamples.take(quarterSize).map(DecoderStepTimingSample::runUs),
            ),
            lastQuarterAverageUs = averageUs(
                safeSamples.takeLast(quarterSize).map(DecoderStepTimingSample::runUs),
            ),
            slowest = safeSamples
                .sortedWith(
                    compareByDescending<DecoderStepTimingSample> { it.runUs }
                        .thenBy { it.stepIndex },
                )
                .take(maxSlowest.coerceAtLeast(0)),
        )
    }

    fun runtimeDelta(
        start: InferenceRuntimeSnapshot,
        end: InferenceRuntimeSnapshot,
    ): InferenceRuntimeDelta {
        val wallUs = InferenceTiming.elapsedUs(start.elapsedRealtimeNs, end.elapsedRealtimeNs)
        val processCpuMs = counterDelta(start.processCpuMs, end.processCpuMs) ?: 0L
        val callerThreadCpuUs = if (
            start.callerThreadId != null &&
            start.callerThreadId == end.callerThreadId
        ) {
            nullableNanosDeltaUs(start.callerThreadCpuNs, end.callerThreadCpuNs)
        } else {
            null
        }
        return InferenceRuntimeDelta(
            wallUs = wallUs,
            processCpuMs = processCpuMs,
            processCpuCorePermille = ratioPermille(
                numerator = processCpuMs,
                denominator = wallUs,
                numeratorScale = 1_000_000.0,
            ),
            callerThreadCpuUs = callerThreadCpuUs,
            callerThreadCpuWallPermille = callerThreadCpuUs?.let {
                ratioPermille(
                    numerator = it,
                    denominator = wallUs,
                    numeratorScale = 1_000.0,
                )
            },
            callerThreadStartId = start.callerThreadId,
            callerThreadEndId = end.callerThreadId,
            callerThreadStartName = start.callerThreadName,
            callerThreadEndName = end.callerThreadName,
            callerThreadStartPriority = start.callerThreadPriority,
            callerThreadEndPriority = end.callerThreadPriority,
            gcCount = nullableCounterDelta(start.gcCount, end.gcCount),
            gcTimeMs = nullableCounterDelta(start.gcTimeMs, end.gcTimeMs),
            blockingGcCount = nullableCounterDelta(
                start.blockingGcCount,
                end.blockingGcCount,
            ),
            blockingGcTimeMs = nullableCounterDelta(
                start.blockingGcTimeMs,
                end.blockingGcTimeMs,
            ),
            javaHeapDeltaBytes = end.javaHeapUsedBytes - start.javaHeapUsedBytes,
            nativeHeapDeltaBytes = end.nativeHeapAllocatedBytes - start.nativeHeapAllocatedBytes,
            javaHeapEndBytes = end.javaHeapUsedBytes,
            nativeHeapEndBytes = end.nativeHeapAllocatedBytes,
            thermalStart = start.thermalStatus,
            thermalEnd = end.thermalStatus,
        )
    }

    fun thermalStatusName(status: Int?): String = when (status) {
        null -> "unsupported"
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "unknown($status)"
    }

    private fun nearestRank(sortedValues: List<Long>, percentile: Double): Long {
        val rank = ceil(percentile.coerceIn(0.0, 1.0) * sortedValues.size)
            .toInt()
            .coerceIn(1, sortedValues.size)
        return sortedValues[rank - 1]
    }

    private fun averageUs(values: List<Long>): Long =
        if (values.isEmpty()) 0L else values.average().toLong()

    private fun nullableNanosDeltaUs(startNs: Long?, endNs: Long?): Long? {
        if (startNs == null || endNs == null) return null
        return InferenceTiming.elapsedUs(startNs, endNs)
    }

    private fun nullableCounterDelta(start: Long?, end: Long?): Long? {
        if (start == null || end == null) return null
        return counterDelta(start, end)
    }

    private fun counterDelta(start: Long, end: Long): Long? =
        (end - start).coerceAtLeast(0L)

    private fun ratioPermille(
        numerator: Long,
        denominator: Long,
        numeratorScale: Double,
    ): Int? {
        if (denominator <= 0L) return null
        return (numerator * numeratorScale / denominator)
            .roundToInt()
            .coerceAtLeast(0)
    }
}
