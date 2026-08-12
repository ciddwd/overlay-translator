package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class MangaMaskDebugAnalyzerTest {

    private data class BubbleCase(
        val name: String,
        val drawBorder: (IntArray, Int, Int) -> Unit,
        val contentBounds: IntRect = IntRect(48, 38, 72, 62),
        val polygon: MangaMaskDebugAnalyzer.Polygon? = null,
        val expectedAccepted: Boolean,
        val expectedReason: String,
        val expectedInteriorPoints: List<Pair<Int, Int>> = listOf(60 to 50),
    )

    @Test
    fun bubbleEstimator_tableDriven_closedLightBubbleAccepted_openOrDarkRejected() {
        val cases = listOf(
            BubbleCase(
                name = "closed white ellipse",
                drawBorder = { pixels, width, height ->
                    drawEllipse(
                        pixels = pixels,
                        width = width,
                        height = height,
                        centerX = 60,
                        centerY = 50,
                        radiusX = 34,
                        radiusY = 24,
                        color = BLACK,
                    )
                },
                expectedAccepted = true,
                expectedReason = "accepted",
            ),
            BubbleCase(
                name = "closed white ellipse keeps gray antialias as boundary",
                drawBorder = { pixels, width, height ->
                    drawEllipse(
                        pixels = pixels,
                        width = width,
                        height = height,
                        centerX = 60,
                        centerY = 50,
                        radiusX = 34,
                        radiusY = 24,
                        color = ANTIALIAS_GRAY,
                    )
                },
                expectedAccepted = true,
                expectedReason = "accepted",
            ),
            BubbleCase(
                name = "closed gray ellipse",
                drawBorder = { pixels, width, height ->
                    fillEllipse(
                        pixels = pixels,
                        width = width,
                        height = height,
                        centerX = 60,
                        centerY = 50,
                        radiusX = 34,
                        radiusY = 24,
                        color = GRAY,
                    )
                    drawEllipse(
                        pixels = pixels,
                        width = width,
                        height = height,
                        centerX = 60,
                        centerY = 50,
                        radiusX = 34,
                        radiusY = 24,
                        color = BLACK,
                    )
                },
                expectedAccepted = true,
                expectedReason = "accepted",
            ),
            BubbleCase(
                name = "wide gray gradient needs expanded roi",
                drawBorder = { pixels, width, height ->
                    fillEllipseGradient(
                        pixels = pixels,
                        width = width,
                        height = height,
                        centerX = 60,
                        centerY = 50,
                        radiusX = 52,
                        radiusY = 32,
                        dark = 0xFF686868.toInt(),
                        light = 0xFFB8B8B8.toInt(),
                    )
                    drawEllipse(
                        pixels = pixels,
                        width = width,
                        height = height,
                        centerX = 60,
                        centerY = 50,
                        radiusX = 52,
                        radiusY = 32,
                        color = BLACK,
                    )
                },
                expectedAccepted = true,
                expectedReason = "accepted_after_roi_expand",
                expectedInteriorPoints = listOf(20 to 50, 60 to 50, 100 to 50),
            ),
            BubbleCase(
                name = "extreme smooth gradient remains fully covered",
                drawBorder = { pixels, width, height ->
                    fillEllipseGradient(
                        pixels = pixels,
                        width = width,
                        height = height,
                        centerX = 60,
                        centerY = 50,
                        radiusX = 52,
                        radiusY = 32,
                        dark = 0xFF1E1E1E.toInt(),
                        light = 0xFFE6E6E6.toInt(),
                    )
                    drawEllipse(
                        pixels = pixels,
                        width = width,
                        height = height,
                        centerX = 60,
                        centerY = 50,
                        radiusX = 52,
                        radiusY = 32,
                        color = BLACK,
                    )
                },
                expectedAccepted = true,
                expectedReason = "accepted_after_roi_expand",
                expectedInteriorPoints = listOf(20 to 50, 60 to 50, 100 to 50),
            ),
            BubbleCase(
                name = "open white ellipse leaks",
                drawBorder = { pixels, width, height ->
                    drawEllipse(
                        pixels = pixels,
                        width = width,
                        height = height,
                        centerX = 60,
                        centerY = 50,
                        radiusX = 34,
                        radiusY = 24,
                        color = BLACK,
                        gapFromY = 42,
                        gapToY = 58,
                    )
                },
                expectedAccepted = false,
                expectedReason = "region_leaked_to_roi",
            ),
            BubbleCase(
                name = "dark panel is outside prototype scope",
                drawBorder = { pixels, _, _ ->
                    pixels.fill(0xFF303030.toInt())
                },
                expectedAccepted = false,
                expectedReason = "background_too_dark",
            ),
            BubbleCase(
                name = "tiny punctuation-like candidate",
                drawBorder = { _, _, _ -> },
                contentBounds = IntRect(55, 45, 65, 55),
                polygon = rectanglePolygon(55f, 45f, 65f, 55f),
                expectedAccepted = false,
                expectedReason = "content_too_small",
            ),
        )

        cases.forEach { case ->
            val width = 120
            val height = 100
            val pixels = IntArray(width * height) { WHITE }
            case.drawBorder(pixels, width, height)
            drawRect(pixels, width, IntRect(53, 42, 57, 58), BLACK)
            drawRect(pixels, width, IntRect(63, 42, 67, 58), BLACK)
            val polygon = case.polygon ?: rectanglePolygon(48f, 38f, 72f, 62f)
            val probabilityMask = BooleanArray(width * height)
            for (y in 42 until 58) {
                for (x in 53 until 57) probabilityMask[y * width + x] = true
                for (x in 63 until 67) probabilityMask[y * width + x] = true
            }

            val analysis = MangaMaskDebugAnalyzer.analyze(
                width = width,
                height = height,
                argb = pixels,
                probabilityTextMask = probabilityMask,
                polygons = listOf(polygon),
                bubbles = listOf(
                    MangaMaskDebugAnalyzer.BubbleInput(
                        contentBounds = case.contentBounds,
                        memberIndices = listOf(0),
                    )
                ),
            )

            assertTrue(
                "${case.name}: expected accepted=${case.expectedAccepted}, " +
                    "actual=${analysis.bubbles.single()}",
                analysis.bubbles.single().accepted == case.expectedAccepted,
            )
            assertTrue(
                "${case.name}: expected reason=${case.expectedReason}, " +
                    "actual=${analysis.bubbles.single()}",
                analysis.bubbles.single().reason == case.expectedReason,
            )
            if (case.expectedAccepted) {
                assertTrue(
                    "${case.name}: accepted bubble covers its OCR members",
                    analysis.bubbles.single().memberCoverage >= 0.72f,
                )
                case.expectedInteriorPoints.forEach { (x, y) ->
                    assertTrue(
                        "${case.name}: interior point ($x,$y) belongs to bubble",
                        analysis.bubbleInteriorMask[y * width + x],
                    )
                }
                assertFalse("${case.name}: exterior excluded", analysis.bubbleInteriorMask[5 * width + 5])
            }
        }
    }

    @Test
    fun edgeBoundedFallback_tableDriven_acceptsClosedShapesAndRejectsOpenRegions() {
        data class EdgeCase(
            val name: String,
            val background: Int,
            val lowBackground: Int,
            val draw: (IntArray, Int, Int) -> Unit,
            val expectedAccepted: Boolean,
        )
        val cases = listOf(
            EdgeCase(
                name = "closed white ellipse",
                background = 255,
                lowBackground = 240,
                draw = { pixels, width, height ->
                    drawEllipse(pixels, width, height, 60, 50, 34, 24, BLACK)
                },
                expectedAccepted = true,
            ),
            EdgeCase(
                name = "closed strong gradient ellipse",
                background = 140,
                lowBackground = 60,
                draw = { pixels, width, height ->
                    fillEllipseGradient(
                        pixels,
                        width,
                        height,
                        60,
                        50,
                        52,
                        32,
                        0xFF1E1E1E.toInt(),
                        0xFFE6E6E6.toInt(),
                    )
                    drawEllipse(pixels, width, height, 60, 50, 52, 32, BLACK)
                },
                expectedAccepted = true,
            ),
            EdgeCase(
                name = "open white ellipse",
                background = 255,
                lowBackground = 240,
                draw = { pixels, width, height ->
                    drawEllipse(
                        pixels,
                        width,
                        height,
                        60,
                        50,
                        34,
                        24,
                        BLACK,
                        gapFromY = 40,
                        gapToY = 60,
                    )
                },
                expectedAccepted = false,
            ),
            EdgeCase(
                name = "unbounded dark panel",
                background = 48,
                lowBackground = 48,
                draw = { pixels, _, _ -> pixels.fill(0xFF303030.toInt()) },
                expectedAccepted = false,
            ),
        )

        cases.forEach { case ->
            val width = 120
            val height = 100
            val pixels = IntArray(width * height) { WHITE }
            case.draw(pixels, width, height)
            val result = MangaMaskDebugAnalyzer.analyzeEdgeRegionForTest(
                width = width,
                height = height,
                argb = pixels,
                polygons = listOf(rectanglePolygon(48f, 38f, 72f, 62f)),
                backgroundLuminance = case.background,
                lowBackgroundLuminance = case.lowBackground,
            )
            assertTrue(
                "${case.name}: expected accepted=${case.expectedAccepted}, " +
                    "reason=${result.reason} pixels=${result.regionPixels}",
                (result.mask != null) == case.expectedAccepted,
            )
            if (case.expectedAccepted) {
                assertTrue("${case.name}: center is inside", result.mask!![50 * width + 60])
                assertFalse("${case.name}: corner is outside", result.mask[5 * width + 5])
            }
        }
    }

    private data class TextMaskCase(
        val name: String,
        val probabilityPixel: Pair<Int, Int>,
        val expectedVisible: Boolean,
    )

    @Test
    fun textEraseMask_tableDriven_intersectsProbabilityWithOcrPolygon() {
        val width = 40
        val height = 30
        val polygon = MangaMaskDebugAnalyzer.Polygon(
            listOf(
                MangaMaskDebugAnalyzer.Point(10f, 8f),
                MangaMaskDebugAnalyzer.Point(28f, 10f),
                MangaMaskDebugAnalyzer.Point(26f, 22f),
                MangaMaskDebugAnalyzer.Point(8f, 20f),
            )
        )
        val cases = listOf(
            TextMaskCase("probability inside quad", 18 to 15, true),
            TextMaskCase("probability outside quad", 2 to 2, false),
            TextMaskCase("quad without probability", 12 to 12, false),
        )

        cases.forEach { case ->
            val probability = BooleanArray(width * height)
            if (case.name != "quad without probability") {
                probability[case.probabilityPixel.second * width + case.probabilityPixel.first] = true
            }
            val analysis = MangaMaskDebugAnalyzer.analyze(
                width = width,
                height = height,
                argb = IntArray(width * height) { WHITE },
                probabilityTextMask = probability,
                polygons = listOf(polygon),
                bubbles = emptyList(),
            )
            val (x, y) = case.probabilityPixel
            assertTrue(
                case.name,
                analysis.textEraseMask[y * width + x] == case.expectedVisible,
            )
        }
    }

    @Test
    fun textEraseMask_refinesDenseDbnetRegionToLocalDarkGlyphs() {
        val width = 48
        val height = 36
        val pixels = IntArray(width * height) { WHITE }
        val polygon = rectanglePolygon(8f, 6f, 40f, 30f)
        val probability = BooleanArray(width * height)
        for (y in 6 until 30) {
            for (x in 8 until 40) probability[y * width + x] = true
        }
        drawRect(pixels, width, IntRect(14, 9, 18, 27), BLACK)
        drawRect(pixels, width, IntRect(29, 9, 33, 27), BLACK)

        val analysis = MangaMaskDebugAnalyzer.analyze(
            width = width,
            height = height,
            argb = pixels,
            probabilityTextMask = probability,
            polygons = listOf(polygon),
            bubbles = emptyList(),
        )

        assertTrue("left glyph retained", analysis.textEraseMask[18 * width + 15])
        assertTrue("right glyph retained", analysis.textEraseMask[18 * width + 31])
        assertFalse("white gap removed from dense DBNet region", analysis.textEraseMask[18 * width + 24])
        assertFalse("white polygon corner removed", analysis.textEraseMask[8 * width + 9])
    }

    @Test
    fun textEraseMask_tableDriven_usesSurroundingBackgroundToChooseInkPolarity() {
        data class Case(
            val name: String,
            val background: Int,
            val foreground: Int,
        )
        val cases = listOf(
            Case(
                name = "large dark glyph remains foreground on light paper",
                background = WHITE,
                foreground = BLACK,
            ),
            Case(
                name = "large light glyph remains foreground on dark panel",
                background = BLACK,
                foreground = WHITE,
            ),
        )

        cases.forEach { case ->
            val width = 48
            val height = 36
            val pixels = IntArray(width * height) { case.background }
            val polygon = rectanglePolygon(8f, 6f, 40f, 30f)
            val probability = BooleanArray(width * height)
            for (y in 6 until 30) {
                for (x in 8 until 40) probability[y * width + x] = true
            }
            drawRect(pixels, width, IntRect(8, 6, 33, 30), case.foreground)

            val analysis = MangaMaskDebugAnalyzer.analyze(
                width = width,
                height = height,
                argb = pixels,
                probabilityTextMask = probability,
                polygons = listOf(polygon),
                bubbles = emptyList(),
            )

            assertTrue(
                "${case.name}: majority ink is retained",
                analysis.textEraseMask[18 * width + 16],
            )
            assertFalse(
                "${case.name}: minority local background is excluded",
                analysis.textEraseMask[18 * width + 38],
            )
        }
    }

    @Test
    fun textEraseMask_tableDriven_completesDetectorMissedCornersWithoutTakingPanelLines() {
        data class Case(
            val name: String,
            val background: Int,
            val foreground: Int,
        )
        listOf(
            Case("dark text on white bubble", WHITE, BLACK),
            Case("white text on black panel", BLACK, WHITE),
        ).forEach { case ->
            val width = 48
            val height = 36
            val pixels = IntArray(width * height) { case.background }
            val polygon = rectanglePolygon(8f, 6f, 40f, 30f)
            val probability = BooleanArray(width * height)
            for (y in 8 until 28) {
                for (x in 9 until 19) probability[y * width + x] = true
            }
            drawRect(pixels, width, IntRect(13, 11, 18, 25), case.foreground)
            drawRect(pixels, width, IntRect(20, 15, 22, 18), case.foreground)
            drawRect(pixels, width, IntRect(37, 7, 38, 29), case.foreground)

            val analysis = MangaMaskDebugAnalyzer.analyze(
                width = width,
                height = height,
                argb = pixels,
                probabilityTextMask = probability,
                polygons = listOf(polygon),
                bubbles = emptyList(),
            )

            assertTrue("${case.name}: DBNet seed remains", analysis.textEraseMask[16 * width + 15])
            assertTrue("${case.name}: nearby missed corner is completed", analysis.textEraseMask[16 * width + 20])
            assertFalse("${case.name}: distant panel line is preserved", analysis.textEraseMask[16 * width + 37])
        }
    }

    @Test
    fun probabilityAccumulator_tableDriven_mapsFullAndOffsetTileCoordinates() {
        data class Case(
            val name: String,
            val scaleX: Float,
            val scaleY: Float,
            val offsetX: Int,
            val offsetY: Int,
            val expected: Pair<Int, Int>,
        )
        val cases = listOf(
            Case("full image", 2f, 2f, 0, 0, 2 to 2),
            Case("offset tile", 2f, 3f, 5, 7, 7 to 10),
        )

        cases.forEach { case ->
            val accumulator = MangaProbabilityMaskAccumulator(width = 30, height = 30)
            accumulator.merge(
                probabilityMap = arrayOf(
                    floatArrayOf(0f, 0f),
                    floatArrayOf(0f, 0.9f),
                ),
                scaleX = case.scaleX,
                scaleY = case.scaleY,
                offsetX = case.offsetX,
                offsetY = case.offsetY,
                threshold = 0.25f,
            )
            val mask = accumulator.snapshot()
            val (x, y) = case.expected
            assertTrue(case.name, mask[y * 30 + x])
            assertFalse("${case.name}: origin remains empty", mask[0])
        }
    }

    private fun rectanglePolygon(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): MangaMaskDebugAnalyzer.Polygon = MangaMaskDebugAnalyzer.Polygon(
        listOf(
            MangaMaskDebugAnalyzer.Point(left, top),
            MangaMaskDebugAnalyzer.Point(right, top),
            MangaMaskDebugAnalyzer.Point(right, bottom),
            MangaMaskDebugAnalyzer.Point(left, bottom),
        )
    )

    private fun drawRect(
        pixels: IntArray,
        width: Int,
        rect: IntRect,
        color: Int,
    ) {
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) pixels[y * width + x] = color
        }
    }

    private fun drawEllipse(
        pixels: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radiusX: Int,
        radiusY: Int,
        color: Int,
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
                    if (!inGap) pixels[y * width + x] = color
                }
            }
        }
    }

    private fun fillEllipse(
        pixels: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radiusX: Int,
        radiusY: Int,
        color: Int,
    ) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val distance = ((x - centerX).toDouble() / radiusX).pow(2) +
                    ((y - centerY).toDouble() / radiusY).pow(2)
                if (distance <= 1.0) pixels[y * width + x] = color
            }
        }
    }

    private fun fillEllipseGradient(
        pixels: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radiusX: Int,
        radiusY: Int,
        dark: Int,
        light: Int,
    ) {
        val darkChannel = dark and 0xFF
        val lightChannel = light and 0xFF
        for (y in 0 until height) {
            for (x in 0 until width) {
                val distance = ((x - centerX).toDouble() / radiusX).pow(2) +
                    ((y - centerY).toDouble() / radiusY).pow(2)
                if (distance <= 1.0) {
                    val ratio = ((x - (centerX - radiusX)).toFloat() / (radiusX * 2))
                        .coerceIn(0f, 1f)
                    val channel = (darkChannel + (lightChannel - darkChannel) * ratio).toInt()
                    pixels[y * width + x] =
                        0xFF000000.toInt() or (channel shl 16) or (channel shl 8) or channel
                }
            }
        }
    }

    private companion object {
        const val WHITE: Int = -1
        const val BLACK: Int = -0x1000000
        const val GRAY: Int = -0x656566
        const val ANTIALIAS_GRAY: Int = -0x7f7f80
    }
}
