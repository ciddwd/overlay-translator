package com.gameocr.app.ocr

import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.RenderMode

/**
 * Keeps the floating-window "merge all" action out of geometric OCR clustering.
 *
 * ALL is a presentation policy: atomic OCR regions must remain intact until the reading-order
 * sorter has consumed their geometry. The other strengths still use the existing OCR merge path.
 */
internal object OcrMergePresentationPolicy {
    fun deferGeometricMerge(
        renderMode: RenderMode,
        mergeAdjacentBlocks: Boolean,
        mergeStrength: MergeStrength,
    ): Boolean =
        renderMode == RenderMode.FLOATING_WINDOW &&
            mergeAdjacentBlocks &&
            mergeStrength == MergeStrength.ALL
}
