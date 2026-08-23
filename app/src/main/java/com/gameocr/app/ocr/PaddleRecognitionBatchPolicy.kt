package com.gameocr.app.ocr

internal object PaddleRecognitionBatchPolicy {
    const val DEFAULT_BATCH_SIZE = 4
    private const val MAX_WIDTH_RATIO = 2f

    /**
     * Groups recognition crops with similar widths so a short crop is not padded to an unrelated
     * very long crop. Returned values are positions in [targetWidths], not OCR result IDs.
     */
    fun plan(
        targetWidths: List<Int>,
        maxBatchSize: Int = DEFAULT_BATCH_SIZE,
        maxWidthRatio: Float = MAX_WIDTH_RATIO,
    ): List<List<Int>> {
        if (targetWidths.isEmpty()) return emptyList()
        val safeBatchSize = maxBatchSize.coerceAtLeast(1)
        val safeWidthRatio = maxWidthRatio.takeIf { it.isFinite() && it >= 1f } ?: 1f
        val sortedPositions = targetWidths.indices.sortedBy { targetWidths[it].coerceAtLeast(1) }
        val result = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var minimumWidth = 1

        fun flush() {
            if (current.isNotEmpty()) result += current.toList()
            current = mutableListOf()
        }

        for (position in sortedPositions) {
            val width = targetWidths[position].coerceAtLeast(1)
            val exceedsBatch = current.size >= safeBatchSize
            val exceedsWidthBucket = current.isNotEmpty() && width > minimumWidth * safeWidthRatio
            if (exceedsBatch || exceedsWidthBucket) flush()
            if (current.isEmpty()) minimumWidth = width
            current += position
        }
        flush()
        return result
    }
}
