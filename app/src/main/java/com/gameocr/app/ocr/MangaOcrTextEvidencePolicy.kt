package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Gives each RT-DETR text-in-bubble detection to one OCR group and keeps TEXT_FREE from expanding
 * recognition crops. Single-member, ultra-wide fallback artifacts remain filtered.
 */
internal object MangaOcrTextEvidencePolicy {
    data class Result(
        val entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        val droppedIndices: List<Int>,
        val textSupportedEntryIndices: Set<Int>,
        val assignments: List<MangaTextEvidenceMatcher.EntryAssignment>,
        val unassignedTextBubbleDetectionIndices: Set<Int>,
        val duplicateCropEntryIndices: Set<Int>,
    )

    fun filter(
        entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        textDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
        evidenceAvailable: Boolean,
    ): Result {
        if (!evidenceAvailable) {
            return Result(
                entries = entries,
                droppedIndices = emptyList(),
                textSupportedEntryIndices = emptySet(),
                assignments = emptyList(),
                unassignedTextBubbleDetectionIndices = emptySet(),
                duplicateCropEntryIndices = emptySet(),
            )
        }

        val assignments = MangaTextEvidenceMatcher.assignTextBubbleDetections(
            entries = entries,
            textDetections = textDetections,
        )
        val assignmentsByEntry = assignments.groupBy(
            MangaTextEvidenceMatcher.EntryAssignment::entryIndex,
        )
        val assignedDetectionIndices = assignments
            .mapTo(mutableSetOf(), MangaTextEvidenceMatcher.EntryAssignment::detectionIndex)
        val unassignedTextBubbleDetectionIndices = textDetections.indices
            .filterTo(mutableSetOf()) { index ->
                textDetections[index].kind ==
                    MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE &&
                    index !in assignedDetectionIndices
            }
        val kept = mutableListOf<MangaOcrBubbleGroupingPolicy.Entry>()
        val keptIndexByOriginalIndex = mutableMapOf<Int, Int>()
        val dropped = mutableListOf<Int>()
        val textSupported = mutableSetOf<Int>()
        entries.forEachIndexed { index, entry ->
            val supportingDetections = assignmentsByEntry[index].orEmpty()
                .map { assignment -> textDetections[assignment.detectionIndex] }
            val isUnsupportedLineArtifact =
                entry.guidedSource == BubbleModelRegrouper.Source.LEGACY_FALLBACK &&
                    entry.bubble.memberIndices.size == 1 &&
                    aspectRatio(entry.bubble.contentRect) >= MIN_LINE_ARTIFACT_ASPECT_RATIO &&
                    supportingDetections.isEmpty()
            if (isUnsupportedLineArtifact) {
                dropped += index
            } else {
                val keptIndex = kept.size
                keptIndexByOriginalIndex[index] = keptIndex
                val recognitionBase = if (
                    entry.guidedSource == BubbleModelRegrouper.Source.MODEL
                ) {
                    entry.bubble.contentRect
                } else {
                    entry.bubble.rect
                }
                kept += if (supportingDetections.isNotEmpty()) {
                    textSupported += keptIndex
                    val evidenceBounds = supportingDetections
                        .map { detection -> detection.toIntRect() }
                        .reduce(::union)
                    val recognitionBounds = union(recognitionBase, evidenceBounds)
                    entry.copy(
                        bubble = entry.bubble.copy(
                            rect = recognitionBounds,
                        ),
                    )
                } else {
                    entry.copy(
                        bubble = entry.bubble.copy(
                            rect = recognitionBase,
                        ),
                    )
                }
            }
        }
        val remappedAssignments = assignments.mapNotNull { assignment ->
            keptIndexByOriginalIndex[assignment.entryIndex]?.let { keptIndex ->
                assignment.copy(entryIndex = keptIndex)
            }
        }
        val duplicateCropEntryIndices = kept.indices
            .groupBy { index -> kept[index].bubble.rect }
            .values
            .filter { indices -> indices.size > 1 }
            .flatten()
            .toSet()
        return Result(
            entries = kept,
            droppedIndices = dropped,
            textSupportedEntryIndices = textSupported,
            assignments = remappedAssignments,
            unassignedTextBubbleDetectionIndices = unassignedTextBubbleDetectionIndices,
            duplicateCropEntryIndices = duplicateCropEntryIndices,
        )
    }

    private fun aspectRatio(bounds: IntRect): Float {
        val shorter = minOf(bounds.width, bounds.height).coerceAtLeast(1)
        val longer = maxOf(bounds.width, bounds.height)
        return longer.toFloat() / shorter
    }

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
