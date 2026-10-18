package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class DetectorGuidedBubbleMaskExtractorTest {
    @Test
    fun extract_tableDriven_doesNotMistakeLetterCounterForBubbleInterior() {
        for (open in listOf(false, true)) {
            val width = 120
            val height = 100
            val pixels = IntArray(width * height) { WHITE }
            drawEllipse(pixels, width, height, 60, 50, 34, 24,
                if (open) 38 else null, if (open) 62 else null)
            drawRect(pixels, width, IntRect(50, 40, 70, 60))
            for (y in 43 until 57) for (x in 53 until 67) pixels[y * width + x] = WHITE
            val result = DetectorGuidedBubbleMaskExtractor.extract(width, height, pixels,
                listOf(rectanglePolygon(48f, 38f, 72f, 62f)), listOf(detection(20f, 18f, 100f, 82f)))
            assertEquals("open=$open", BubbleShapeMaskQuality.TRUSTED, result.decisions.single().shapeMaskQuality)
            assertEquals("open=$open", listOf(0), result.memberDetectionIndices)
            val mask = result.instanceMasks.single()
            assertTrue("must extend above text; open=$open", mask.contains(60, 30))
            assertTrue("must extend below text; open=$open", mask.contains(60, 70))
        }
    }

    @Test
    fun refineAssignments_tableDriven_keepsOutsideNarrationSeparateFromBubbleDialogue() {
        data class Case(val name: String, val bounds: IntRect, val assignment: Int?, val expected: Int?)
        val mask = BubbleSegmentationPostprocessor.InstanceMask(10, 10, 100, 100, BooleanArray(10000) { true })
        listOf(
            Case("inside", IntRect(20, 20, 100, 40), 0, 0),
            Case("exactly 85 percent", IntRect(25, 20, 125, 40), 0, 0),
            Case("84 percent", IntRect(26, 20, 126, 40), 0, null),
            Case("center inside but most text outside", IntRect(70, 20, 140, 40), 0, null),
            Case("outside", IntRect(120, 20, 160, 40), 0, null),
            Case("empty", IntRect(20, 20, 20, 40), 0, null),
            Case("unassigned", IntRect(20, 20, 100, 40), null, null),
            Case("missing mask", IntRect(20, 20, 100, 40), 5, null),
        ).forEach { case ->
            assertEquals(case.name, listOf(case.expected), DetectorGuidedBubbleMaskExtractor.refineMemberAssignmentsByInterior(
                180, 120, listOf(case.bounds), listOf(case.assignment), listOf(mask),
            ))
        }
    }

    @Test
    fun extract_tableDriven_recoversOpenBubblesWithoutIncludingOutlineOrArtwork() {
        data class Case(val name: String, val artwork: IntRect?)
        val cases = listOf(
            Case("open outline", null),
            Case("open outline with enclosed artwork", IntRect(78, 43, 84, 57)),
        )
        cases.forEach { case ->
            val width = 120
            val height = 100
            val pixels = IntArray(width * height) { WHITE }
            drawEllipse(pixels, width, height, 60, 50, 34, 24, 38, 62)
            case.artwork?.let { drawRect(pixels, width, it) }
            val polygon = rectanglePolygon(48f, 38f, 72f, 62f)
            val result = DetectorGuidedBubbleMaskExtractor.extract(
                width, height, pixels, listOf(polygon), listOf(detection(20f, 18f, 100f, 82f)),
            )
            val decision = result.decisions.single()
            assertEquals(case.name, "accepted_detector_constrained_interior", decision.diagnostic.reason)
            assertEquals(case.name, BubbleShapeMaskQuality.TRUSTED, decision.shapeMaskQuality)
            assertEquals(case.name, "region_leaked_to_roi", decision.recoverySourceReason)
            val mask = result.instanceMasks.single()
            assertTrue(case.name, mask.contains(60, 30))
            assertTrue(case.name, mask.contains(60, 70))
            for (y in mask.top until mask.top + mask.height) {
                for (x in mask.left until mask.left + mask.width) {
                    val inText = x in 48 until 72 && y in 38 until 62
                    if (pixels[y * width + x] == BLACK && !inText) {
                        assertFalse("${case.name}: protected pixel $x,$y", mask.contains(x, y))
                    }
                }
            }
        }
    }

    @Test
    fun extract_tableDriven_reflowsTranslationAcrossLargeRecoveredBubble() {
        data class Case(val name: String, val text: String, val orientation: ShapeAwareTextLayout.Orientation)
        val cases = listOf(
            Case("Chinese with source line breaks", "你今天居然选了\n一条超短的裙子……\n来工作室……", ShapeAwareTextLayout.Orientation.HORIZONTAL),
            Case("English", "You chose to wear a really short skirt to the studio today.", ShapeAwareTextLayout.Orientation.HORIZONTAL),
            Case("vertical Chinese", "你今天居然选了一条超短的裙子来工作室", ShapeAwareTextLayout.Orientation.VERTICAL_RIGHT_TO_LEFT),
        )
        val width = 1000
        val height = 700
        val pixels = IntArray(width * height) { WHITE }
        drawEllipse(pixels, width, height, 500, 350, 390, 230, 300, 400)
        val result = DetectorGuidedBubbleMaskExtractor.extract(
            width, height, pixels,
            listOf(rectanglePolygon(186f, 265f, 814f, 434f)),
            listOf(detection(101f, 112f, 898f, 588f)),
        )
        assertEquals(BubbleShapeMaskQuality.TRUSTED, result.decisions.single().shapeMaskQuality)
        val mask = result.instanceMasks.single()
        val measurer = ShapeAwareTextLayout.TextMeasurer { text, size ->
            text.sumOf { if (it.code < 128) size * 0.56 else size.toDouble() }.toFloat()
        }
        cases.forEach { case ->
            val layout = ShapeAwareTextLayout.layout(
                mask.width, mask.height, mask.pixels, case.text, case.orientation, 10, 96, measurer,
            )
            assertTrue("${case.name}: ${layout.reason}", layout.accepted)
            assertTrue("${case.name}: larger than original 39px font", layout.fontSizePx > 39)
            assertTrue(case.name, layout.runs.none { '\n' in it.text })
            assertEquals(case.name, case.text.filterNot(Char::isWhitespace), layout.runs.joinToString("") { it.text }.filterNot(Char::isWhitespace))
            layout.runs.forEach { run ->
                for (y in run.bounds.top until run.bounds.bottom) {
                    for (x in run.bounds.left until run.bounds.right) {
                        assertTrue("${case.name}: glyph bounds stay inside bubble at $x,$y", mask.pixels[y * mask.width + x])
                    }
                }
            }
        }
    }

    @Test
    fun extract_tableDriven_preservesExactDecisionAndMaskSignatures() {
        data class Case(
            val name: String,
            val detection: MangaBubbleDetectionPostprocessor.Detection,
            val polygon: MangaMaskDebugAnalyzer.Polygon,
            val draw: (IntArray, Int, Int) -> Unit,
        )

        val cases = listOf(
            Case(
                name = "closed ellipse",
                detection = detection(20f, 18f, 100f, 82f),
                polygon = rectanglePolygon(48f, 38f, 72f, 62f),
                draw = { pixels, width, height ->
                    drawEllipse(pixels, width, height, 60, 50, 34, 24)
                },
            ),
            Case(
                name = "tight detector retry",
                detection = detection(32f, 28f, 88f, 72f),
                polygon = rectanglePolygon(48f, 38f, 72f, 62f),
                draw = { pixels, width, height ->
                    drawEllipse(pixels, width, height, 60, 50, 34, 24)
                },
            ),
            Case(
                name = "open ellipse fallback",
                detection = detection(20f, 18f, 100f, 82f),
                polygon = rectanglePolygon(48f, 38f, 72f, 62f),
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
            ),
            Case(
                name = "dark reject",
                detection = detection(20f, 18f, 100f, 82f),
                polygon = rectanglePolygon(48f, 38f, 72f, 62f),
                draw = { pixels, _, _ -> pixels.fill(DARK) },
            ),
            Case(
                name = "no member",
                detection = detection(80f, 60f, 115f, 95f),
                polygon = rectanglePolygon(48f, 38f, 72f, 62f),
                draw = { _, _, _ -> },
            ),
        )

        val actual = cases.map { case ->
            val width = 120
            val height = 100
            val pixels = IntArray(width * height) { WHITE }
            case.draw(pixels, width, height)
            val result = DetectorGuidedBubbleMaskExtractor.extract(
                width = width,
                height = height,
                argb = pixels,
                polygons = listOf(case.polygon),
                boxDetections = listOf(case.detection),
            )
            listOf(
                result.timing.totalUs,
                result.timing.assignmentUs,
                result.timing.estimateTotalUs,
                result.timing.backgroundUs,
                result.timing.luminanceUs,
                result.timing.candidateBuildUs,
                result.timing.seedUs,
                result.timing.floodUs,
                result.timing.edgeUs,
                result.timing.fillUs,
                result.timing.coverageAndCopyUs,
                result.timing.ellipseFallbackUs,
                result.timing.maskIoUs,
                result.timing.otherUs,
            ).forEach { durationUs ->
                assertTrue("${case.name}: timing must be non-negative", durationUs >= 0L)
            }
            val decision = result.decisions.single()
            listOf(
                case.name,
                decision.accepted,
                decision.diagnostic.reason,
                decision.shapeMaskQuality,
                decision.diagnostic.attempts,
                decision.diagnostic.regionPixels,
                result.instanceMasks.single().pixels.count { it },
                result.unionMask.count { it },
                result.timing.estimateCalls,
                result.timing.ellipseFallbackCalls,
            ).joinToString("|")
        }

        assertEquals(
            listOf(
                "closed ellipse|true|accepted|TRUSTED|1|2325|2325|2325|1|0",
                "tight detector retry|true|accepted_after_roi_expand|TRUSTED|2|2325|2325|2325|1|0",
                "open ellipse fallback|true|accepted_detector_constrained_interior|TRUSTED|1|2331|2331|2331|2|1",
                "dark reject|false|background_too_dark|REJECTED|1|0|0|0|1|1",
                "no member|false|detector_no_ocr_members|REJECTED|1|0|0|0|0|0",
            ),
            actual,
        )
    }

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
                name = "open detected bubble recovers measured interior",
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
                expectedReason = "accepted_detector_constrained_interior",
                expectedMemberAssignment = 0,
                expectedAttempts = 1,
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
