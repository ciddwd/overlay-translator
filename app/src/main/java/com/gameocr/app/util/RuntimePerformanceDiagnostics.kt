package com.gameocr.app.util

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.gameocr.app.data.LogRepository
import java.util.ArrayDeque
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class RuntimePerformanceStage(
    val displayName: String,
    val category: LogRepository.Category,
    val minimumSlowdownMs: Long,
) {
    CAPTURE("截屏", LogRepository.Category.CAPTURE, 750L),
    OCR("OCR", LogRepository.Category.OCR, 1_500L),
    TRANSLATION("翻译", LogRepository.Category.TRANSLATE, 3_000L),
}

internal data class RuntimePerformanceAnomalyDecision(
    val anomalous: Boolean,
    val baselineMs: Long?,
    val ratio: Double?,
)

internal data class RuntimePerformanceSnapshot(
    val ocrElapsedMs: Long? = null,
    val translationElapsedMs: Long? = null,
    val translationInferenceTotalMs: Double? = null,
    val translationInitMs: Double? = null,
    val translationPrefillMs: Double? = null,
    val translationDecodeMs: Double? = null,
    val translationInputTokens: Int? = null,
    val translationOutputTokens: Int? = null,
    val translationPrefillTokensPerSecond: Double? = null,
    val translationDecodeTokensPerSecond: Double? = null,
    val memoryMb: Int? = null,
    val cpuCoreUsage: Float? = null,
)

internal data class RuntimeLlmInferenceMetrics(
    val initMs: Double?,
    val prefillMs: Double?,
    val decodeMs: Double?,
    val totalMs: Double,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val prefillTokensPerSecond: Double?,
    val decodeTokensPerSecond: Double?,
)

internal data class RuntimeResourceSample(
    val memoryMb: Int,
    val cpuCoreUsage: Float?,
)

private data class RuntimeCpuInterval(
    val startWallMs: Long,
    val endWallMs: Long,
    val cpuMs: Long,
)

/** Process-wide PSS and CPU time sampled only while the developer overlay is enabled. */
internal class RuntimeResourceSampler(
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val processCpuTimeMs: () -> Long = Process::getElapsedCpuTime,
    private val totalPssKb: () -> Long = Debug::getPss,
) {
    private var previousWallMs: Long? = null
    private var previousCpuMs: Long? = null
    private val cpuIntervals = ArrayDeque<RuntimeCpuInterval>()

    fun sample(): RuntimeResourceSample {
        val wallMs = elapsedRealtimeMs()
        val cpuMs = processCpuTimeMs()
        val previousWall = previousWallMs
        val previousCpu = previousCpuMs
        val cpuCoreUsage = if (previousWall != null && previousCpu != null) {
            val wallDelta = wallMs - previousWall
            val cpuDelta = cpuMs - previousCpu
            if (wallDelta > 0L && cpuDelta >= 0L) {
                cpuIntervals.addLast(
                    RuntimeCpuInterval(
                        startWallMs = previousWall,
                        endWallMs = wallMs,
                        cpuMs = cpuDelta,
                    )
                )
                rollingCpuCoreUsage(wallMs)
            } else {
                cpuIntervals.clear()
                null
            }
        } else {
            null
        }
        previousWallMs = wallMs
        previousCpuMs = cpuMs
        return RuntimeResourceSample(
            memoryMb = ((totalPssKb().coerceAtLeast(0L) + 512L) / 1024L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            cpuCoreUsage = cpuCoreUsage,
        )
    }

    private fun rollingCpuCoreUsage(nowWallMs: Long): Float? {
        val windowStartMs = nowWallMs - CPU_ROLLING_WINDOW_MS
        while (cpuIntervals.isNotEmpty() && cpuIntervals.first().endWallMs <= windowStartMs) {
            cpuIntervals.removeFirst()
        }
        var coveredWallMs = 0.0
        var coveredCpuMs = 0.0
        cpuIntervals.forEach { interval ->
            val intervalWallMs = interval.endWallMs - interval.startWallMs
            val overlapStartMs = maxOf(interval.startWallMs, windowStartMs)
            val overlapWallMs = interval.endWallMs - overlapStartMs
            if (intervalWallMs > 0L && overlapWallMs > 0L) {
                coveredWallMs += overlapWallMs
                coveredCpuMs += interval.cpuMs * (overlapWallMs.toDouble() / intervalWallMs)
            }
        }
        return if (coveredWallMs > 0.0) {
            (coveredCpuMs / coveredWallMs).toFloat().coerceAtLeast(0f)
        } else {
            null
        }
    }

    fun reset() {
        previousWallMs = null
        previousCpuMs = null
        cpuIntervals.clear()
    }

    private companion object {
        const val CPU_ROLLING_WINDOW_MS = 2_000L
    }
}

internal object RuntimePerformanceValueFormatter {
    fun duration(elapsedMs: Number?): String {
        val value = elapsedMs?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 } ?: return "—"
        return when {
            value < 1_000.0 && kotlin.math.abs(value - kotlin.math.round(value)) >= 0.05 ->
                String.format(Locale.US, "%.1f ms", value)
            value < 1_000.0 -> "${value.toLong()} ms"
            else -> String.format(Locale.US, "%.2f s", value / 1_000.0)
        }
    }

    fun cpuCores(cores: Float?): String = cores?.let {
        String.format(Locale.US, "%.1f", it.coerceAtLeast(0f))
    } ?: "—"

    fun memory(memoryMb: Int?): String = memoryMb?.let { "$it MB" } ?: "—"

    fun tokens(count: Int?): String = count?.let { "$it tokens" } ?: "—"

    fun estimatedTokens(count: Int?): String = count?.let { "~$it tokens" } ?: "—"

    fun tokensPerSecond(rate: Double?): String = rate
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { "${String.format(Locale.US, "%.1f", it)} tok/s" }
        ?: "—"

    fun estimatedTokensPerSecond(rate: Double?): String = rate
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { "~${String.format(Locale.US, "%.1f", it)} tok/s" }
        ?: "—"
}

/** Normalizes provider/native metrics and derives decode throughput without inventing token data. */
internal object RuntimeLlmInferenceMetricsPolicy {
    fun resolve(
        initMs: Double? = null,
        firstTokenMs: Long?,
        totalMs: Long,
        inputTokens: Int?,
        outputTokens: Int?,
        reportedTokensPerSecond: Double? = null,
    ): RuntimeLlmInferenceMetrics {
        val safeTotalMs = totalMs.coerceAtLeast(0L).toDouble()
        val safeInitMs = initMs?.takeIf { it.isFinite() && it >= 0.0 }
        val safeFirstTokenMs = firstTokenMs
            ?.takeIf { it >= 0L }
            ?.toDouble()
            ?.coerceAtMost(safeTotalMs)
        val safeInputTokens = inputTokens?.takeIf { it >= 0 }
        val safeOutputTokens = outputTokens?.takeIf { it >= 0 }
        val safeReportedRate = reportedTokensPerSecond
            ?.takeIf { it.isFinite() && it >= 0.0 }
        val decodeMs = safeFirstTokenMs?.let { (safeTotalMs - it).coerceAtLeast(0.0) }
        val prefillRate = when {
            safeInputTokens == null || safeInputTokens <= 0 || safeFirstTokenMs == null -> null
            safeFirstTokenMs > 0.0 -> safeInputTokens * 1_000.0 / safeFirstTokenMs
            else -> null
        }
        val decodeRate = when {
            safeReportedRate != null -> safeReportedRate
            safeOutputTokens == null || safeOutputTokens <= 0 -> null
            decodeMs != null && safeOutputTokens > 1 && decodeMs > 0.0 ->
                (safeOutputTokens - 1) * 1_000.0 / decodeMs
            else -> null
        }
        return RuntimeLlmInferenceMetrics(
            initMs = safeInitMs,
            prefillMs = safeFirstTokenMs,
            decodeMs = decodeMs,
            totalMs = safeTotalMs,
            inputTokens = safeInputTokens,
            outputTokens = safeOutputTokens,
            prefillTokensPerSecond = prefillRate,
            decodeTokensPerSecond = decodeRate,
        )
    }
}

/**
 * Uses a recent median instead of a fixed duration. OCR and translation time depends heavily on
 * the selected model and input size, so an absolute global threshold would create false alarms.
 */
internal object RuntimePerformanceAnomalyPolicy {
    const val MIN_BASELINE_SAMPLES = 3
    const val MAX_BASELINE_SAMPLES = 9
    const val SLOWDOWN_RATIO_PERCENT = 180

    fun evaluate(
        recentSamplesMs: List<Long>,
        elapsedMs: Long,
        minimumSlowdownMs: Long,
    ): RuntimePerformanceAnomalyDecision {
        val valid = recentSamplesMs.filter { it >= 0L }
        if (elapsedMs < 0L || valid.size < MIN_BASELINE_SAMPLES) {
            return RuntimePerformanceAnomalyDecision(false, null, null)
        }
        val sorted = valid.sorted()
        val baselineMs = sorted[sorted.size / 2]
        if (baselineMs <= 0L) {
            return RuntimePerformanceAnomalyDecision(false, baselineMs, null)
        }
        val ratio = elapsedMs.toDouble() / baselineMs.toDouble()
        val relativeSlowdown = elapsedMs * 100L >= baselineMs * SLOWDOWN_RATIO_PERCENT
        val meaningfulDelta = elapsedMs - baselineMs >= minimumSlowdownMs
        return RuntimePerformanceAnomalyDecision(
            anomalous = relativeSlowdown && meaningfulDelta,
            baselineMs = baselineMs,
            ratio = ratio,
        )
    }
}

internal data class ScreenWakePerformanceContext(
    val wakeAgeMs: Long,
    val screenOffDurationMs: Long?,
    val idleConnectionsCleared: Int,
)

private data class ScreenWakePerformanceState(
    val wakeAtElapsedMs: Long,
    val screenOffDurationMs: Long?,
    val idleConnectionsCleared: Int,
    val firstNetworkRequestPending: Boolean = true,
)

/** Stable keys keep unlike workloads out of the same baseline without storing user content. */
internal object RuntimePerformanceKeyPolicy {
    fun bitmap(operation: String, width: Int, height: Int): String {
        val pixels = width.toLong().coerceAtLeast(0L) * height.toLong().coerceAtLeast(0L)
        return "$operation/pixels-2^${magnitudeBucket(pixels)}"
    }

    fun text(operation: String, itemCount: Int, characterCount: Int): String =
        "$operation/items-2^${magnitudeBucket(itemCount.toLong())}/chars-2^${magnitudeBucket(characterCount.toLong())}"

    private fun magnitudeBucket(value: Long): Int = when {
        value <= 0L -> 0
        else -> 63 - java.lang.Long.numberOfLeadingZeros(value)
    }
}

/**
 * In-memory runtime diagnostics. It never records OCR text, request bodies, URL paths, or secrets.
 * Baselines intentionally reset with the process because comparisons across app versions and
 * device thermal states are less useful than recent same-session behavior.
 */
@Singleton
class RuntimePerformanceDiagnostics @Inject constructor(
    private val logRepository: LogRepository,
) {
    private val lock = Any()
    private val samples = mutableMapOf<String, ArrayDeque<Long>>()
    private val lastWarningAtMs = mutableMapOf<String, Long>()
    private var wakeState: ScreenWakePerformanceState? = null
    private var resourceSampler = RuntimeResourceSampler()
    private val _performanceSnapshot = MutableStateFlow(RuntimePerformanceSnapshot())
    private var performanceOverlayEnabled = false

    internal val performanceSnapshot: StateFlow<RuntimePerformanceSnapshot> =
        _performanceSnapshot.asStateFlow()

    internal constructor(
        logRepository: LogRepository,
        resourceSampler: RuntimeResourceSampler,
    ) : this(logRepository) {
        this.resourceSampler = resourceSampler
    }

    fun setPerformanceOverlayEnabled(enabled: Boolean) {
        val next = synchronized(lock) {
            if (performanceOverlayEnabled == enabled) return
            performanceOverlayEnabled = enabled
            resourceSampler.reset()
            if (!enabled) RuntimePerformanceSnapshot() else {
                val resource = resourceSampler.sample()
                _performanceSnapshot.value.copy(
                    memoryMb = resource.memoryMb,
                    cpuCoreUsage = resource.cpuCoreUsage,
                )
            }
        }
        _performanceSnapshot.value = next
    }

    fun isPerformanceOverlayEnabled(): Boolean = synchronized(lock) {
        performanceOverlayEnabled
    }

    /** Clears request-specific values while preserving the most recent OCR and resource sample. */
    fun beginTranslation() {
        val next = synchronized(lock) {
            if (!performanceOverlayEnabled) return
            _performanceSnapshot.value.copy(
                translationElapsedMs = null,
                translationInferenceTotalMs = null,
                translationInitMs = null,
                translationPrefillMs = null,
                translationDecodeMs = null,
                translationInputTokens = null,
                translationOutputTokens = null,
                translationPrefillTokensPerSecond = null,
                translationDecodeTokensPerSecond = null,
            )
        }
        _performanceSnapshot.value = next
    }

    /** Records only real provider/native token metrics; null fields stay visibly unavailable. */
    fun recordLlmInference(
        firstTokenMs: Long?,
        totalMs: Long,
        inputTokens: Int?,
        outputTokens: Int?,
        reportedTokensPerSecond: Double? = null,
        initMs: Double? = null,
    ) {
        recordResolvedLlmMetrics(
            RuntimeLlmInferenceMetricsPolicy.resolve(
                initMs = initMs,
                firstTokenMs = firstTokenMs,
                totalMs = totalMs,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                reportedTokensPerSecond = reportedTokensPerSecond,
            )
        )
    }

    /** Records native phase timings when the runtime exposes the phase boundaries directly. */
    fun recordLlmInferencePhases(
        initMs: Double,
        prefillMs: Double,
        decodeMs: Double,
        totalMs: Double,
        inputTokens: Int,
        outputTokens: Int,
    ) {
        val safeInit = initMs.takeIf { it.isFinite() && it >= 0.0 }
        val safePrefill = prefillMs.takeIf { it.isFinite() && it >= 0.0 }
        val safeDecode = decodeMs.takeIf { it.isFinite() && it >= 0.0 }
        val safeTotal = totalMs.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val safeInput = inputTokens.takeIf { it >= 0 }
        val safeOutput = outputTokens.takeIf { it >= 0 }
        recordResolvedLlmMetrics(
            RuntimeLlmInferenceMetrics(
                initMs = safeInit,
                prefillMs = safePrefill,
                decodeMs = safeDecode,
                totalMs = safeTotal,
                inputTokens = safeInput,
                outputTokens = safeOutput,
                prefillTokensPerSecond = if (safePrefill != null && safePrefill > 0.0 && safeInput != null) {
                    safeInput * 1_000.0 / safePrefill
                } else {
                    null
                },
                decodeTokensPerSecond = if (safeDecode != null && safeDecode > 0.0 && safeOutput != null) {
                    safeOutput * 1_000.0 / safeDecode
                } else {
                    null
                },
            )
        )
    }

    private fun recordResolvedLlmMetrics(metrics: RuntimeLlmInferenceMetrics) {
        val next = synchronized(lock) {
            if (!performanceOverlayEnabled) return
            val resource = resourceSampler.sample()
            _performanceSnapshot.value.copy(
                translationInferenceTotalMs = metrics.totalMs,
                translationInitMs = metrics.initMs,
                translationPrefillMs = metrics.prefillMs,
                translationDecodeMs = metrics.decodeMs,
                translationInputTokens = metrics.inputTokens,
                translationOutputTokens = metrics.outputTokens,
                translationPrefillTokensPerSecond = metrics.prefillTokensPerSecond,
                translationDecodeTokensPerSecond = metrics.decodeTokensPerSecond,
                memoryMb = resource.memoryMb,
                cpuCoreUsage = resource.cpuCoreUsage,
            )
        }
        _performanceSnapshot.value = next
    }

    internal fun onScreenOff() = synchronized(lock) {
        wakeState = null
    }

    internal fun onScreenWake(
        screenOffDurationMs: Long?,
        idleConnectionsCleared: Int,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) = synchronized(lock) {
        wakeState = ScreenWakePerformanceState(
            wakeAtElapsedMs = nowElapsedMs,
            screenOffDurationMs = screenOffDurationMs,
            idleConnectionsCleared = idleConnectionsCleared.coerceAtLeast(0),
        )
    }

    internal fun resetWakeContext() = synchronized(lock) {
        wakeState = null
    }

    internal fun currentWakeContext(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): ScreenWakePerformanceContext? = synchronized(lock) {
        wakeContextLocked(nowElapsedMs)
    }

    internal fun claimFirstNetworkRequestAfterWake(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): ScreenWakePerformanceContext? = synchronized(lock) {
        val state = wakeState ?: return@synchronized null
        val context = wakeContextLocked(nowElapsedMs) ?: return@synchronized null
        if (!state.firstNetworkRequestPending) return@synchronized null
        wakeState = state.copy(firstNetworkRequestPending = false)
        context
    }

    internal fun observe(
        stage: RuntimePerformanceStage,
        operationKey: String,
        elapsedMs: Long,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        if (elapsedMs < 0L) return
        var performanceUpdate: RuntimePerformanceSnapshot? = null
        val warning = synchronized(lock) {
            if (
                performanceOverlayEnabled &&
                (stage == RuntimePerformanceStage.OCR || stage == RuntimePerformanceStage.TRANSLATION)
            ) {
                val resource = resourceSampler.sample()
                performanceUpdate = _performanceSnapshot.value.copy(
                    ocrElapsedMs = if (stage == RuntimePerformanceStage.OCR) {
                        elapsedMs
                    } else {
                        _performanceSnapshot.value.ocrElapsedMs
                    },
                    translationElapsedMs = if (stage == RuntimePerformanceStage.TRANSLATION) {
                        elapsedMs
                    } else {
                        _performanceSnapshot.value.translationElapsedMs
                    },
                    memoryMb = resource.memoryMb,
                    cpuCoreUsage = resource.cpuCoreUsage,
                )
            }
            val key = "${stage.name}:$operationKey"
            val series = samples.getOrPut(key) { ArrayDeque() }
            val decision = RuntimePerformanceAnomalyPolicy.evaluate(
                recentSamplesMs = series.toList(),
                elapsedMs = elapsedMs,
                minimumSlowdownMs = stage.minimumSlowdownMs,
            )
            series.addLast(elapsedMs)
            while (series.size > RuntimePerformanceAnomalyPolicy.MAX_BASELINE_SAMPLES) {
                series.removeFirst()
            }
            if (!decision.anomalous) return@synchronized null
            val lastWarning = lastWarningAtMs[key]
            if (lastWarning != null && nowElapsedMs - lastWarning in 0 until WARNING_COOLDOWN_MS) {
                return@synchronized null
            }
            lastWarningAtMs[key] = nowElapsedMs
            val wake = wakeContextLocked(nowElapsedMs)
            buildString {
                append("性能异常 ")
                append(stage.displayName)
                append("本次 ")
                append(elapsedMs)
                append("ms，近期中位数 ")
                append(decision.baselineMs)
                append("ms")
                decision.ratio?.let {
                    append("（")
                    append(String.format(Locale.US, "%.1f", it))
                    append(" 倍）")
                }
                append(" ")
                append(operationKey)
                wake?.let {
                    append(" 亮屏后 ")
                    append(it.wakeAgeMs)
                    append("ms")
                    it.screenOffDurationMs?.let { duration ->
                        append("（熄屏 ")
                        append(duration)
                        append("ms）")
                    }
                }
            }
        }
        performanceUpdate?.let { _performanceSnapshot.value = it }
        warning?.let { logRepository.warn(stage.category, it, elapsedMs = elapsedMs) }
    }

    private fun wakeContextLocked(nowElapsedMs: Long): ScreenWakePerformanceContext? {
        val state = wakeState ?: return null
        val age = (nowElapsedMs - state.wakeAtElapsedMs).coerceAtLeast(0L)
        if (age > WAKE_CORRELATION_WINDOW_MS) {
            wakeState = null
            return null
        }
        return ScreenWakePerformanceContext(
            wakeAgeMs = age,
            screenOffDurationMs = state.screenOffDurationMs,
            idleConnectionsCleared = state.idleConnectionsCleared,
        )
    }

    private companion object {
        const val WAKE_CORRELATION_WINDOW_MS = 60_000L
        const val WARNING_COOLDOWN_MS = 60_000L
    }
}
