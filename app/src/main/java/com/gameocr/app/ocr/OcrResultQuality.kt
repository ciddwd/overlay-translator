package com.gameocr.app.ocr

import java.util.Locale

internal data class OcrResultQualityIssue(
    val blockCount: Int,
    val nonWhitespaceChars: Int,
    val averageConfidence: Float,
    val lowConfidenceBlockCount: Int,
    val lowConfidenceRatio: Float
) {
    fun toLogString(): String =
        "blocks=$blockCount chars=$nonWhitespaceChars avgConf=${averageConfidence.toFixed3()} " +
            "lowConf=$lowConfidenceBlockCount ratio=${lowConfidenceRatio.toFixed3()}"
}

internal fun findOcrResultQualityIssue(blocks: List<TextBlock>): OcrResultQualityIssue? {
    if (blocks.isEmpty()) return null

    val confidences = blocks.map { it.confidence.coerceIn(0f, 1f) }
    val average = confidences.average().toFloat()
    val lowCount = confidences.count { it < LOW_CONFIDENCE_BLOCK_THRESHOLD }
    val lowRatio = lowCount.toFloat() / blocks.size
    val chars = blocks.sumOf { block -> block.text.count { !it.isWhitespace() } }
    val issue = OcrResultQualityIssue(
        blockCount = blocks.size,
        nonWhitespaceChars = chars,
        averageConfidence = average,
        lowConfidenceBlockCount = lowCount,
        lowConfidenceRatio = lowRatio
    )

    val veryLowAverage = average < MIN_AVERAGE_CONFIDENCE && lowRatio >= MIN_LOW_CONFIDENCE_RATIO
    val mostlyLowShortResult = average < SOFT_AVERAGE_CONFIDENCE &&
        lowRatio >= MOSTLY_LOW_CONFIDENCE_RATIO &&
        chars <= SHORT_RESULT_CHAR_LIMIT
    return if (veryLowAverage || mostlyLowShortResult) issue else null
}

private fun Float.toFixed3(): String = String.format(Locale.US, "%.3f", this)

private const val LOW_CONFIDENCE_BLOCK_THRESHOLD = 0.45f
private const val MIN_AVERAGE_CONFIDENCE = 0.35f
private const val SOFT_AVERAGE_CONFIDENCE = 0.45f
private const val MIN_LOW_CONFIDENCE_RATIO = 0.50f
private const val MOSTLY_LOW_CONFIDENCE_RATIO = 0.80f
private const val SHORT_RESULT_CHAR_LIMIT = 24
