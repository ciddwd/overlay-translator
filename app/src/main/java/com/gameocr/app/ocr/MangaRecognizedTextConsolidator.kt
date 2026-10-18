package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import java.text.Normalizer
import java.util.Locale

/** Resolves residual fallback fragments using both recognized content and physical overlap. */
internal object MangaRecognizedTextConsolidator {
    data class Result(
        val entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        val texts: List<String>,
        val absorbedByOwner: Map<Int, Int>,
        val retainedIndices: List<Int>,
    )

    fun consolidate(entries: List<MangaOcrBubbleGroupingPolicy.Entry>, texts: List<String>): Result {
        require(entries.size == texts.size)
        val normalized = texts.map { text ->
            Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                .filter { it.isLetterOrDigit() }
        }
        val absorbed = entries.indices.mapNotNull { index ->
            val fragment = entries[index]
            val text = normalized[index]
            if (fragment.guidedSource != BubbleModelRegrouper.Source.LEGACY_FALLBACK ||
                fragment.regionGranularity == TextRegionGranularity.FREE_TEXT || text.length < 3
            ) return@mapNotNull null
            val owners = entries.indices.filter { owner ->
                val entry = entries[owner]
                entry.guidedSource == BubbleModelRegrouper.Source.MODEL &&
                    entry.modelBubbleIndex != null &&
                    entry.regionGranularity == TextRegionGranularity.BUBBLE &&
                    normalized[owner].length >= text.length && normalized[owner].contains(text) &&
                    coverage(fragment.bubble.contentRect, entry.bubble.contentRect) >= 0.70
            }
            owners.singleOrNull()?.let { owner -> index to owner }
        }.toMap()
        val fragmentsByOwner = absorbed.entries.groupBy({ it.value }, { it.key })
        val keptIndices = entries.indices.filterNot(absorbed::containsKey)
        return Result(
            entries = keptIndices.map { index ->
                val entry = entries[index]
                val fragments = fragmentsByOwner[index].orEmpty().map(entries::get)
                if (fragments.isEmpty()) entry else entry.copy(bubble = entry.bubble.copy(
                    rect = fragments.fold(entry.bubble.rect) { bounds, other -> union(bounds, other.bubble.rect) },
                    contentRect = fragments.fold(entry.bubble.contentRect) { bounds, other -> union(bounds, other.bubble.contentRect) },
                    memberIndices = (entry.bubble.memberIndices + fragments.flatMap { it.bubble.memberIndices }).distinct().sorted(),
                ))
            },
            texts = keptIndices.map(texts::get),
            absorbedByOwner = absorbed,
            retainedIndices = keptIndices,
        )
    }

    private fun coverage(fragment: IntRect, owner: IntRect): Double {
        val area = fragment.width.toDouble() * fragment.height
        if (area <= 0) return 0.0
        return (minOf(fragment.right, owner.right) - maxOf(fragment.left, owner.left)).coerceAtLeast(0).toDouble() *
            (minOf(fragment.bottom, owner.bottom) - maxOf(fragment.top, owner.top)).coerceAtLeast(0) / area
    }

    private fun union(a: IntRect, b: IntRect) = IntRect(
        minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom),
    )
}
