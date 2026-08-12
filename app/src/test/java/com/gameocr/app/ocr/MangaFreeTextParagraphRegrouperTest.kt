package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaFreeTextParagraphRegrouperTest {

    @Test
    fun regroup_isTableDriven_andNeverDropsMembers() {
        data class Case(
            val name: String,
            val memberBounds: List<IntRect>,
            val entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
            val expectedMembers: List<List<Int>>,
            val expectedMergedOriginalEntries: List<List<Int>>,
        )

        val regressionMembers = listOf(
            IntRect(179, 480, 247, 788),
            IntRect(104, 480, 180, 970),
            IntRect(308, 495, 329, 585),
            IntRect(308, 635, 329, 733),
            IntRect(249, 480, 318, 827),
        )
        val cases = listOf(
            Case(
                name = "logcat sentence columns become one model crop",
                memberBounds = regressionMembers,
                entries = listOf(
                    fallbackEntry(regressionMembers, listOf(0, 1)),
                    fallbackEntry(regressionMembers, listOf(2, 3, 4)),
                ),
                expectedMembers = listOf(listOf(0, 1, 2, 3, 4)),
                expectedMergedOriginalEntries = listOf(listOf(0, 1)),
            ),
            Case(
                name = "large horizontal gap keeps free text independent",
                memberBounds = regressionMembers.mapIndexed { index, rect ->
                    if (index < 2) rect else rect.shiftX(100)
                },
                entries = listOf(
                    fallbackEntry(regressionMembers.mapIndexed { index, rect ->
                        if (index < 2) rect else rect.shiftX(100)
                    }, listOf(0, 1)),
                    fallbackEntry(regressionMembers.mapIndexed { index, rect ->
                        if (index < 2) rect else rect.shiftX(100)
                    }, listOf(2, 3, 4)),
                ),
                expectedMembers = listOf(listOf(0, 1), listOf(2, 3, 4)),
                expectedMergedOriginalEntries = emptyList(),
            ),
            Case(
                name = "weak vertical overlap keeps separate captions independent",
                memberBounds = listOf(
                    IntRect(100, 0, 160, 300),
                    IntRect(40, 260, 95, 560),
                ),
                entries = listOf(
                    fallbackEntry(listOf(IntRect(100, 0, 160, 300), IntRect(40, 260, 95, 560)), listOf(0)),
                    fallbackEntry(listOf(IntRect(100, 0, 160, 300), IntRect(40, 260, 95, 560)), listOf(1)),
                ),
                expectedMembers = listOf(listOf(0), listOf(1)),
                expectedMergedOriginalEntries = emptyList(),
            ),
            Case(
                name = "model confirmed bubble is never crossed",
                memberBounds = listOf(
                    IntRect(100, 0, 160, 400),
                    IntRect(40, 0, 95, 400),
                ),
                entries = listOf(
                    modelEntry(listOf(IntRect(100, 0, 160, 400), IntRect(40, 0, 95, 400)), listOf(0)),
                    fallbackEntry(listOf(IntRect(100, 0, 160, 400), IntRect(40, 0, 95, 400)), listOf(1)),
                ),
                expectedMembers = listOf(listOf(0), listOf(1)),
                expectedMergedOriginalEntries = emptyList(),
            ),
            Case(
                name = "horizontal free text is outside the vertical paragraph policy",
                memberBounds = listOf(
                    IntRect(0, 0, 240, 40),
                    IntRect(0, 45, 240, 85),
                ),
                entries = listOf(
                    fallbackEntry(listOf(IntRect(0, 0, 240, 40), IntRect(0, 45, 240, 85)), listOf(0)),
                    fallbackEntry(listOf(IntRect(0, 0, 240, 40), IntRect(0, 45, 240, 85)), listOf(1)),
                ),
                expectedMembers = listOf(listOf(0), listOf(1)),
                expectedMergedOriginalEntries = emptyList(),
            ),
            Case(
                name = "model crop band budget prevents a dense transitive merge",
                memberBounds = List(7) { index ->
                    val left = 420 - index * 55
                    IntRect(left, 0, left + 50, 400)
                },
                entries = listOf(
                    fallbackEntry(
                        List(7) { index ->
                            val left = 420 - index * 55
                            IntRect(left, 0, left + 50, 400)
                        },
                        listOf(0, 1, 2, 3),
                    ),
                    fallbackEntry(
                        List(7) { index ->
                            val left = 420 - index * 55
                            IntRect(left, 0, left + 50, 400)
                        },
                        listOf(4, 5, 6),
                    ),
                ),
                expectedMembers = listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6)),
                expectedMergedOriginalEntries = emptyList(),
            ),
        )

        cases.forEach { case ->
            val result = MangaFreeTextParagraphRegrouper.regroup(case.entries, case.memberBounds)
            assertEquals(case.name, case.expectedMembers, result.entries.map { it.bubble.memberIndices })
            assertEquals(case.name, case.expectedMergedOriginalEntries, result.mergedOriginalEntryIndices)
            assertEquals(
                "${case.name}: OCR members must never be deleted",
                case.entries.flatMap { it.bubble.memberIndices }.distinct().sorted(),
                result.entries.flatMap { it.bubble.memberIndices }.distinct().sorted(),
            )
            result.entries.forEach { entry ->
                assertEquals(
                    "${case.name}: regroup marker",
                    result.mergedOriginalEntryIndices.isNotEmpty(),
                    entry.paragraphRegrouped,
                )
            }
        }
    }

    private fun fallbackEntry(
        bounds: List<IntRect>,
        memberIndices: List<Int>,
    ): MangaOcrBubbleGroupingPolicy.Entry = entry(
        bounds = bounds,
        memberIndices = memberIndices,
        source = BubbleModelRegrouper.Source.LEGACY_FALLBACK,
        granularity = TextRegionGranularity.FREE_TEXT,
        modelBubbleIndex = null,
    )

    private fun modelEntry(
        bounds: List<IntRect>,
        memberIndices: List<Int>,
    ): MangaOcrBubbleGroupingPolicy.Entry = entry(
        bounds = bounds,
        memberIndices = memberIndices,
        source = BubbleModelRegrouper.Source.MODEL,
        granularity = TextRegionGranularity.BUBBLE,
        modelBubbleIndex = 3,
    )

    private fun entry(
        bounds: List<IntRect>,
        memberIndices: List<Int>,
        source: BubbleModelRegrouper.Source,
        granularity: TextRegionGranularity,
        modelBubbleIndex: Int?,
    ): MangaOcrBubbleGroupingPolicy.Entry {
        val members = memberIndices.map(bounds::get)
        val union = IntRect(
            members.minOf(IntRect::left),
            members.minOf(IntRect::top),
            members.maxOf(IntRect::right),
            members.maxOf(IntRect::bottom),
        )
        return MangaOcrBubbleGroupingPolicy.Entry(
            bubble = Bubble(union, union, memberIndices),
            guidedSource = source,
            modelBubbleIndex = modelBubbleIndex,
            regionGranularity = granularity,
        )
    }

    private fun IntRect.shiftX(delta: Int): IntRect =
        IntRect(left + delta, top, right + delta, bottom)
}
