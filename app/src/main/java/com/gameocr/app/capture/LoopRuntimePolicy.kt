package com.gameocr.app.capture

import com.gameocr.app.data.LoopTriggerMode

internal enum class LoopActiveResultDecision {
    CAPTURE,
    KEEP_TRANSLATING,
    KEEP_VISIBLE,
}

internal enum class LoopIndicatorMode {
    COUNTDOWN,
    INDETERMINATE,
}

internal data class LoopIndicatorSpec(
    val mode: LoopIndicatorMode,
    val periodMs: Long,
)

internal object LoopRuntimePolicy {
    const val SMART_INDICATOR_PERIOD_MS: Long = 1600L

    fun usesStabilityPolling(mode: LoopTriggerMode): Boolean =
        mode != LoopTriggerMode.FIXED_INTERVAL

    fun pollingIntervalMs(
        configuredLoopIntervalMs: Long,
        mode: LoopTriggerMode,
        backendMinimumMs: Long,
    ): Long = maxOf(
        LoopFrameStabilityPolicy.pollingIntervalMs(
            configuredLoopIntervalMs = configuredLoopIntervalMs,
            enabled = usesStabilityPolling(mode),
        ),
        backendMinimumMs.coerceAtLeast(0L),
    )

    fun activeResultDecision(
        hasBlockingResult: Boolean,
        translationInFlight: Boolean,
    ): LoopActiveResultDecision {
        if (translationInFlight) return LoopActiveResultDecision.KEEP_TRANSLATING
        if (!hasBlockingResult) return LoopActiveResultDecision.CAPTURE
        return LoopActiveResultDecision.KEEP_VISIBLE
    }

    fun indicatorSpec(
        configuredLoopIntervalMs: Long,
        smartMode: Boolean,
    ): LoopIndicatorSpec = if (smartMode) {
        LoopIndicatorSpec(
            mode = LoopIndicatorMode.INDETERMINATE,
            periodMs = SMART_INDICATOR_PERIOD_MS,
        )
    } else {
        LoopIndicatorSpec(
            mode = LoopIndicatorMode.COUNTDOWN,
            periodMs = configuredLoopIntervalMs.takeIf { it > 0L }
                ?: LoopFrameStabilityPolicy.DEFAULT_LOOP_INTERVAL_MS,
        )
    }
}
