package com.gameocr.app.ocr

import kotlin.math.exp

internal data class MangaTokenPrediction(
    val tokenId: Int,
    val confidence: Float,
)

internal data class MangaSequenceConfidence(
    val average: Float,
    val minimum: Float,
    val tokenCount: Int,
)

/**
 * Selects the greedy decoder token and, for Debug diagnostics, calculates its stable softmax
 * probability. The probability is meaningful inside manga-ocr only; it must not be compared to a
 * Paddle CTC confidence until real-device samples have been calibrated.
 */
internal fun mangaTokenPrediction(
    logitsByStep: Array<FloatArray>,
    calculateConfidence: Boolean,
): MangaTokenPrediction {
    require(logitsByStep.isNotEmpty()) { "Manga OCR logits must contain at least one step" }
    val logits = logitsByStep.last()
    require(logits.isNotEmpty()) { "Manga OCR logits vocabulary must not be empty" }

    var bestIndex = 0
    var bestLogit = logits[0]
    for (index in 1 until logits.size) {
        if (logits[index] > bestLogit) {
            bestLogit = logits[index]
            bestIndex = index
        }
    }
    if (!calculateConfidence) {
        return MangaTokenPrediction(bestIndex, Float.NaN)
    }

    var denominator = 0.0
    for (logit in logits) {
        denominator += exp((logit - bestLogit).toDouble())
    }
    val confidence = if (denominator.isFinite() && denominator > 0.0) {
        (1.0 / denominator).toFloat().coerceIn(0f, 1f)
    } else {
        Float.NaN
    }
    return MangaTokenPrediction(bestIndex, confidence)
}

internal fun mangaSequenceConfidence(confidences: List<Float>): MangaSequenceConfidence {
    val valid = confidences.filter(Float::isFinite)
    if (valid.isEmpty()) {
        return MangaSequenceConfidence(Float.NaN, Float.NaN, tokenCount = 0)
    }
    return MangaSequenceConfidence(
        average = valid.average().toFloat(),
        minimum = valid.min(),
        tokenCount = valid.size,
    )
}
