package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Removes only single-member, ultra-wide fallback groups that look like panel borders and have no
 * supporting RT-DETR text detection. Ordinary text and model bubble groups remain untouched.
 */
internal object MangaOcrTextEvidencePolicy {
    data class Result(
        val entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        val droppedIndices: List<Int>,
        val textSupportedEntryIndices: Set<Int>,
    )

    fun filter(
        entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        textDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
        evidenceAvailable: Boolean,
    ): Result {
        if (!evidenceAvailable) {
            return Result(entries, emptyList(), emptySet())
        }

        val kept = mutableListOf<MangaOcrBubbleGroupingPolicy.Entry>()
        val dropped = mutableListOf<Int>()
        val textSupported = mutableSetOf<Int>()
        entries.forEachIndexed { index, entry ->
            val expectedKind = when (entry.guidedSource) {
                BubbleModelRegrouper.Source.MODEL ->
                    MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE
                BubbleModelRegrouper.Source.LEGACY_FALLBACK ->
                    MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE
                null -> null
            }
            val supportingDetections = if (expectedKind == null) {
                emptyList()
            } else {
                textDetections.filter { detection ->
                    detection.kind == expectedKind &&
                        overlaps(entry.bubble.contentRect, detection)
                }
            }
            val isUnsupportedLineArtifact =
                entry.guidedSource == BubbleModelRegrouper.Source.LEGACY_FALLBACK &&
                    entry.bubble.memberIndices.size == 1 &&
                    aspectRatio(entry.bubble.contentRect) >= MIN_LINE_ARTIFACT_ASPECT_RATIO &&
                    supportingDetections.isEmpty()
            if (isUnsupportedLineArtifact) {
                dropped += index
            } else {
                val keptIndex = kept.size
                kept += if (supportingDetections.isNotEmpty()) {
                    textSupported += keptIndex
                    val evidenceBounds = supportingDetections
                        .map { detection -> detection.toIntRect() }
                        .reduce(::union)
                    val recognitionBounds = when (entry.guidedSource) {
                        // The bubble box remains available to the mask/rendering path. OCR should
                        // see only DBNet content plus RT-DETR's text-in-bubble evidence.
                        BubbleModelRegrouper.Source.MODEL ->
                            union(entry.bubble.contentRect, evidenceBounds)
                        BubbleModelRegrouper.Source.LEGACY_FALLBACK ->
                            union(entry.bubble.rect, evidenceBounds)
                        null -> entry.bubble.rect
                    }
                    entry.copy(
                        bubble = entry.bubble.copy(
                            rect = recognitionBounds,
                        ),
                    )
                } else {
                    entry
                }
            }
        }
        return Result(kept, dropped, textSupported)
    }

    private fun aspectRatio(bounds: IntRect): Float {
        val shorter = minOf(bounds.width, bounds.height).coerceAtLeast(1)
        val longer = maxOf(bounds.width, bounds.height)
        return longer.toFloat() / shorter
    }

    private fun overlaps(
        bounds: IntRect,
        detection: MangaBubbleDetectionPostprocessor.Detection,
    ): Boolean =
        minOf(bounds.right.toFloat(), detection.right) >
            maxOf(bounds.left.toFloat(), detection.left) &&
            minOf(bounds.bottom.toFloat(), detection.bottom) >
            maxOf(bounds.top.toFloat(), detection.top)

    private fun MangaBubbleDetectionPostprocessor.Detection.toIntRect(): IntRect = IntRect(
        left = floor(left).toInt().coerceAtLeast(0),
        top = floor(top).toInt().coerceAtLeast(0),
        right = ceil(right).toInt().coerceAtLeast(0),
        bottom = ceil(bottom).toInt().coerceAtLeast(0),
    )

    private fun union(first: IntRect, second: IntRect): IntRect = IntRect(
        left = minOf(first.left, second.left),
        top = minOf(first.top, second.top),
        right = maxOf(first.right, second.right),
        bottom = maxOf(first.bottom, second.bottom),
    )

    internal const val MIN_LINE_ARTIFACT_ASPECT_RATIO: Float = 8f
}
