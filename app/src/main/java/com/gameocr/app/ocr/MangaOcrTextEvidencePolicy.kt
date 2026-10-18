package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Gives each RT-DETR text-in-bubble detection to one OCR group and keeps TEXT_FREE from expanding
 * recognition crops. Single-member, ultra-wide fallback artifacts remain filtered unless the
 * detector explicitly classified their complete region as standalone text.
 */
internal object MangaOcrTextEvidencePolicy {
    data class Result(
        val entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        val droppedIndices: List<Int>,
        val textSupportedEntryIndices: Set<Int>,
        val assignments: List<MangaTextEvidenceMatcher.EntryAssignment>,
        val unassignedTextBubbleDetectionIndices: Set<Int>,
        val duplicateCropEntryIndices: Set<Int>,
    ) {
        fun toModelGroups(): List<BubbleModelRegrouper.Group> = entries.map { entry ->
            BubbleModelRegrouper.Group(
                source = entry.guidedSource ?: BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                modelBubbleIndex = entry.modelBubbleIndex,
                cropBounds = entry.bubble.rect,
                contentBounds = entry.bubble.contentRect,
                memberIndices = entry.bubble.memberIndices,
            )
        }
    }

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
        // A model text extent can include DBNet columns which were left in the fallback path.
        // Recognizing those columns separately repeats a substring of the complete balloon.
        // Move their members to the sole supported owner; preserve free text and ambiguous ties.
        val absorbedByOwner = entries.indices.mapNotNull { index ->
            val entry = entries[index]
            if (entry.guidedSource != BubbleModelRegrouper.Source.LEGACY_FALLBACK ||
                entry.regionGranularity == TextRegionGranularity.FREE_TEXT
            ) return@mapNotNull null
            val owners = assignments.filter { assignment ->
                coverage(entry.bubble.contentRect, textDetections[assignment.detectionIndex].toIntRect()) >=
                    MIN_FRAGMENT_COVERAGE
            }.map { it.entryIndex }.distinct()
            owners.singleOrNull()?.let { owner -> index to owner }
        }.toMap()
        val absorbedEntries = absorbedByOwner.entries.groupBy({ it.value }, { it.key })
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
            if (index in absorbedByOwner) {
                dropped += index
                return@forEachIndexed
            }
            val supportingDetections = assignmentsByEntry[index].orEmpty()
                .map { assignment -> textDetections[assignment.detectionIndex] }
            val isUnsupportedLineArtifact =
                entry.guidedSource == BubbleModelRegrouper.Source.LEGACY_FALLBACK &&
                    entry.regionGranularity != TextRegionGranularity.FREE_TEXT &&
                    entry.bubble.memberIndices.size == 1 &&
                    aspectRatio(entry.bubble.contentRect) >= MIN_LINE_ARTIFACT_ASPECT_RATIO &&
                    supportingDetections.isEmpty()
            if (isUnsupportedLineArtifact) {
                dropped += index
            } else {
                val keptIndex = kept.size
                keptIndexByOriginalIndex[index] = keptIndex
                val absorbed = absorbedEntries[index].orEmpty().map(entries::get)
                val contentBounds = absorbed.fold(entry.bubble.contentRect) { bounds, fragment ->
                    union(bounds, fragment.bubble.contentRect)
                }
                val members = (entry.bubble.memberIndices +
                    absorbed.flatMap { it.bubble.memberIndices }).distinct().sorted()
                val recognitionBase = if (
                    entry.guidedSource == BubbleModelRegrouper.Source.MODEL
                ) {
                    contentBounds
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
                            // TEXT_BUBBLE is text extent, not padding or the enclosing balloon.
                            // Recognition, erasure and rendering must retain the same content.
                            contentRect = union(contentBounds, evidenceBounds),
                            memberIndices = members,
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

    private fun coverage(fragment: IntRect, owner: IntRect): Double {
        val area = fragment.width.toDouble() * fragment.height
        if (area <= 0) return 0.0
        val overlapWidth = (minOf(fragment.right, owner.right) - maxOf(fragment.left, owner.left))
            .coerceAtLeast(0)
        val overlapHeight = (minOf(fragment.bottom, owner.bottom) - maxOf(fragment.top, owner.top))
            .coerceAtLeast(0)
        return overlapWidth.toDouble() * overlapHeight / area
    }

    internal const val MIN_LINE_ARTIFACT_ASPECT_RATIO: Float = 8f
    private const val MIN_FRAGMENT_COVERAGE = 0.80
}
