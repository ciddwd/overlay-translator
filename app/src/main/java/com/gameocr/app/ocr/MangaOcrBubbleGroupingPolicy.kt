package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble

/**
 * Selects detector-guided OCR groups only when they form a complete, non-duplicated partition of
 * the eligible DBNet members. Members explicitly rejected by text-in-bubble evidence are excluded
 * from that partition; any other incomplete or malformed model output keeps the legacy groups.
 */
internal object MangaOcrBubbleGroupingPolicy {
    enum class Source {
        DETECTOR_GUIDED,
        LEGACY,
    }

    data class Selection(
        val entries: List<Entry>,
        val source: Source,
    ) {
        val bubbles: List<Bubble>
            get() = entries.map(Entry::bubble)
    }

    data class Entry(
        val bubble: Bubble,
        val guidedSource: BubbleModelRegrouper.Source?,
        val modelBubbleIndex: Int?,
    )

    fun select(
        legacyBubbles: List<Bubble>,
        guidedGroups: List<BubbleModelRegrouper.Group>,
        memberCount: Int,
        enabled: Boolean,
        excludedMemberIndices: Set<Int> = emptySet(),
    ): Selection {
        val hasDetectorGuidance =
            guidedGroups.any { it.source == BubbleModelRegrouper.Source.MODEL } ||
                excludedMemberIndices.isNotEmpty()
        if (
            !enabled ||
            !hasDetectorGuidance ||
            !isCompletePartition(guidedGroups, memberCount, excludedMemberIndices)
        ) {
            return Selection(
                entries = legacyBubbles.map { bubble ->
                    Entry(
                        bubble = bubble,
                        guidedSource = null,
                        modelBubbleIndex = null,
                    )
                },
                source = Source.LEGACY,
            )
        }
        return Selection(
            entries = guidedGroups.map { group ->
                Entry(
                    bubble = Bubble(
                        rect = group.cropBounds,
                        contentRect = group.contentBounds,
                        memberIndices = group.memberIndices,
                    ),
                    guidedSource = group.source,
                    modelBubbleIndex = group.modelBubbleIndex,
                )
            },
            source = Source.DETECTOR_GUIDED,
        )
    }

    private fun isCompletePartition(
        groups: List<BubbleModelRegrouper.Group>,
        memberCount: Int,
        excludedMemberIndices: Set<Int>,
    ): Boolean {
        if (memberCount <= 0) return false
        if (excludedMemberIndices.any { it !in 0 until memberCount }) return false
        if (groups.isEmpty()) return excludedMemberIndices.size == memberCount
        if (groups.any { group ->
                group.memberIndices.isEmpty() ||
                    group.cropBounds.width <= 0 ||
                    group.cropBounds.height <= 0 ||
                    group.contentBounds.width <= 0 ||
                    group.contentBounds.height <= 0
            }
        ) {
            return false
        }
        return groups
            .flatMap(BubbleModelRegrouper.Group::memberIndices)
            .sorted() == (0 until memberCount).filterNot(excludedMemberIndices::contains)
    }
}
