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
