package com.gameocr.app.util

internal data class GenerationTiming(
    val queueMs: Long,
    val firstOutputMs: Long?,
    val totalMs: Long,
    val outputPiecesPerSecond: Double?,
)

internal data class InferenceStageSummary(
    val totalUs: Long,
    val accountedUs: Long,
    val unaccountedUs: Long,
)

internal object InferenceTiming {
    fun elapsedMs(startMs: Long, endMs: Long): Long =
        (endMs - startMs).coerceAtLeast(0L)

    fun elapsedUs(startNs: Long, endNs: Long): Long =
        ((endNs - startNs).coerceAtLeast(0L)) / 1_000L

    fun stageSummary(
        totalUs: Long,
        stagesUs: Iterable<Long>,
    ): InferenceStageSummary {
        val safeTotalUs = totalUs.coerceAtLeast(0L)
        val accountedUs = stagesUs.fold(0L) { total, stage ->
            total + stage.coerceAtLeast(0L)
        }
        return InferenceStageSummary(
            totalUs = safeTotalUs,
            accountedUs = accountedUs,
            unaccountedUs = (safeTotalUs - accountedUs).coerceAtLeast(0L),
        )
    }

    fun generation(
        queuedAtMs: Long,
        startedAtMs: Long,
        firstOutputAtMs: Long?,
        finishedAtMs: Long,
        outputPieces: Int,
    ): GenerationTiming {
        val totalMs = elapsedMs(startedAtMs, finishedAtMs)
        val firstOutputMs = firstOutputAtMs
            ?.takeIf { outputPieces > 0 }
            ?.let { elapsedMs(startedAtMs, it).coerceAtMost(totalMs) }
        val rate = if (outputPieces > 0 && totalMs > 0L) {
            outputPieces * 1000.0 / totalMs
        } else {
            null
        }
        return GenerationTiming(
            queueMs = elapsedMs(queuedAtMs, startedAtMs),
            firstOutputMs = firstOutputMs,
            totalMs = totalMs,
            outputPiecesPerSecond = rate,
        )
    }
}
