package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.ceil
import kotlin.math.floor

/** Splits fallback crops crossing multiple text regions before they can recognize both twice. */
internal object MangaTextMemberConsolidator {
    data class Result(val entries: List<MangaOcrBubbleGroupingPolicy.Entry>, val movedMembers: Map<Int, Int>)

    fun consolidate(
        entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        memberBounds: List<IntRect>,
        textDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
    ): Result {
        val assignments = MangaTextEvidenceMatcher.assignTextBubbleDetections(entries, textDetections)
        val moved = linkedMapOf<Int, Int>()
        entries.filter { it.guidedSource == BubbleModelRegrouper.Source.LEGACY_FALLBACK &&
            it.regionGranularity != TextRegionGranularity.FREE_TEXT }.forEach { entry ->
            entry.bubble.memberIndices.forEach memberLoop@ { member ->
                val bounds = memberBounds.getOrNull(member) ?: return@memberLoop
                val owners = assignments.filter { assignment ->
                    val detection = textDetections[assignment.detectionIndex]
                    val centerX = (bounds.left + bounds.right) / 2f
                    val centerY = (bounds.top + bounds.bottom) / 2f
                    val detectionWidth = detection.right - detection.left
                    val detectionHeight = detection.bottom - detection.top
                    // A tiny corner already covered by one larger word may have weaker detector
                    // evidence. Give it to that word so it is erased once with the complete text,
                    // instead of recognizing it separately or leaving a protected stroke behind.
                    val smallWordCorner = detection.confidence >= 0.35f &&
                        bounds.width.toDouble() * bounds.height <= detectionWidth * detectionHeight * 0.10 &&
                        maxOf(bounds.width, bounds.height) <= minOf(detectionWidth, detectionHeight) * 0.40f
                    (detection.confidence >= 0.50f || smallWordCorner) && centerX >= detection.left && centerX < detection.right &&
                        centerY >= detection.top && centerY < detection.bottom && coverage(bounds, detection) >= 0.60
                }.map { it.entryIndex }.distinct()
                owners.singleOrNull()?.let { moved[member] = it }
            }
        }
        if (moved.isEmpty()) return Result(entries, emptyMap())
        val byOwner = moved.entries.groupBy({ it.value }, { it.key })
        val updated = entries.mapIndexedNotNull { index, entry ->
            val added = byOwner[index].orEmpty()
            val retained = entry.bubble.memberIndices.filterNot(moved::containsKey)
            if (retained.isEmpty() && added.isEmpty()) return@mapIndexedNotNull entry.takeIf { entry.bubble.memberIndices.isEmpty() }
            val members = (retained + added).distinct().sorted()
            val changedFallback = retained.size != entry.bubble.memberIndices.size
            val content = if (changedFallback) {
                retained.map(memberBounds::get).reduce(::union)
            } else {
                added.fold(entry.bubble.contentRect) { bounds, member -> union(bounds, memberBounds[member]) }
            }
            entry.copy(bubble = entry.bubble.copy(memberIndices = members, contentRect = content,
                rect = if (changedFallback) content else added.fold(entry.bubble.rect) { b, m -> union(b, memberBounds[m]) }))
        }
        return Result(updated, moved.mapValues { (_, owner) -> entries[owner].modelBubbleIndex ?: owner })
    }

    private fun coverage(bounds: IntRect, d: MangaBubbleDetectionPostprocessor.Detection): Double {
        val area = bounds.width.toDouble() * bounds.height
        if (area <= 0) return 0.0
        val l = floor(d.left).toInt(); val t = floor(d.top).toInt()
        val r = ceil(d.right).toInt(); val b = ceil(d.bottom).toInt()
        return (minOf(bounds.right, r) - maxOf(bounds.left, l)).coerceAtLeast(0).toDouble() *
            (minOf(bounds.bottom, b) - maxOf(bounds.top, t)).coerceAtLeast(0) / area
    }

    private fun union(a: IntRect, b: IntRect) = IntRect(minOf(a.left, b.left), minOf(a.top, b.top),
        maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
}
