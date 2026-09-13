package com.gameocr.app.ocr

/** Requires all confirmed erase targets, not all pixels in their enclosing text rectangle. */
internal object TextRepairPatchCoveragePolicy {
    fun canDisplay(
        repairedPixels: Int,
        residualPixels: Int,
        hasPatchPixels: Boolean,
        requiredCoveragePixels: Int = 0,
        repairedRequiredCoveragePixels: Int = requiredCoveragePixels,
        completionReliable: Boolean = true,
    ): Boolean = hasPatchPixels &&
        repairedPixels > 0 &&
        residualPixels == 0 &&
        completionReliable &&
        requiredCoverageFraction(
            requiredCoveragePixels = requiredCoveragePixels,
            repairedRequiredCoveragePixels = repairedRequiredCoveragePixels,
        ) >= MIN_REQUIRED_COVERAGE_FRACTION

    private fun requiredCoverageFraction(
        requiredCoveragePixels: Int,
        repairedRequiredCoveragePixels: Int,
    ): Float = if (requiredCoveragePixels <= 0) {
        1f
    } else {
        repairedRequiredCoveragePixels.coerceIn(0, requiredCoveragePixels).toFloat() /
            requiredCoveragePixels
    }

    private const val MIN_REQUIRED_COVERAGE_FRACTION = 1f
}
