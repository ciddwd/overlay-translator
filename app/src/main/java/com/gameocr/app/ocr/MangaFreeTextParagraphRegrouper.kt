package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect

/**
 * Conservatively restores a complete vertical paragraph when the bubble detector left adjacent
 * free-text columns in separate fallback groups.
 *
 * The regrouped crop is sent through manga-ocr as a whole. This intentionally does not classify
 * or delete ruby: the recognition model sees the base text and annotations together, while an
 * uncertain geometric decision can never discard OCR input.
 */
internal object MangaFreeTextParagraphRegrouper {

    data class Result(
        val entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        val mergedOriginalEntryIndices: List<List<Int>>,
    )

    fun regroup(
        entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        memberBounds: List<IntRect>,
    ): Result {
        if (entries.size < 2 || memberBounds.isEmpty()) {
            return Result(entries, emptyList())
        }

        val parent = IntArray(entries.size) { it }
        val componentMembers = Array(entries.size) { index ->
            entries[index].bubble.memberIndices.toMutableSet()
        }

        fun find(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        data class Edge(val first: Int, val second: Int, val gap: Int)
        val edges = buildList {
            entries.indices.forEach { first ->
                for (second in first + 1 until entries.size) {
                    paragraphEdge(entries[first], entries[second], memberBounds)?.let { gap ->
                        add(Edge(first, second, gap))
                    }
                }
            }
        }.sortedWith(compareBy(Edge::gap, Edge::first, Edge::second))

        edges.forEach { edge ->
            val firstRoot = find(edge.first)
            val secondRoot = find(edge.second)
            if (firstRoot == secondRoot) return@forEach
            val combinedMembers = componentMembers[firstRoot] + componentMembers[secondRoot]
            if (combinedMembers.size > MangaOcrCropPlanner.MAX_TEXT_BANDS_PER_CROP) return@forEach
            parent[secondRoot] = firstRoot
            componentMembers[firstRoot].clear()
            componentMembers[firstRoot].addAll(combinedMembers)
        }

        val components = linkedMapOf<Int, MutableList<Int>>()
        entries.indices.forEach { index ->
            components.getOrPut(find(index)) { mutableListOf() } += index
        }
        val merged = components.values.filter { it.size > 1 }.map { it.toList() }
        if (merged.isEmpty()) return Result(entries, emptyList())

        val output = components.values.map { indices ->
            if (indices.size == 1) {
                entries[indices.single()]
            } else {
                mergeEntries(indices.map(entries::get), memberBounds)
            }
        }
        return Result(output, merged)
    }

    private fun paragraphEdge(
        first: MangaOcrBubbleGroupingPolicy.Entry,
        second: MangaOcrBubbleGroupingPolicy.Entry,
        memberBounds: List<IntRect>,
    ): Int? {
        if (!first.isStandaloneFallback() || !second.isStandaloneFallback()) return null
        val firstMembers = first.bubble.memberIndices.mapNotNull(memberBounds::getOrNull)
        val secondMembers = second.bubble.memberIndices.mapNotNull(memberBounds::getOrNull)
        if (firstMembers.isEmpty() || secondMembers.isEmpty()) return null
        if (!isVertical(first, firstMembers) || !isVertical(second, secondMembers)) return null

        val firstBounds = first.bubble.contentRect
        val secondBounds = second.bubble.contentRect
        val overlap = axisOverlap(
            firstBounds.top,
            firstBounds.bottom,
            secondBounds.top,
            secondBounds.bottom,
        )
        val shorterHeight = minOf(firstBounds.height, secondBounds.height).coerceAtLeast(1)
        val overlapRatio = overlap.toFloat() / shorterHeight
        val memberWidths = (firstMembers + secondMembers).map(IntRect::width).filter { it > 0 }
        if (memberWidths.isEmpty()) return null
        val baseColumnWidth = upperMedian(memberWidths)
        val minimumOverlap = baseColumnWidth * MIN_OVERLAP_COLUMN_WIDTHS
        val gap = axisGap(
            firstBounds.left,
            firstBounds.right,
            secondBounds.left,
            secondBounds.right,
        )
        val maximumGap = baseColumnWidth * MAX_GAP_COLUMN_WIDTH_RATIO
        return gap.takeIf {
            overlapRatio >= MIN_VERTICAL_OVERLAP_RATIO &&
                overlap >= minimumOverlap &&
                gap <= maximumGap
        }
    }

    private fun mergeEntries(
        entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        memberBounds: List<IntRect>,
    ): MangaOcrBubbleGroupingPolicy.Entry {
        val memberIndices = entries
            .flatMap { entry -> entry.bubble.memberIndices }
            .distinct()
            .sorted()
        val validMembers = memberIndices.mapNotNull(memberBounds::getOrNull)
        val contentBounds = union(validMembers.ifEmpty { entries.map { it.bubble.contentRect } })
        val cropBounds = union(entries.map { it.bubble.rect } + contentBounds)
        return MangaOcrBubbleGroupingPolicy.Entry(
            bubble = Bubble(
                rect = cropBounds,
                contentRect = contentBounds,
                memberIndices = memberIndices,
            ),
            guidedSource = BubbleModelRegrouper.Source.LEGACY_FALLBACK,
            modelBubbleIndex = null,
            regionGranularity = TextRegionGranularity.FREE_TEXT,
            paragraphRegrouped = true,
        )
    }

    private fun MangaOcrBubbleGroupingPolicy.Entry.isStandaloneFallback(): Boolean =
        guidedSource == BubbleModelRegrouper.Source.LEGACY_FALLBACK &&
            modelBubbleIndex == null &&
            regionGranularity == TextRegionGranularity.FREE_TEXT

    private fun isVertical(
        entry: MangaOcrBubbleGroupingPolicy.Entry,
        members: List<IntRect>,
    ): Boolean = inferSourceLayoutOrientation(
        sourceBoxes = members,
        blockBounds = entry.bubble.contentRect,
    ) == TextOrientation.VERTICAL_RTL

    private fun union(rects: List<IntRect>): IntRect {
        require(rects.isNotEmpty())
        return IntRect(
            left = rects.minOf(IntRect::left),
            top = rects.minOf(IntRect::top),
            right = rects.maxOf(IntRect::right),
            bottom = rects.maxOf(IntRect::bottom),
        )
    }

    private fun upperMedian(values: List<Int>): Int = values.sorted()[values.size / 2]

    private fun axisGap(firstStart: Int, firstEnd: Int, secondStart: Int, secondEnd: Int): Int =
        when {
            firstEnd < secondStart -> secondStart - firstEnd
            secondEnd < firstStart -> firstStart - secondEnd
            else -> 0
        }

    private fun axisOverlap(firstStart: Int, firstEnd: Int, secondStart: Int, secondEnd: Int): Int =
        (minOf(firstEnd, secondEnd) - maxOf(firstStart, secondStart)).coerceAtLeast(0)

    private const val MAX_GAP_COLUMN_WIDTH_RATIO = 0.35f
    private const val MIN_VERTICAL_OVERLAP_RATIO = 0.70f
    private const val MIN_OVERLAP_COLUMN_WIDTHS = 2
}
