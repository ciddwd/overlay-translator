package com.gameocr.app.overlay

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPresentationGeometryTest {
    @Test
    fun layout_tableDriven_keepsEveryRotatedCornerAtCaptureBounds() {
        for (bounds in listOf(
            OverlayIntRect(0, 0, 31, 79), OverlayIntRect(1, 3, 202, 40),
            OverlayIntRect(1167, 423, 1297, 2394), OverlayIntRect(5, 6, 135, 1977),
            OverlayIntRect(400, 2, 2001, 93),
        )) for (angle in listOf(0, 90, 180, 270, -90)) {
            val layout = OverlayPresentationGeometry.layout(bounds, angle)
            val corners = listOf(0 to 0, layout.width to 0, 0 to layout.height, layout.width to layout.height)
                .map { (x, y) -> rotate(x.toDouble(), y.toDouble(), angle) }
            assertEquals("$bounds $angle left", bounds.left.toDouble(), layout.left + corners.minOf { it.first }, 0.00001)
            assertEquals("$bounds $angle top", bounds.top.toDouble(), layout.top + corners.minOf { it.second }, 0.00001)
            assertEquals("$bounds $angle right", bounds.right.toDouble(), layout.left + corners.maxOf { it.first }, 0.00001)
            assertEquals("$bounds $angle bottom", bounds.bottom.toDouble(), layout.top + corners.maxOf { it.second }, 0.00001)
        }
    }

    @Test
    fun eraseRectangles_tableDriven_matchOriginalAfterViewRotation() {
        for ((w, h) in listOf(130 to 1971, 1971 to 130, 51 to 81)) {
            val bounds = OverlayIntRect(7, 383, 7 + w, 383 + h)
            val sources = listOf(OverlayIntRect(10, 392, 7 + w - 3, 383 + h - 9))
            for (rects in listOf(adaptiveEraseRects(bounds, sources), adaptiveFallbackEraseRects(bounds))) {
                for (angle in listOf(0, 90, 180, 270)) {
                    val layout = OverlayPresentationGeometry.layout(bounds, angle)
                    val local = OverlayPresentationGeometry.captureLocalRectsToView(rects, w, h, angle)
                    local.zip(rects).forEach { (view, original) ->
                        val corners = listOf(view.left to view.top, view.right to view.top,
                            view.left to view.bottom, view.right to view.bottom)
                            .map { (x, y) -> rotate(x.toDouble(), y.toDouble(), angle) }
                        assertEquals((bounds.left + original.left).toDouble(), layout.left + corners.minOf { it.first }, 0.00001)
                        assertEquals((bounds.top + original.top).toDouble(), layout.top + corners.minOf { it.second }, 0.00001)
                        assertEquals((bounds.left + original.right).toDouble(), layout.left + corners.maxOf { it.first }, 0.00001)
                        assertEquals((bounds.top + original.bottom).toDouble(), layout.top + corners.maxOf { it.second }, 0.00001)
                    }
                }
            }
        }
    }

    private fun rotate(x: Double, y: Double, degrees: Int): Pair<Double, Double> {
        val angle = Math.toRadians(degrees.toDouble())
        return (x * cos(angle) - y * sin(angle)) to (x * sin(angle) + y * cos(angle))
    }
}
