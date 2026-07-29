package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class DetectorGuidedBubbleMaskExtractorTest {
    @Test
    fun extract_tableDriven_acceptsClosedShapesAndRejectsUnsafeCandidates() {
        data class Case(
            val name: String,
            val detection: MangaBubbleDetectionPostprocessor.Detection,
            val draw: (IntArray, Int, Int) -> Unit,
            val expectedAccepted: Boolean,
            val expectedReason: String,
            val expectedMemberAssignment: Int?,
            val expectedAttempts: Int,
        )
        val cases = listOf(
            Case(
                name = "closed ellipse",
                detection = detection(20f, 18f, 100f, 82f),
                draw = { pixels, width, height ->
                    drawEllipse(pixels, width, height, 60, 50, 34, 24)
                },
                expectedAccepted = true,
                expectedReason = "accepted",
                expectedMemberAssignment = 0,
                expectedAttempts = 1,
            ),
            Case(
                name = "tight detector retries with expanded roi",
                detection = detection(32f, 28f, 88f, 72f),
                draw = { pixels, width, height ->
                    drawEllipse(pixels, width, height, 60, 50, 34, 24)
                },
                expectedAccepted = true,
                expectedReason = "accepted_after_roi_expand",
                expectedMemberAssignment = 0,
                expectedAttempts = 2,
            ),
            Case(
                name = "open detected bubble uses ellipse fallback",
                detection = detection(20f, 18f, 100f, 82f),
                draw = { pixels, width, height ->
                    drawEllipse(
                        pixels,
                        width,
                        height,
                        60,
                        50,
                        34,
                        24,
                        gapFromY = 38,
                        gapToY = 62,
                    )
                },
                expectedAccepted = true,
                expectedReason = "accepted_ellipse_fallback",
                expectedMemberAssignment = 0,
                expectedAttempts = 2,
            ),
            Case(
                name = "detector without ocr member",
                detection = detection(80f, 60f, 115f, 95f),
                draw = { _, _, _ -> },
                expectedAccepted = false,
                expectedReason = "detector_no_ocr_members",
                expectedMemberAssignment = null,
                expectedAttempts = 1,
            ),
        )

        cases.forEach { case ->
            val width = 120
            val height = 100
            val pixels = IntArray(width * height) { WHITE }
            case.draw(pixels, width, height)
            val polygons = listOf(rectanglePolygon(48f, 38f, 72f, 62f))
            val result = DetectorGuidedBubbleMaskExtractor.extract(
                width = width,
                height = height,
                argb = pixels,
                polygons = polygons,
                boxDetections = listOf(case.detection),
            )

            assertEquals(case.name, case.expectedAccepted, result.decisions.single().accepted)
            assertEquals(case.name, case.expectedReason, result.decisions.single().diagnostic.reason)
            assertEquals(
                case.name,
                listOf(case.expectedMemberAssignment),
                result.memberDetectionIndices,
            )
            assertEquals(
                case.name,
                case.expectedAttempts,
                result.decisions.single().diagnostic.attempts,
            )
            assertEquals(
                case.name,
                case.expectedAccepted,
                result.instanceMasks.single().pixels.any { it },
            )
            if (case.expectedAccepted) {
                assertTrue(case.name, result.instanceMasks.single().contains(60, 50))
                assertFalse(case.name, result.instanceMasks.single().contains(5, 5))
            }
        }
    }

    @Test
    fun extract_tableDriven_ellipseFallbackRejectsUnsafeCandidates() {
        data class Case(
            val name: String,
            val pixels: IntArray,
            val polygon: MangaMaskDebugAnalyzer.Polygon,
            val expectedReason: String,
        )

        val width = 120
        val height = 100
        val openOutline = IntArray(width * height) { WHITE }.also { pixels ->
            drawEllipse(
                pixels = pixels,
                width = width,
                height = height,
                centerX = 60,
                centerY = 50,
                radiusX = 34,
                radiusY = 24,
                gapFromY = 38,
                gapToY = 62,
            )
        }
        val cases = listOf(
            Case(
                name = "member at detector corner is outside fallback ellipse",
                pixels = openOutline,
                polygon = rectanglePolygon(20f, 18f, 44f, 42f),
                expectedReason = "region_leaked_to_roi",
            ),
            Case(
                name = "dark candidate never becomes an ellipse",
                pixels = IntArray(width * height) { DARK },
                polygon = rectanglePolygon(48f, 38f, 72f, 62f),
                expectedReason = "background_too_dark",
            ),
        )

        cases.forEach { case ->
            val result = DetectorGuidedBubbleMaskExtractor.extract(
                width = width,
                height = height,
                argb = case.pixels,
                polygons = listOf(case.polygon),
                boxDetections = listOf(detection(20f, 18f, 100f, 82f)),
            )

            assertFalse(case.name, result.decisions.single().accepted)
            assertEquals(case.name, case.expectedReason, result.decisions.single().diagnostic.reason)
            assertFalse(case.name, result.instanceMasks.single().pixels.any { it })
        }
    }

    @Test
    fun extract_tableDriven_assignsEachMemberToBestDetectorBox() {
        val width = 180
        val height = 100
        val pixels = IntArray(width * height) { WHITE }
        drawEllipse(pixels, width, height, 50, 50, 30, 24)
        drawEllipse(pixels, width, height, 130, 50, 30, 24)
        val result = DetectorGuidedBubbleMaskExtractor.extract(
            width = width,
            height = height,
            argb = pixels,
            polygons = listOf(
                rectanglePolygon(42f, 38f, 58f, 62f),
                rectanglePolygon(122f, 38f, 138f, 62f),
            ),
            boxDetections = listOf(
                detection(15f, 18f, 85f, 82f),
                detection(95f, 18f, 165f, 82f),
            ),
        )

        assertEquals(listOf(0), result.decisions[0].memberIndices)
        assertEquals(listOf(1), result.decisions[1].memberIndices)
        assertEquals(listOf(0, 1), result.memberDetectionIndices)
        assertEquals(2, result.acceptedCount)
        assertTrue(result.durationMs >= 0L)
    }

    private fun detection(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = MangaBubbleDetectionPostprocessor.Detection(
        confidence = 0.9f,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    private fun rectanglePolygon(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = MangaMaskDebugAnalyzer.Polygon(
        listOf(
            MangaMaskDebugAnalyzer.Point(left, top),
            MangaMaskDebugAnalyzer.Point(right, top),
            MangaMaskDebugAnalyzer.Point(right, bottom),
            MangaMaskDebugAnalyzer.Point(left, bottom),
        )
    )

    private fun drawEllipse(
        pixels: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radiusX: Int,
        radiusY: Int,
        gapFromY: Int? = null,
        gapToY: Int? = null,
    ) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val distance = ((x - centerX).toDouble() / radiusX).pow(2) +
                    ((y - centerY).toDouble() / radiusY).pow(2)
                if (distance in 0.91..1.09) {
                    val inGap = gapFromY != null && gapToY != null &&
                        x >= centerX + radiusX - 5 && y in gapFromY..gapToY
                    if (!inGap) pixels[y * width + x] = BLACK
                }
            }
        }
        drawRect(pixels, width, IntRect(centerX - 7, centerY - 8, centerX - 3, centerY + 8))
        drawRect(pixels, width, IntRect(centerX + 3, centerY - 8, centerX + 7, centerY + 8))
    }

    private fun drawRect(
        pixels: IntArray,
        width: Int,
        rect: IntRect,
    ) {
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) pixels[y * width + x] = BLACK
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val DARK = 0xFF202020.toInt()
    }
}
