package com.gameocr.app.capture

internal enum class LoopRoiStabilityDecision {
    WAIT,
    TRANSLATE_CACHED,
    RUN_FINAL_ROI_OCR,
    FALLBACK_TO_TEXT_STABILITY,
}

internal data class LoopRoiStabilityState(
    val previous: LoopRoiVisualFingerprint,
    val startedAtElapsedMs: Long,
    val stableSinceElapsedMs: Long,
    val changedSinceInitialOcr: Boolean = false,
    val changedObservationCount: Int = 0,
)

internal data class LoopRoiStabilityResult(
    val decision: LoopRoiStabilityDecision,
    val state: LoopRoiStabilityState,
    val similarity: Float,
)

internal object LoopRoiStabilityPolicy {
    const val DEFAULT_FALLBACK_TIMEOUT_MS = 2000L
    private const val MIN_CHANGED_OBSERVATIONS_BEFORE_FALLBACK = 3

    fun start(
        fingerprint: LoopRoiVisualFingerprint,
        nowElapsedMs: Long,
    ): LoopRoiStabilityState = LoopRoiStabilityState(
        previous = fingerprint,
        startedAtElapsedMs = nowElapsedMs,
        stableSinceElapsedMs = nowElapsedMs,
    )

    fun observe(
        state: LoopRoiStabilityState,
        current: LoopRoiVisualFingerprint,
        similarityThreshold: Float,
        stableDurationMs: Long,
        nowElapsedMs: Long,
        fallbackTimeoutMs: Long = DEFAULT_FALLBACK_TIMEOUT_MS,
    ): LoopRoiStabilityResult {
        val similarity = LoopRoiVisualPolicy.similarity(state.previous, current)
        val isStable = similarity >= LoopFrameChangePolicy.normalizeThreshold(similarityThreshold)
        val normalizedStableDuration = LoopFrameStabilityPolicy.normalizeStableDuration(stableDurationMs)
        if (isStable) {
            val stableElapsedMs = (nowElapsedMs - state.stableSinceElapsedMs).coerceAtLeast(0L)
            if (stableElapsedMs >= normalizedStableDuration) {
                return LoopRoiStabilityResult(
                    decision = if (state.changedSinceInitialOcr) {
                        LoopRoiStabilityDecision.RUN_FINAL_ROI_OCR
                    } else {
                        LoopRoiStabilityDecision.TRANSLATE_CACHED
                    },
                    state = state.copy(previous = current),
                    similarity = similarity,
                )
            }
            if (shouldFallback(state, nowElapsedMs, fallbackTimeoutMs)) {
                return LoopRoiStabilityResult(
                    LoopRoiStabilityDecision.FALLBACK_TO_TEXT_STABILITY,
                    state.copy(previous = current),
                    similarity,
                )
            }
            return LoopRoiStabilityResult(
                LoopRoiStabilityDecision.WAIT,
                state.copy(previous = current),
                similarity,
            )
        }

        val changedState = state.copy(
            previous = current,
            stableSinceElapsedMs = nowElapsedMs,
            changedSinceInitialOcr = true,
            changedObservationCount = state.changedObservationCount + 1,
        )
        return LoopRoiStabilityResult(
            decision = if (shouldFallback(changedState, nowElapsedMs, fallbackTimeoutMs)) {
                LoopRoiStabilityDecision.FALLBACK_TO_TEXT_STABILITY
            } else {
                LoopRoiStabilityDecision.WAIT
            },
            state = changedState,
            similarity = similarity,
        )
    }

    private fun shouldFallback(
        state: LoopRoiStabilityState,
        nowElapsedMs: Long,
        fallbackTimeoutMs: Long,
    ): Boolean = state.changedObservationCount >= MIN_CHANGED_OBSERVATIONS_BEFORE_FALLBACK &&
        nowElapsedMs - state.startedAtElapsedMs >= fallbackTimeoutMs.coerceAtLeast(0L)
}

internal enum class LoopRoiFallbackEvent {
    ENTER,
    TEXT_STILL_WAITING,
    TEXT_FINISHED,
    RESET,
}

internal object LoopRoiFallbackPolicy {
    fun transition(active: Boolean, event: LoopRoiFallbackEvent): Boolean = when (event) {
        LoopRoiFallbackEvent.ENTER -> true
        LoopRoiFallbackEvent.TEXT_STILL_WAITING -> active
        LoopRoiFallbackEvent.TEXT_FINISHED,
        LoopRoiFallbackEvent.RESET -> false
    }
}
