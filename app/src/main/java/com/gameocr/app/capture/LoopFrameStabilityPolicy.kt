package com.gameocr.app.capture

import com.gameocr.app.data.Settings

internal enum class LoopFrameStabilityDecision {
    PROCESS,
    PROBE_TEXT_STABILITY,
    WAIT_FOR_STABLE_FRAME,
    SKIP_ALREADY_PROCESSED,
}

internal enum class LoopFramePostOcrDecision {
    TRANSLATE,
    WAIT_FOR_STABLE_TEXT,
    SKIP_ALREADY_PROCESSED,
}

internal data class LoopFrameStabilityState(
    val previous: LoopFrameFingerprint? = null,
    val stableSinceElapsedMs: Long = 0L,
    val lastProcessedExactHash: Long? = null,
    val lastTextProbeElapsedMs: Long = 0L,
    val previousOcrText: String? = null,
    val textStableSinceElapsedMs: Long = 0L,
    val lastProcessedOcrText: String? = null,
    val maxObservedOcrTextLength: Int = 0,
)

internal data class LoopFrameStabilityResult(
    val decision: LoopFrameStabilityDecision,
    val state: LoopFrameStabilityState,
)

internal data class LoopFramePostOcrResult(
    val decision: LoopFramePostOcrDecision,
    val state: LoopFrameStabilityState,
    val textObservation: LoopOcrTextObservation? = null,
)

internal object LoopFrameStabilityPolicy {
    const val DEFAULT_STABLE_DURATION_MS: Long = Settings.DEFAULT_LOOP_TEXT_STABLE_DURATION_MS
    const val MIN_STABLE_DURATION_MS: Long = 200L
    const val MAX_STABLE_DURATION_MS: Long = 2000L
    const val PROBE_INTERVAL_MS: Long = 200L
    const val DEFAULT_LOOP_INTERVAL_MS: Long = 2000L

    fun normalizeStableDuration(value: Long): Long =
        value.coerceIn(MIN_STABLE_DURATION_MS, MAX_STABLE_DURATION_MS)

    fun pollingIntervalMs(configuredLoopIntervalMs: Long, enabled: Boolean): Long {
        val normalized = configuredLoopIntervalMs.takeIf { it > 0L } ?: DEFAULT_LOOP_INTERVAL_MS
        return if (enabled) minOf(normalized, PROBE_INTERVAL_MS) else normalized
    }

    fun beforeOcr(
        state: LoopFrameStabilityState,
        current: LoopFrameFingerprint,
        enabled: Boolean,
        allowTextStabilityProbe: Boolean,
        skipAlreadyProcessed: Boolean = true,
        stableDurationMs: Long,
        nowElapsedMs: Long,
    ): LoopFrameStabilityResult {
        if (!enabled) {
            return LoopFrameStabilityResult(
                decision = LoopFrameStabilityDecision.PROCESS,
                state = state.copy(
                    previous = current,
                    stableSinceElapsedMs = nowElapsedMs,
                    lastTextProbeElapsedMs = nowElapsedMs,
                ),
            )
        }

        val previous = state.previous
        if (previous == null || !hasComparableContext(previous, current)) {
            return LoopFrameStabilityResult(
                decision = if (allowTextStabilityProbe) {
                    LoopFrameStabilityDecision.PROBE_TEXT_STABILITY
                } else {
                    LoopFrameStabilityDecision.WAIT_FOR_STABLE_FRAME
                },
                state = LoopFrameStabilityState(
                    previous = current,
                    stableSinceElapsedMs = nowElapsedMs,
                    lastTextProbeElapsedMs = nowElapsedMs,
                ),
            )
        }
        if (previous.exactHash != current.exactHash) {
            val shouldProbeText = allowTextStabilityProbe
            return LoopFrameStabilityResult(
                decision = if (shouldProbeText) {
                    LoopFrameStabilityDecision.PROBE_TEXT_STABILITY
                } else {
                    LoopFrameStabilityDecision.WAIT_FOR_STABLE_FRAME
                },
                state = state.copy(
                    previous = current,
                    stableSinceElapsedMs = nowElapsedMs,
                    lastTextProbeElapsedMs = if (shouldProbeText) {
                        nowElapsedMs
                    } else {
                        state.lastTextProbeElapsedMs
                    },
                ),
            )
        }
        if (skipAlreadyProcessed && state.lastProcessedExactHash == current.exactHash) {
            return LoopFrameStabilityResult(
                decision = LoopFrameStabilityDecision.SKIP_ALREADY_PROCESSED,
                state = state.copy(previous = current),
            )
        }

        val stableElapsedMs = (nowElapsedMs - state.stableSinceElapsedMs).coerceAtLeast(0L)
        return LoopFrameStabilityResult(
            decision = if (stableElapsedMs >= normalizeStableDuration(stableDurationMs)) {
                LoopFrameStabilityDecision.PROCESS
            } else {
                LoopFrameStabilityDecision.WAIT_FOR_STABLE_FRAME
            },
            state = state.copy(previous = current),
        )
    }

    fun afterOcr(
        state: LoopFrameStabilityState,
        current: LoopFrameFingerprint?,
        trigger: LoopFrameStabilityDecision,
        normalizedOcrText: String,
        enabled: Boolean,
        skipAlreadyProcessed: Boolean = true,
        stableDurationMs: Long,
        nowElapsedMs: Long,
    ): LoopFramePostOcrResult {
        if (!enabled) {
            return LoopFramePostOcrResult(LoopFramePostOcrDecision.TRANSLATE, state)
        }
        val processedObservation = state.lastProcessedOcrText
            ?.takeIf { skipAlreadyProcessed && normalizedOcrText.isNotEmpty() }
            ?.let { processedText ->
                LoopOcrTextStabilityPolicy.compareToProcessedText(
                    processedText = processedText,
                    currentText = normalizedOcrText,
                )
            }
        if (processedObservation != null &&
            LoopOcrTextStabilityPolicy.isLikelySameProcessedText(processedObservation)
        ) {
            val duplicateState = markProcessed(state, current, normalizedOcrText).copy(
                lastProcessedOcrText = state.lastProcessedOcrText,
            )
            return LoopFramePostOcrResult(
                LoopFramePostOcrDecision.SKIP_ALREADY_PROCESSED,
                duplicateState,
                processedObservation,
            )
        }
        if (trigger == LoopFrameStabilityDecision.PROCESS) {
            val decision = if (normalizedOcrText.isEmpty()) {
                LoopFramePostOcrDecision.SKIP_ALREADY_PROCESSED
            } else {
                LoopFramePostOcrDecision.TRANSLATE
            }
            return LoopFramePostOcrResult(
                decision,
                markProcessed(state, current, normalizedOcrText),
            )
        }

        val observation = LoopOcrTextStabilityPolicy.observe(
            previousText = state.previousOcrText,
            currentText = normalizedOcrText,
            maxObservedLength = state.maxObservedOcrTextLength,
        )
        if (observation.shouldResetStableTimer) {
            return LoopFramePostOcrResult(
                LoopFramePostOcrDecision.WAIT_FOR_STABLE_TEXT,
                state.copy(
                    previousOcrText = normalizedOcrText,
                    textStableSinceElapsedMs = nowElapsedMs,
                    maxObservedOcrTextLength = observation.maxObservedLength,
                ),
                observation,
            )
        }
        val stableElapsedMs = (nowElapsedMs - state.textStableSinceElapsedMs).coerceAtLeast(0L)
        if (stableElapsedMs < normalizeStableDuration(stableDurationMs)) {
            return LoopFramePostOcrResult(
                LoopFramePostOcrDecision.WAIT_FOR_STABLE_TEXT,
                state.copy(
                    previousOcrText = normalizedOcrText,
                    maxObservedOcrTextLength = observation.maxObservedLength,
                ),
                observation,
            )
        }
        val decision = if (normalizedOcrText.isEmpty()) {
            LoopFramePostOcrDecision.SKIP_ALREADY_PROCESSED
        } else {
            LoopFramePostOcrDecision.TRANSLATE
        }
        return LoopFramePostOcrResult(
            decision,
            markProcessed(state, current, normalizedOcrText),
            observation,
        )
    }

    fun markProcessed(
        state: LoopFrameStabilityState,
        current: LoopFrameFingerprint?,
        normalizedOcrText: String? = state.previousOcrText,
    ): LoopFrameStabilityState = if (current == null) {
        state.copy(
            previousOcrText = normalizedOcrText,
            lastProcessedOcrText = normalizedOcrText,
        )
    } else {
        state.copy(
            previous = current,
            lastProcessedExactHash = current.exactHash,
            previousOcrText = normalizedOcrText,
            lastProcessedOcrText = normalizedOcrText,
        )
    }

    private fun hasComparableContext(
        previous: LoopFrameFingerprint,
        current: LoopFrameFingerprint,
    ): Boolean =
        previous.width == current.width &&
            previous.height == current.height &&
            previous.contextId == current.contextId
}
