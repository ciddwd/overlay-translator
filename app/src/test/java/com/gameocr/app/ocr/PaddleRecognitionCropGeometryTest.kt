package com.gameocr.app.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleRecognitionCropGeometryTest {

    private fun quad(vararg xy: Float) = DBPostprocessor.Quad(
        paddlePointF(xy[0], xy[1]),
        paddlePointF(xy[2], xy[3]),
        paddlePointF(xy[4], xy[5]),
        paddlePointF(xy[6], xy[7]),
    )

    private fun DBPostprocessor.Quad.coords() =
        floatArrayOf(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)

    @Test
    fun cropSize_truncatesTheLongerOppositeEdge_likeGetRotateCropImage() {
        data class Case(val name: String, val points: FloatArray, val expected: IntArray)
        listOf(
            Case("axis aligned", floatArrayOf(0f, 0f, 10f, 0f, 10f, 5f, 0f, 5f), intArrayOf(10, 5)),
            Case("fractional edges truncate", floatArrayOf(0f, 0f, 10.9f, 0f, 10.9f, 5.9f, 0f, 5.9f), intArrayOf(10, 5)),
            Case("trapezoid uses longer edge", floatArrayOf(0f, 0f, 8f, 0f, 12f, 6f, 0f, 6f), intArrayOf(12, 7)),
        ).forEach { case ->
            assertArrayEquals(case.name, case.expected, PaddleRotateCrop.cropSize(case.points))
        }
    }

    @Test
    fun warp_integerAlignedRectangle_copiesSourcePixelsExactly() {
        val width = 20
        val height = 10
        val source = IntArray(width * height) { i -> (0xFF shl 24) or ((i * 7) and 0xFF shl 16) or ((i * 13) and 0xFF shl 8) or (i and 0xFF) }
        val points = floatArrayOf(2f, 3f, 12f, 3f, 12f, 8f, 2f, 8f)
        val out = PaddleRotateCrop.warp(source, width, height, 0, 0, points, 10, 5)
        for (y in 0 until 5) for (x in 0 until 10) {
            assertEquals("($x,$y)", source[(y + 3) * width + x + 2], out[y * 10 + x])
        }
    }

    @Test
    fun warp_regionOrigin_isEquivalentToFullImageSampling() {
        val width = 30
        val height = 20
        val full = IntArray(width * height) { i -> (0xFF shl 24) or ((i * 31) and 0xFFFFFF) }
        val points = floatArrayOf(6.5f, 4.25f, 21.75f, 6f, 20.5f, 12.5f, 5.25f, 10.75f)
        val size = PaddleRotateCrop.cropSize(points)
        val expected = PaddleRotateCrop.warp(full, width, height, 0, 0, points, size[0], size[1])

        val bounds = PaddleRotateCrop.sourceBounds(points, width, height)
        val regionW = bounds[2] - bounds[0]
        val regionH = bounds[3] - bounds[1]
        val region = IntArray(regionW * regionH) { i -> full[(bounds[1] + i / regionW) * width + bounds[0] + i % regionW] }
        val actual = PaddleRotateCrop.warp(region, regionW, regionH, bounds[0], bounds[1], points, size[0], size[1])

        assertArrayEquals(expected, actual)
    }

    /** BORDER_REPLICATE: a crop touching the image edge never samples transparent/black pixels. */
    @Test
    fun warp_atImageEdges_replicatesBorderInsteadOfBlack() {
        val width = 16
        val height = 12
        val white = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        listOf(
            "full frame" to floatArrayOf(0f, 0f, 16f, 0f, 16f, 12f, 0f, 12f),
            "rotated, touching corners" to floatArrayOf(0f, 2f, 15f, 0f, 16f, 10f, 1f, 12f),
            "right edge strip" to floatArrayOf(10f, 0f, 16f, 0f, 16f, 12f, 10f, 12f),
        ).forEach { (name, points) ->
            val size = PaddleRotateCrop.cropSize(points)
            val out = PaddleRotateCrop.warp(white, width, height, 0, 0, points, size[0], size[1])
            assertTrue(name, out.all { it == 0xFFFFFFFF.toInt() })
        }
    }

    @Test
    fun fitRecognitionCropQuad_clampsToImageAndRefitsLikeCropByPolys() {
        val fitted = requireNotNull(
            DBPostprocessor.fitRecognitionCropQuad(quad(-10f, -5f, 50f, -5f, 50f, 20f, -10f, 20f), 40, 30)
        )
        assertArrayEquals(floatArrayOf(0f, 0f, 40f, 0f, 40f, 20f, 0f, 20f), fitted.coords(), 0.001f)

        // Corners are rounded to integers before the refit, as DBPostProcess does.
        val rounded = requireNotNull(
            DBPostprocessor.fitRecognitionCropQuad(quad(1.4f, 2.6f, 30.5f, 2.6f, 30.5f, 9.4f, 1.4f, 9.4f), 40, 30)
        )
        assertArrayEquals(floatArrayOf(1f, 3f, 31f, 3f, 31f, 9f, 1f, 9f), rounded.coords(), 0.001f)

        rounded.coords().toList().chunked(2).forEach { (x, y) ->
            assertTrue(x in 0f..40f && y in 0f..30f)
        }
    }

    @Test
    fun fitRecognitionCropQuad_boxEntirelyOutsideImage_isDropped() {
        assertNull(DBPostprocessor.fitRecognitionCropQuad(quad(50f, 5f, 70f, 5f, 70f, 15f, 50f, 15f), 40, 30))
    }

    /** pyclipper offset distance = area * ratio / perimeter, with no upper bound. */
    @Test
    fun unclip_largeTextIsNotCapped() {
        data class Case(val name: String, val w: Float, val h: Float, val ratio: Float)
        listOf(
            Case("small line", 40f, 8f, 1.5f),
            Case("large title", 1000f, 100f, 1.5f),
            Case("v6 ratio", 600f, 60f, 1.4f),
        ).forEach { case ->
            val expanded = DBPostprocessor.unclip(quad(0f, 0f, case.w, 0f, case.w, case.h, 0f, case.h), case.ratio)
            val distance = case.w * case.h * case.ratio / (2 * (case.w + case.h))
            assertArrayEquals(
                case.name,
                floatArrayOf(
                    -distance, -distance,
                    case.w + distance, -distance,
                    case.w + distance, case.h + distance,
                    -distance, case.h + distance,
                ),
                expanded.coords(),
                0.01f,
            )
        }
    }
}
