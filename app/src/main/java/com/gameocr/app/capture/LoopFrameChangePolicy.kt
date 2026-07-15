package com.gameocr.app.capture

import kotlin.math.abs

internal data class LoopFrameFingerprint(
    val width: Int,
    val height: Int,
    val contextId: Int,
    val exactHash: Long,
    val luminanceSample: ByteArray,
)

internal enum class LoopFramePreOcrDecision {
    PROCESS,
    SKIP_EXACT_FRAME,
    PROCESS_SIMILAR_FRAME,
}

internal data class LoopFramePreOcrResult(
    val decision: LoopFramePreOcrDecision,
    val similarity: Float? = null,
)

internal object LoopFrameChangePolicy {
    const val DEFAULT_SIMILARITY_THRESHOLD: Float = 0.95f
    const val MIN_SIMILARITY_THRESHOLD: Float = 0.50f
    const val MAX_SIMILARITY_THRESHOLD: Float = 0.99f

    fun normalizeThreshold(value: Float): Float =
        if (value.isFinite()) {
            value.coerceIn(MIN_SIMILARITY_THRESHOLD, MAX_SIMILARITY_THRESHOLD)
        } else {
            DEFAULT_SIMILARITY_THRESHOLD
        }

    fun beforeOcr(
        previous: LoopFrameFingerprint?,
        current: LoopFrameFingerprint,
        enabled: Boolean,
        similarityThreshold: Float,
    ): LoopFramePreOcrResult {
        if (!enabled || previous == null || !hasComparableContext(previous, current)) {
            return LoopFramePreOcrResult(LoopFramePreOcrDecision.PROCESS)
        }
        if (previous.exactHash == current.exactHash) {
            return LoopFramePreOcrResult(
                decision = LoopFramePreOcrDecision.SKIP_EXACT_FRAME,
                similarity = 1f,
            )
        }
        val similarity = similarity(previous.luminanceSample, current.luminanceSample)
        return LoopFramePreOcrResult(
            decision = if (similarity >= normalizeThreshold(similarityThreshold)) {
                LoopFramePreOcrDecision.PROCESS_SIMILAR_FRAME
            } else {
                LoopFramePreOcrDecision.PROCESS
            },
            similarity = similarity,
        )
    }

    fun shouldSkipTranslation(
        preOcrDecision: LoopFramePreOcrDecision,
        previousOcrText: String?,
        currentOcrText: String,
    ): Boolean =
        preOcrDecision == LoopFramePreOcrDecision.PROCESS_SIMILAR_FRAME &&
            previousOcrText != null &&
            previousOcrText == currentOcrText

    fun normalizeOcrText(blockTexts: List<String>): String =
        blockTexts
            .map { text -> text.trim().replace(Regex("\\s+"), " ") }
            .filter(String::isNotEmpty)
            .joinToString(separator = "\n")

    fun similarity(previous: ByteArray, current: ByteArray): Float {
        if (previous.isEmpty() || previous.size != current.size) return 0f
        var absoluteDifference = 0L
        previous.indices.forEach { index ->
            val before = previous[index].toInt() and 0xFF
            val now = current[index].toInt() and 0xFF
            absoluteDifference += abs(before - now)
        }
        val maximumDifference = previous.size.toLong() * 255L
        return (1.0 - absoluteDifference.toDouble() / maximumDifference.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private fun hasComparableContext(
        previous: LoopFrameFingerprint,
        current: LoopFrameFingerprint,
    ): Boolean =
        previous.width == current.width &&
            previous.height == current.height &&
            previous.contextId == current.contextId &&
            previous.luminanceSample.size == current.luminanceSample.size
}
