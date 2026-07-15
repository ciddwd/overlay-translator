package com.gameocr.app.capture

import java.util.Locale
import kotlin.math.max

internal enum class LoopOcrTextRelation {
    FIRST_OBSERVATION,
    EXACT,
    GROWING,
    OCR_JITTER,
    CHANGED,
}

internal data class LoopOcrTextObservation(
    val relation: LoopOcrTextRelation,
    val shouldResetStableTimer: Boolean,
    val maxObservedLength: Int,
    val similarity: Float,
)

internal object LoopOcrTextStabilityPolicy {
    const val OCR_JITTER_SIMILARITY_THRESHOLD = 0.95f
    private const val MAX_COMPARISON_LENGTH = 2000
    private const val GROWING_PREFIX_RATIO = 0.80f

    fun observe(
        previousText: String?,
        currentText: String,
        maxObservedLength: Int,
    ): LoopOcrTextObservation {
        val current = canonicalize(currentText)
        if (previousText == null) {
            return observation(
                relation = LoopOcrTextRelation.FIRST_OBSERVATION,
                reset = true,
                currentLength = current.length,
                maxObservedLength = maxObservedLength,
                similarity = 0f,
            )
        }

        val previous = canonicalize(previousText)
        if (previous == current) {
            return observation(
                relation = LoopOcrTextRelation.EXACT,
                reset = false,
                currentLength = current.length,
                maxObservedLength = maxObservedLength,
                similarity = 1f,
            )
        }

        val similarity = levenshteinSimilarity(previous, current)
        val exceedsPreviousMaximum = current.length > maxObservedLength
        val commonPrefixLength = previous.commonPrefixWith(current).length
        val requiredPrefixLength = (previous.length * GROWING_PREFIX_RATIO).toInt()
        val looksLikeGrowingText = exceedsPreviousMaximum &&
            previous.isNotEmpty() &&
            commonPrefixLength >= requiredPrefixLength
        if (looksLikeGrowingText) {
            return observation(
                relation = LoopOcrTextRelation.GROWING,
                reset = true,
                currentLength = current.length,
                maxObservedLength = maxObservedLength,
                similarity = similarity,
            )
        }
        if (similarity >= OCR_JITTER_SIMILARITY_THRESHOLD) {
            return observation(
                relation = LoopOcrTextRelation.OCR_JITTER,
                reset = false,
                currentLength = current.length,
                maxObservedLength = maxObservedLength,
                similarity = similarity,
            )
        }
        return observation(
            relation = LoopOcrTextRelation.CHANGED,
            reset = true,
            currentLength = current.length,
            maxObservedLength = current.length,
            similarity = similarity,
        )
    }

    fun compareToProcessedText(
        processedText: String,
        currentText: String,
    ): LoopOcrTextObservation = observe(
        previousText = processedText,
        currentText = currentText,
        maxObservedLength = canonicalize(processedText).length,
    )

    fun isLikelySameProcessedText(observation: LoopOcrTextObservation): Boolean =
        observation.relation == LoopOcrTextRelation.EXACT ||
            observation.relation == LoopOcrTextRelation.OCR_JITTER

    private fun observation(
        relation: LoopOcrTextRelation,
        reset: Boolean,
        currentLength: Int,
        maxObservedLength: Int,
        similarity: Float,
    ) = LoopOcrTextObservation(
        relation = relation,
        shouldResetStableTimer = reset,
        maxObservedLength = max(maxObservedLength, currentLength),
        similarity = similarity,
    )

    private fun canonicalize(text: String): String =
        text.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)

    private fun levenshteinSimilarity(leftRaw: String, rightRaw: String): Float {
        val left = leftRaw.forComparison()
        val right = rightRaw.forComparison()
        val maxLength = max(left.length, right.length)
        if (maxLength == 0) return 1f
        val distance = levenshteinDistance(left, right)
        return (1f - distance.toFloat() / maxLength.toFloat()).coerceIn(0f, 1f)
    }

    private fun String.forComparison(): String = if (length <= MAX_COMPARISON_LENGTH) {
        this
    } else {
        take(MAX_COMPARISON_LENGTH / 2) + takeLast(MAX_COMPARISON_LENGTH / 2)
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        left.forEachIndexed { leftIndex, leftChar ->
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                val insertion = current[rightIndex] + 1
                val deletion = previous[rightIndex + 1] + 1
                val substitution = previous[rightIndex] + if (leftChar == rightChar) 0 else 1
                current[rightIndex + 1] = minOf(insertion, deletion, substitution)
            }
            current.copyInto(previous)
        }
        return previous[right.length]
    }
}
