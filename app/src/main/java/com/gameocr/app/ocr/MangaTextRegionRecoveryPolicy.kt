package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.ceil
import kotlin.math.floor

/** Uses already computed text detections when DBNet has no corresponding OCR group. */
internal object MangaTextRegionRecoveryPolicy {
    data class Result(
        val evidence: MangaOcrTextEvidencePolicy.Result,
        val recoveredDetectionIndices: List<Int>,
        val skippedDetectionIndices: List<Int>,
    )

    fun recover(
        evidence: MangaOcrTextEvidencePolicy.Result,
        textDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
        imageWidth: Int,
        imageHeight: Int,
    ): Result {
        require(imageWidth > 0 && imageHeight > 0)
        val entries = evidence.entries.toMutableList()
        val supported = evidence.textSupportedEntryIndices.toMutableSet()
        val assignments = evidence.assignments.toMutableList()
        val recovered = mutableListOf<Int>()
        val skipped = mutableListOf<Int>()
        // Confidence order makes overlap suppression deterministic and independent of scan order.
        val candidates = evidence.unassignedTextBubbleDetectionIndices.sortedWith(
            compareByDescending<Int> { textDetections.getOrNull(it)?.confidence ?: -1f }
                .thenBy { it },
        )
        for (index in candidates) {
            val detection = textDetections.getOrNull(index)
            val bounds = detection?.validBounds(imageWidth, imageHeight)
            if (bounds == null || entries.any { overlapsText(bounds, it.bubble.contentRect) }) {
                skipped += index
                continue
            }
            val entryIndex = entries.size
            entries += MangaOcrBubbleGroupingPolicy.Entry(
                bubble = Bubble(rect = bounds, contentRect = bounds, memberIndices = emptyList()),
                // Do not fabricate DBNet members or a bubble-mask association.
                guidedSource = null,
                modelBubbleIndex = null,
                regionGranularity = TextRegionGranularity.BUBBLE,
            )
            assignments += MangaTextEvidenceMatcher.EntryAssignment(index, entryIndex, 1f, 1f, 0.0)
            supported += entryIndex
            recovered += index
        }
        return Result(
            evidence = evidence.copy(
                entries = entries,
                textSupportedEntryIndices = supported,
                assignments = assignments,
                unassignedTextBubbleDetectionIndices =
                    evidence.unassignedTextBubbleDetectionIndices - recovered.toSet(),
            ),
            recoveredDetectionIndices = recovered,
            skippedDetectionIndices = skipped,
        )
    }

    private fun MangaBubbleDetectionPostprocessor.Detection.validBounds(w: Int, h: Int): IntRect? {
        if (kind != MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE ||
            !confidence.isFinite() || confidence < MangaBubbleDetectionPostprocessor.DEFAULT_CONFIDENCE_THRESHOLD ||
            !left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()
        ) return null
        val bounds = IntRect(
            floor(left).toInt().coerceIn(0, w), floor(top).toInt().coerceIn(0, h),
            ceil(right).toInt().coerceIn(0, w), ceil(bottom).toInt().coerceIn(0, h),
        )
        return bounds.takeIf {
            it.width >= MangaBubbleDetectionPostprocessor.MIN_BOX_SIDE_PX &&
                it.height >= MangaBubbleDetectionPostprocessor.MIN_BOX_SIDE_PX
        }
    }

    private fun overlapsText(a: IntRect, b: IntRect): Boolean {
        val area = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0).toLong() *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        val smaller = minOf(a.width.toLong() * a.height, b.width.toLong() * b.height)
        return smaller > 0 && area.toDouble() / smaller >= MangaTextEvidenceMatcher.MIN_BOUNDS_COVERAGE
    }
}
