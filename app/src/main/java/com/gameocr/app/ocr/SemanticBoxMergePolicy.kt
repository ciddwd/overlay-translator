package com.gameocr.app.ocr

/**
 * Keeps detector/model-provided semantic regions intact while still allowing line-level OCR and
 * detector-unconfirmed legacy manga bubbles to use the geometric box merger.
 *
 * Eligible items are grouped only while they are contiguous. A protected semantic item therefore
 * acts as a hard boundary: boxes on opposite sides can never be merged through it.
 */
internal object SemanticBoxMergePolicy {

    fun isLineLevel(granularity: TextRegionGranularity): Boolean = when (granularity) {
        TextRegionGranularity.UNKNOWN,
        TextRegionGranularity.LINE -> true
        TextRegionGranularity.PARAGRAPH,
        TextRegionGranularity.BUBBLE,
        TextRegionGranularity.FREE_TEXT -> false
    }

    /**
     * Manga OCR marks both detector-confirmed regions and its legacy geometry fallback as BUBBLE.
     * Only the former has a model parent. Treating every BUBBLE as final makes the user's merge
     * setting a no-op whenever the detector falls back to separate vertical columns.
     */
    fun isMergeEligible(
        granularity: TextRegionGranularity,
        parentRegionId: Int?,
        mergeStandaloneFreeText: Boolean = false,
    ): Boolean = isLineLevel(granularity) ||
        (granularity == TextRegionGranularity.BUBBLE && parentRegionId == null) ||
        (mergeStandaloneFreeText &&
            granularity == TextRegionGranularity.FREE_TEXT &&
            parentRegionId == null)

    fun <T> mergeEligibleRuns(
        items: List<T>,
        isEligible: (T) -> Boolean,
        mergeRun: (List<T>) -> List<T>,
    ): List<T> {
        if (items.isEmpty()) return emptyList()

        val output = ArrayList<T>(items.size)
        val pending = mutableListOf<T>()

        fun flushPending() {
            if (pending.isEmpty()) return
            output += mergeRun(pending.toList())
            pending.clear()
        }

        items.forEach { item ->
            if (isEligible(item)) {
                pending += item
            } else {
                flushPending()
                output += item
            }
        }
        flushPending()
        return output
    }
}
