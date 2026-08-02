package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrBubbleGroupingPolicyTest {
    private data class Case(
        val name: String,
        val enabled: Boolean = true,
        val groups: List<BubbleModelRegrouper.Group>,
        val memberCount: Int = 3,
        val excludedMemberIndices: Set<Int> = emptySet(),
        val freeTextMemberIndices: Set<Int> = emptySet(),
        val expectedSource: MangaOcrBubbleGroupingPolicy.Source,
        val expectedGranularities: List<TextRegionGranularity>? = null,
    )

    @Test
    fun select_tableDriven_requiresCompleteDetectorGuidedPartition() {
        val validGroups = listOf(
            group(
                source = BubbleModelRegrouper.Source.MODEL,
                bounds = IntRect(0, 0, 40, 40),
                members = listOf(0, 1),
            ),
            group(
                source = BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                bounds = IntRect(50, 0, 70, 30),
                members = listOf(2),
            ),
        )
        val cases = listOf(
            Case(
                name = "valid model and fallback partition uses detector guidance",
                groups = validGroups,
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.DETECTOR_GUIDED,
            ),
            Case(
                name = "explicit free text fallback remains in the guided partition",
                groups = validGroups,
                freeTextMemberIndices = setOf(2),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.DETECTOR_GUIDED,
                expectedGranularities = listOf(
                    TextRegionGranularity.BUBBLE,
                    TextRegionGranularity.FREE_TEXT,
                ),
            ),
            Case(
                name = "disabled feature keeps legacy groups",
                enabled = false,
                groups = validGroups,
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.LEGACY,
            ),
            Case(
                name = "empty output keeps legacy groups",
                groups = emptyList(),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.LEGACY,
            ),
            Case(
                name = "fallback only output keeps legacy groups",
                groups = listOf(
                    group(
                        source = BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                        bounds = IntRect(0, 0, 70, 40),
                        members = listOf(0, 1, 2),
                    ),
                ),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.LEGACY,
            ),
            Case(
                name = "all-standalone-text page uses detector guidance without a bubble",
                groups = listOf(
                    group(
                        source = BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                        bounds = IntRect(0, 0, 70, 40),
                        members = listOf(0, 1, 2),
                    ),
                ),
                freeTextMemberIndices = setOf(0, 1, 2),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.DETECTOR_GUIDED,
                expectedGranularities = listOf(TextRegionGranularity.FREE_TEXT),
            ),
            Case(
                name = "explicit rejection enables a complete fallback-only partition",
                groups = listOf(
                    group(
                        source = BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                        bounds = IntRect(0, 0, 70, 40),
                        members = listOf(0, 1),
                    ),
                ),
                excludedMemberIndices = setOf(2),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.DETECTOR_GUIDED,
            ),
            Case(
                name = "all members may be intentionally rejected by text refinement",
                groups = emptyList(),
                excludedMemberIndices = setOf(0, 1, 2),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.DETECTOR_GUIDED,
            ),
            Case(
                name = "missing member keeps legacy groups",
                groups = validGroups.dropLast(1),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.LEGACY,
            ),
            Case(
                name = "duplicate member keeps legacy groups",
                groups = validGroups + group(
                    source = BubbleModelRegrouper.Source.MODEL,
                    bounds = IntRect(80, 0, 100, 30),
                    members = listOf(2),
                ),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.LEGACY,
            ),
            Case(
                name = "out of range member keeps legacy groups",
                groups = listOf(
                    group(
                        source = BubbleModelRegrouper.Source.MODEL,
                        bounds = IntRect(0, 0, 40, 40),
                        members = listOf(0, 1, 3),
                    ),
                ),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.LEGACY,
            ),
            Case(
                name = "invalid crop keeps legacy groups",
                groups = listOf(
                    group(
                        source = BubbleModelRegrouper.Source.MODEL,
                        bounds = IntRect(10, 10, 10, 40),
                        members = listOf(0, 1, 2),
                    ),
                ),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.LEGACY,
            ),
            Case(
                name = "complete partition may intentionally omit text-refined fragments",
                groups = listOf(
                    group(
                        source = BubbleModelRegrouper.Source.MODEL,
                        bounds = IntRect(0, 0, 40, 40),
                        members = listOf(0, 1),
                    ),
                ),
                excludedMemberIndices = setOf(2),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.DETECTOR_GUIDED,
            ),
            Case(
                name = "undeclared missing fragment keeps legacy groups",
                groups = listOf(
                    group(
                        source = BubbleModelRegrouper.Source.MODEL,
                        bounds = IntRect(0, 0, 40, 40),
                        members = listOf(0, 1),
                    ),
                ),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.LEGACY,
            ),
            Case(
                name = "out-of-range excluded fragment keeps legacy groups",
                groups = validGroups,
                excludedMemberIndices = setOf(3),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.LEGACY,
            ),
            Case(
                name = "out-of-range standalone fragment keeps legacy groups",
                groups = validGroups,
                freeTextMemberIndices = setOf(3),
                expectedSource = MangaOcrBubbleGroupingPolicy.Source.LEGACY,
            ),
        )
        val legacy = listOf(
            Bubble(
                rect = IntRect(0, 0, 100, 100),
                contentRect = IntRect(5, 5, 95, 95),
                memberIndices = listOf(0, 1, 2),
            ),
        )

        cases.forEach { case ->
            val result = MangaOcrBubbleGroupingPolicy.select(
                legacyBubbles = legacy,
                guidedGroups = case.groups,
                memberCount = case.memberCount,
                enabled = case.enabled,
                excludedMemberIndices = case.excludedMemberIndices,
                freeTextMemberIndices = case.freeTextMemberIndices,
            )

            assertEquals(case.name, case.expectedSource, result.source)
            if (case.expectedSource == MangaOcrBubbleGroupingPolicy.Source.LEGACY) {
                assertEquals(case.name, legacy, result.bubbles)
            } else {
                assertEquals(
                    case.name,
                    case.groups.map(BubbleModelRegrouper.Group::memberIndices),
                    result.bubbles.map(Bubble::memberIndices),
                )
                case.expectedGranularities?.let { expected ->
                    assertEquals(
                        case.name,
                        expected,
                        result.entries.map(MangaOcrBubbleGroupingPolicy.Entry::regionGranularity),
                    )
                }
            }
        }
    }

    private fun group(
        source: BubbleModelRegrouper.Source,
        bounds: IntRect,
        members: List<Int>,
    ) = BubbleModelRegrouper.Group(
        source = source,
        modelBubbleIndex = if (source == BubbleModelRegrouper.Source.MODEL) 0 else null,
        cropBounds = bounds,
        contentBounds = bounds,
        memberIndices = members,
    )
}
