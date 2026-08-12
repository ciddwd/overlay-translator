package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaSeedSearchOrderTest {
    @Test
    fun nearest_tableDriven_preservesDistanceAndStableTiePriority() {
        data class Case(
            val name: String,
            val roi: IntRect,
            val candidatePoints: List<Pair<Int, Int>>,
            val expectedLocalIndex: Int?,
        )

        val imageWidth = 10
        val points = listOf(4 to 4, 3 to 4, 5 to 4, 4 to 3, 8 to 8)
        val order = MangaSeedSearchOrder.prepare(
            imageWidth = imageWidth,
            globalIndices = points.map { (x, y) -> y * imageWidth + x }.toIntArray(),
            distanceSquared = floatArrayOf(0f, 1f, 1f, 1f, 32f),
        )
        val cases = listOf(
            Case("closest point wins", IntRect(0, 0, 10, 10), listOf(4 to 4, 8 to 8), 44),
            Case("equal distance keeps row-major preparation order", IntRect(0, 0, 10, 10), listOf(3 to 4, 5 to 4), 43),
            Case("blocked closest uses next distance", IntRect(0, 0, 10, 10), listOf(5 to 4), 45),
            Case("points outside roi are ignored", IntRect(5, 4, 10, 10), listOf(4 to 4, 5 to 4, 8 to 8), 0),
            Case("no eligible candidate", IntRect(0, 0, 5, 5), listOf(0 to 0), null),
        )

        cases.forEach { case ->
            val candidate = BooleanArray(case.roi.width * case.roi.height)
            case.candidatePoints.forEach { (x, y) ->
                if (x in case.roi.left until case.roi.right && y in case.roi.top until case.roi.bottom) {
                    candidate[(y - case.roi.top) * case.roi.width + (x - case.roi.left)] = true
                }
            }
            assertEquals(case.name, case.expectedLocalIndex, order.nearest(candidate, case.roi))
        }
    }
}
