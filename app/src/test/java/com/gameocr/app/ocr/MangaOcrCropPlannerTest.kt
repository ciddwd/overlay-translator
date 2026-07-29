package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrCropPlannerTest {

    @Test
    fun crop_planning_is_table_driven() {
        data class Case(
            val name: String,
            val rects: List<IntRect>,
            val bubbleMemberIndices: List<Int> = rects.indices.toList(),
            val splitByTextBand: Boolean = false,
            val expectedCropMembers: List<List<Int>>,
        )

        val observedDenseDocument = listOf(
            IntRect(89, 987, 1300, 1063),
            IntRect(89, 1051, 1303, 1124),
            IntRect(90, 1116, 513, 1189),
            IntRect(89, 1179, 1303, 1254),
            IntRect(89, 1246, 838, 1317),
            IntRect(94, 1314, 260, 1374),
            IntRect(226, 1309, 1303, 1382),
            IntRect(90, 1374, 161, 1445),
            IntRect(85, 1419, 1201, 1524),
            IntRect(96, 1502, 211, 1569),
            IntRect(186, 1506, 1301, 1571),
            IntRect(92, 1565, 164, 1636),
            IntRect(97, 1633, 1290, 1699),
            IntRect(97, 1697, 996, 1762),
            IntRect(97, 1756, 1297, 1832),
            IntRect(90, 1822, 591, 1894),
        )
        val cases = listOf(
            Case(
                name = "normal-three-line-horizontal-bubble-is-unchanged",
                rects = horizontalRows(count = 3),
                expectedCropMembers = listOf(listOf(0, 1, 2)),
            ),
            Case(
                name = "six-horizontal-bands-stay-in-one-crop",
                rects = horizontalRows(count = 6),
                expectedCropMembers = listOf(listOf(0, 1, 2, 3, 4, 5)),
            ),
            Case(
                name = "seventh-horizontal-band-starts-a-second-crop",
                rects = horizontalRows(count = 7),
                expectedCropMembers = listOf(
                    listOf(0, 1, 2, 3, 4, 5),
                    listOf(6),
                ),
            ),
            Case(
                name = "same-row-fragments-count-as-one-band",
                rects = horizontalRows(count = 5) + listOf(
                    IntRect(0, 120, 80, 140),
                    IntRect(90, 121, 180, 141),
                ),
                expectedCropMembers = listOf(listOf(0, 1, 2, 3, 4, 5, 6)),
            ),
            Case(
                name = "free-text-title-splits-each-horizontal-band",
                rects = horizontalRows(count = 2),
                splitByTextBand = true,
                expectedCropMembers = listOf(listOf(0), listOf(1)),
            ),
            Case(
                name = "free-text-same-row-fragments-stay-in-one-crop",
                rects = listOf(
                    IntRect(0, 0, 80, 20),
                    IntRect(90, 1, 180, 21),
                ),
                splitByTextBand = true,
                expectedCropMembers = listOf(listOf(0, 1)),
            ),
            Case(
                name = "free-text-vertical-columns-split-right-to-left",
                rects = verticalColumns(count = 2),
                splitByTextBand = true,
                expectedCropMembers = listOf(listOf(1), listOf(0)),
            ),
            Case(
                name = "vertical-columns-split-in-right-to-left-order",
                rects = verticalColumns(count = 7),
                expectedCropMembers = listOf(
                    listOf(6, 5, 4, 3, 2, 1),
                    listOf(0),
                ),
            ),
            Case(
                name = "device-regression-sixteen-box-document-becomes-three-crops",
                rects = observedDenseDocument,
                expectedCropMembers = listOf(
                    listOf(0, 1, 2, 3, 4, 5, 6),
                    listOf(7, 8, 9, 10, 11, 12, 13),
                    listOf(14, 15),
                ),
            ),
            Case(
                name = "invalid-member-index-is-ignored-without-changing-valid-bubble",
                rects = horizontalRows(count = 2),
                bubbleMemberIndices = listOf(0, 99, 1),
                expectedCropMembers = listOf(listOf(0, 99, 1)),
            ),
        )

        cases.forEach { case ->
            val bubble = bubble(case.rects, case.bubbleMemberIndices)
            val plans = MangaOcrCropPlanner.plan(
                bubbles = listOf(bubble),
                rects = case.rects,
                imageWidth = 1600,
                imageHeight = 2400,
                padding = 0,
                splitByTextBandBubbleIndices =
                    if (case.splitByTextBand) setOf(0) else emptySet(),
            )
            assertEquals(
                case.name,
                case.expectedCropMembers,
                plans.map { plan -> plan.bubble.memberIndices },
            )
            plans.forEachIndexed { index, plan ->
                assertEquals(case.name, 0, plan.sourceBubbleIndex)
                assertEquals(case.name, index, plan.cropIndex)
                assertEquals(case.name, plans.size, plan.cropCount)
            }
        }
    }

    @Test
    fun split_crop_bounds_and_padding_are_recomputed_and_clamped() {
        val rects = horizontalRows(count = 7, left = 3, right = 190)
        val plans = MangaOcrCropPlanner.plan(
            bubbles = listOf(bubble(rects)),
            rects = rects,
            imageWidth = 200,
            imageHeight = 180,
            padding = 12,
        )

        assertEquals(2, plans.size)
        assertEquals(IntRect(3, 0, 190, 140), plans[0].bubble.contentRect)
        assertEquals(IntRect(0, 0, 200, 152), plans[0].bubble.rect)
        assertEquals(IntRect(3, 144, 190, 164), plans[1].bubble.contentRect)
        assertEquals(IntRect(0, 132, 200, 176), plans[1].bubble.rect)
    }

    private fun horizontalRows(
        count: Int,
        left: Int = 0,
        right: Int = 180,
    ): List<IntRect> = List(count) { index ->
        val top = index * 24
        IntRect(left, top, right, top + 20)
    }

    private fun verticalColumns(count: Int): List<IntRect> = List(count) { index ->
        val left = index * 24
        IntRect(left, 0, left + 20, 180)
    }

    private fun bubble(
        rects: List<IntRect>,
        memberIndices: List<Int> = rects.indices.toList(),
    ): Bubble {
        val validMembers = memberIndices.mapNotNull(rects::getOrNull)
        val contentRect = IntRect(
            validMembers.minOf { it.left },
            validMembers.minOf { it.top },
            validMembers.maxOf { it.right },
            validMembers.maxOf { it.bottom },
        )
        return Bubble(
            rect = contentRect,
            contentRect = contentRect,
            memberIndices = memberIndices,
        )
    }
}
