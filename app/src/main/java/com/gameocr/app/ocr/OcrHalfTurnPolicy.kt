package com.gameocr.app.ocr

internal data class HalfTurnReading(val text: String, val confidence: Float)
internal data class HalfTurnReadingPair(val current: HalfTurnReading, val opposite: HalfTurnReading)

/** Compare actual recognition of identical pixels in both directions; numbers cannot decide. */
internal object OcrHalfTurnPolicy {
    const val SOURCE = "paddle-ocr-half-turn"
    const val MAX_LINES = 4
    private const val MIN_WINNING_CONFIDENCE = 0.8f
    private const val MIN_CONFIDENCE_GAIN = 0.1f

    fun isCandidate(width: Int, height: Int): Boolean =
        width >= 8 && height >= 8 && width >= height * 1.5f

    fun correctedRotation(normalization: Int): Int = ((normalization % 360 + 540) % 360)

    fun decide(pairs: List<HalfTurnReadingPair>): OrientationResult? {
        val votes = pairs.mapNotNull { pair ->
            when {
                wins(pair.opposite, pair.current) -> 180 to pair.opposite.confidence
                wins(pair.current, pair.opposite) -> 0 to pair.current.confidence
                else -> null
            }
        }
        if (votes.size < 2) return null
        val opposite = votes.filter { it.first == 180 }
        val current = votes.filter { it.first == 0 }
        val winner = listOf(opposite, current).firstOrNull {
            it.size >= 2 && it.size.toFloat() / votes.size >= 0.75f
        } ?: return null
        return OrientationResult(TextOrientation.HORIZONTAL_LTR,
            winner.map { it.second }.average().toFloat(), winner.first().first, SOURCE)
    }

    private fun wins(winner: HalfTurnReading, other: HalfTurnReading): Boolean =
        winner.confidence in MIN_WINNING_CONFIDENCE..1f && other.confidence in 0f..1f &&
            winner.confidence - other.confidence >= MIN_CONFIDENCE_GAIN - 0.000001f &&
            winner.text.count(Char::isLetter) >= 3
}

/**
 * Guards the full-page OCR rerun after a 180-degree hint. A hint can be wrong; the two OCR passes
 * themselves are the evidence, so the rotated result replaces the first only when it reads
 * clearly better, and a first pass that already reads confidently is not rerun at all.
 */
internal object OcrHalfTurnRerunPolicy {
    /** The rotated pass must carry this much more confident text than the first pass. */
    const val MIN_SCORE_GAIN = 1.2f
    private const val RELIABLE_MIN_BLOCKS = 3
    private const val RELIABLE_MIN_CHARS = 20
    private const val RELIABLE_MIN_AVERAGE_CONFIDENCE = 0.85f

    /**
     * Confidence-weighted non-whitespace characters. Engines that report no confidence (always
     * 1.0, such as Baidu) reduce to a plain character count.
     */
    fun score(blocks: List<TextBlock>): Float = blocks.sumOf { block ->
        block.confidence.coerceIn(0f, 1f).toDouble() * block.text.count { !it.isWhitespace() }
    }.toFloat()

    fun prefersRerun(first: List<TextBlock>, rerun: List<TextBlock>): Boolean {
        val rerunScore = score(rerun)
        return rerunScore > 0f && rerunScore >= score(first) * MIN_SCORE_GAIN
    }

    /**
     * Upside-down lines decode to few, low-confidence characters, so several confident lines mean
     * the page is already upright. Only meaningful for engines that report real confidences.
     */
    fun firstPassIsReliable(blocks: List<TextBlock>): Boolean {
        val read = blocks.filter { block -> block.text.any { !it.isWhitespace() } }
        if (read.size < RELIABLE_MIN_BLOCKS) return false
        if (read.sumOf { block -> block.text.count { !it.isWhitespace() } } < RELIABLE_MIN_CHARS) return false
        return read.map { it.confidence.coerceIn(0f, 1f) }.average() >= RELIABLE_MIN_AVERAGE_CONFIDENCE
    }
}
