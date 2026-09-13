package com.gameocr.app.capture

import android.graphics.Rect
import com.gameocr.app.data.CaptureContentOrientation
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import com.gameocr.app.ocr.ShapeAwareBubblePatch
import com.gameocr.app.ocr.TextBlock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureContentOrientationPolicyTest {

    @Test
    fun canUseOcrSpacePixelMask_acceptsExactlyInvertibleQuarterTurns_tableDriven() {
        listOf(
            0 to true,
            360 to true,
            90 to true,
            -90 to true,
            180 to true,
            270 to true,
            45 to false,
        ).forEach { (angle, expected) ->
            assertEquals(
                "angle=$angle",
                expected,
                CaptureContentOrientationPolicy.canUseOcrSpacePixelMask(angle),
            )
        }
    }

    @Test
    fun resolveRotationDegrees_respectsAutoAspectAndModelDirection_tableDriven() {
        data class Case(
            val name: String,
            val requested: CaptureContentOrientation,
            val width: Int,
            val height: Int,
            val modelAngle: Int,
            val expected: Int,
        )

        val cases = listOf(
            Case("auto never rotates genuine vertical content", CaptureContentOrientation.AUTO, 1080, 2400, 90, 0),
            Case("landscape already matches", CaptureContentOrientation.LANDSCAPE, 2400, 1080, 270, 0),
            Case("portrait already matches", CaptureContentOrientation.PORTRAIT, 1080, 2400, 90, 0),
            Case("portrait capture becomes landscape counterclockwise", CaptureContentOrientation.LANDSCAPE, 1080, 2400, 90, 90),
            Case("portrait capture becomes landscape clockwise", CaptureContentOrientation.LANDSCAPE, 1080, 2400, 270, 270),
            Case("landscape capture becomes portrait", CaptureContentOrientation.PORTRAIT, 2400, 1080, 270, 270),
            Case("negative model angle is normalized", CaptureContentOrientation.LANDSCAPE, 1080, 2400, -90, 270),
            Case("model cannot choose quarter turn", CaptureContentOrientation.LANDSCAPE, 1080, 2400, 180, 0),
            Case("invalid size is ignored", CaptureContentOrientation.LANDSCAPE, 0, 2400, 90, 0),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                CaptureContentOrientationPolicy.resolveRotationDegrees(
                    case.requested,
                    case.width,
                    case.height,
                    case.modelAngle,
                ),
            )
        }
    }

    @Test
    fun mapBlocksToOriginal_restoresQuarterTurnCoordinates_tableDriven() {
        data class Case(
            val name: String,
            val angle: Int,
            val rotatedRect: Rect,
            val expected: Rect,
        )

        val cases = listOf(
            Case("90 degree correction", 90, Rect(20, 70, 50, 90), Rect(10, 20, 30, 50)),
            Case("270 degree correction", 270, Rect(30, 10, 60, 30), Rect(10, 20, 30, 50)),
            Case("180 degree mapping remains supported", 180, Rect(70, 30, 90, 60), Rect(10, 20, 30, 50)),
            Case("out of bounds is clamped", 90, Rect(-10, -10, 100, 120), Rect(0, 0, 100, 80)),
        )

        cases.forEach { case ->
            val block = TextBlock(
                text = "text",
                boundingBox = Rect(case.rotatedRect),
                sourceBoxes = listOf(Rect(case.rotatedRect)),
            )
            val mapped = CaptureContentOrientationPolicy.mapBlocksToOriginal(
                listOf(block),
                originalWidth = 100,
                originalHeight = 80,
                counterClockwiseDegrees = case.angle,
            ).single()

            assertRectEquals(case.name, case.expected, mapped.boundingBox)
            assertRectEquals("${case.name} source box", case.expected, mapped.sourceBoxes.single())
            assertEquals(case.name, "text", mapped.text)
            assertEquals(case.name, ((case.angle % 360) + 360) % 360, mapped.presentationRotationDegrees)
        }
    }

    @Test
    fun mapBlocksToOcr_roundTripsDisplayCoordinates_tableDriven() {
        listOf(0, 90, 180, 270).forEach { angle ->
            val original = TextBlock(
                text = "text",
                boundingBox = Rect(10, 20, 30, 50),
                sourceBoxes = listOf(Rect(12, 22, 28, 48)),
                presentationRotationDegrees = angle,
            )

            val ocrBlock = CaptureContentOrientationPolicy.mapBlocksToOcr(
                blocks = listOf(original),
                originalWidth = 100,
                originalHeight = 80,
                counterClockwiseDegrees = angle,
            ).single()
            val restored = CaptureContentOrientationPolicy.mapBlocksToOriginal(
                blocks = listOf(ocrBlock),
                originalWidth = 100,
                originalHeight = 80,
                counterClockwiseDegrees = angle,
            ).single()

            assertRectEquals("$angle bounding box", original.boundingBox, restored.boundingBox)
            assertRectEquals("$angle source box", original.sourceBoxes.single(), restored.sourceBoxes.single())
            assertEquals("$angle OCR presentation rotation", 0, ocrBlock.presentationRotationDegrees)
            assertEquals("$angle display presentation rotation", angle, restored.presentationRotationDegrees)
        }
    }

    @Test
    fun mapPatchesToOriginal_rotatesBoundsAndPixelsWithoutChangingCoverage_tableDriven() {
        data class Case(
            val name: String,
            val angle: Int,
            val inputBounds: IntRect,
            val expectedBounds: IntRect,
            val expectedPixels: IntArray,
        )

        val inputPixels = intArrayOf(1, 2, 3, 4, 5, 6)
        val cases = listOf(
            Case(
                name = "quarter turn counterclockwise correction",
                angle = 90,
                inputBounds = IntRect(20, 70, 23, 72),
                expectedBounds = IntRect(28, 20, 30, 23),
                expectedPixels = intArrayOf(4, 1, 5, 2, 6, 3),
            ),
            Case(
                name = "quarter turn clockwise correction",
                angle = 270,
                inputBounds = IntRect(30, 10, 33, 12),
                expectedBounds = IntRect(10, 47, 12, 50),
                expectedPixels = intArrayOf(3, 6, 2, 5, 1, 4),
            ),
        )

        cases.forEach { case ->
            val patch = ShapeAwareBubblePatch(
                modelBubbleIndex = null,
                bounds = case.inputBounds,
                pixels = inputPixels,
                coordinateScale = 2f,
                blockIndices = listOf(0),
                role = ShapeAwareBubblePatch.Role.TEXT_BACKGROUND,
            )

            val restored = CaptureContentOrientationPolicy.mapPatchesToOriginal(
                patches = listOf(patch),
                originalImageWidth = 100,
                originalImageHeight = 80,
                counterClockwiseDegrees = case.angle,
            ).single()

            assertEquals("${case.name} bounds", case.expectedBounds, restored.bounds)
            assertArrayEquals("${case.name} pixels", case.expectedPixels, restored.pixels)
            assertEquals("${case.name} scale", 2f, restored.coordinateScale)
            assertEquals("${case.name} role", patch.role, restored.role)
            assertEquals("${case.name} blocks", patch.blockIndices, restored.blockIndices)
        }
    }

    @Test
    fun mapPatchesToOriginal_noAdjustmentKeepsExistingPatchList() {
        val patch = ShapeAwareBubblePatch(
            modelBubbleIndex = null,
            bounds = IntRect(1, 2, 3, 4),
            pixels = intArrayOf(1, 2, 3, 4),
            coordinateScale = 1f,
            blockIndices = listOf(0),
            role = ShapeAwareBubblePatch.Role.TEXT_BACKGROUND,
        )
        val patches = listOf(patch)

        assertEquals(
            patches,
            CaptureContentOrientationPolicy.mapPatchesToOriginal(patches, 10, 10, 0),
        )
    }

    @Test
    fun mapPatchesToOriginal_clipsPixelsTogetherWithBounds_tableDriven() {
        // Unique colors, including transparent pixels, expose any transpose/shift/cropping error.
        for ((w, h) in listOf(7 to 4, 4 to 7, 5 to 5)) {
            for (angle in listOf(0, 90, 180, 270)) {
                val ow = if (angle % 180 == 0) w else h
                val oh = if (angle % 180 == 0) h else w
                for (bounds in listOf(
                    IntRect(0, 0, ow, oh), IntRect(1, 1, ow, oh),
                    IntRect(-1, -2, ow + 1, oh + 2), IntRect(ow - 1, 0, ow + 2, 2),
                    IntRect(ow + 1, oh + 1, ow + 3, oh + 3),
                )) {
                    val pixels = IntArray(bounds.width * bounds.height) { i ->
                        if (i % 3 == 0) 0 else 0xff000000.toInt() or (i + 1)
                    }
                    val patch = ShapeAwareBubblePatch(null, bounds, pixels, 2f, listOf(3),
                        ShapeAwareBubblePatch.Role.TEXT_BACKGROUND)
                    val expected = IntArray(w * h)
                    val covered = BooleanArray(w * h)
                    for (y in 0 until bounds.height) for (x in 0 until bounds.width) {
                        val sx = bounds.left + x
                        val sy = bounds.top + y
                        val dx = when (angle) { 90 -> w - 1 - sy; 180 -> w - 1 - sx; 270 -> sy; else -> sx }
                        val dy = when (angle) { 90 -> sx; 180 -> h - 1 - sy; 270 -> h - 1 - sx; else -> sy }
                        if (dx in 0 until w && dy in 0 until h) {
                            covered[dy * w + dx] = true
                            expected[dy * w + dx] = pixels[y * bounds.width + x]
                        }
                    }
                    val result = CaptureContentOrientationPolicy.mapPatchesToOriginal(listOf(patch), w, h, angle)
                    val actual = IntArray(w * h)
                    result.forEach { mapped ->
                        assertEquals(2f, mapped.coordinateScale)
                        assertEquals(listOf(3), mapped.blockIndices)
                        assertEquals(patch.role, mapped.role)
                        for (y in 0 until mapped.bounds.height) for (x in 0 until mapped.bounds.width) {
                            actual[(mapped.bounds.top + y) * w + mapped.bounds.left + x] =
                                mapped.pixels[y * mapped.bounds.width + x]
                        }
                    }
                    assertEquals("$w x $h angle=$angle bounds=$bounds", if (covered.any { it }) 1 else 0, result.size)
                    assertArrayEquals("$w x $h angle=$angle bounds=$bounds", expected, actual)
                }
            }
        }
    }

    private fun assertRectEquals(name: String, expected: Rect, actual: Rect) {
        assertEquals("$name left", expected.left, actual.left)
        assertEquals("$name top", expected.top, actual.top)
        assertEquals("$name right", expected.right, actual.right)
        assertEquals("$name bottom", expected.bottom, actual.bottom)
    }
}
