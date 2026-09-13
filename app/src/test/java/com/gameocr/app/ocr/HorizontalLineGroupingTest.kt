package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalLineGroupingTest {
    private fun r(l: Int, t: Int, right: Int, b: Int) = MergeDebugRect(l, t, right, b)
    private fun group(rects: List<MergeDebugRect>, bubbles: List<Int?> = List(rects.size) { null }) =
        groupHorizontalLineRects(rects, bubbles, .7f, 1.8f, 2.5f)

    @Test fun row_cases_keep_reading_order_and_boundaries() {
        data class Case(val name: String, val boxes: List<MergeDebugRect>, val groups: List<List<Int>>)
        listOf(
            Case("large and small same baseline", listOf(r(0, 0, 100, 100), r(95, 50, 180, 100)), listOf(listOf(0, 1))),
            Case("right box has earlier top", listOf(r(100, 0, 180, 50), r(0, 4, 104, 54)), listOf(listOf(1, 0))),
            Case("distant columns", listOf(r(0, 0, 100, 50), r(300, 0, 400, 50)), listOf(listOf(0), listOf(1))),
            Case("separate rows with padding overlap", listOf(r(0, 0, 100, 100), r(80, 90, 180, 190)), listOf(listOf(0), listOf(1))),
            Case("nested box", listOf(r(0, 0, 100, 100), r(25, 25, 75, 75)), listOf(listOf(0), listOf(1))),
            Case("lower middle fragment arrives in spatial order", listOf(r(0, 0, 40, 50), r(180, 0, 220, 50), r(80, 5, 140, 55)), listOf(listOf(0, 2, 1))),
            Case("sloping chain cannot join two rows", listOf(r(0, 0, 90, 50), r(100, 20, 190, 70), r(200, 40, 290, 90)), listOf(listOf(0, 1), listOf(2))),
            Case("empty", emptyList(), emptyList()),
        ).forEach { case -> assertEquals(case.name, case.groups, group(case.boxes)) }
        assertEquals(listOf(listOf(0), listOf(1)), group(listOf(r(0, 0, 90, 50), r(100, 0, 190, 50)), listOf(1, 2)))
        assertEquals(listOf(listOf(0), listOf(1)), group(listOf(r(0, 0, 90, 50), r(100, 0, 190, 50)), listOf(null, 1)))
    }

    @Test fun recorded_geometry_forms_three_lines_and_one_paragraph_at_every_scale() {
        val original = listOf(r(773, 1863, 1213, 2050), r(628, 2038, 1158, 2371), r(1128, 2170, 1397, 2332), r(759, 2330, 1243, 2479))
        for (scale in listOf(.25f, 1f, 4f)) for (order in listOf(listOf(0, 1, 2, 3), listOf(2, 3, 1, 0), listOf(3, 2, 0, 1))) {
            val boxes = order.map { i -> original[i].let {
                r((it.left * scale).toInt() + 100, (it.top * scale).toInt() + 200,
                    (it.right * scale).toInt() + 100, (it.bottom * scale).toInt() + 200)
            } }
            val rows = group(boxes)
            assertEquals("scale=$scale order=$order", listOf(listOf(0), listOf(1, 2), listOf(3)), rows.map { row -> row.map { order[it] } })
            val rowRects = rows.map { row ->
                r(row.minOf { boxes[it].left }, row.minOf { boxes[it].top }, row.maxOf { boxes[it].right }, row.maxOf { boxes[it].bottom })
            }
            assertEquals(listOf(listOf(0, 1, 2)), groupHorizontalParagraphRects(rowRects, List(rows.size) { null }, 1.3f, .15f))
        }
    }

    @Test fun merge_strength_still_controls_mixed_font_size() {
        val boxes = listOf(r(0, 0, 100, 100), r(95, 50, 180, 100))
        for ((tolerance, gap, height) in listOf(Triple(.35f, .8f, 1.4f), Triple(.5f, 1.2f, 1.6f), Triple(.7f, 1.8f, 2.5f))) {
            assertEquals(if (height >= 2f) 1 else 2, groupHorizontalLineRects(boxes, listOf(null, null), tolerance, gap, height).size)
        }
    }
}
