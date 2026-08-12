package com.gameocr.app.ocr

/** Rejects a partial repair patch when more source-support pixels remain than were repaired. */
internal object TextRepairPatchCoveragePolicy {
    fun canDisplay(
        repairedPixels: Int,
        residualPixels: Int,
        hasPatchPixels: Boolean,
    ): Boolean = hasPatchPixels &&
        repairedPixels > 0 &&
        residualPixels.coerceAtLeast(0) <= repairedPixels
}
