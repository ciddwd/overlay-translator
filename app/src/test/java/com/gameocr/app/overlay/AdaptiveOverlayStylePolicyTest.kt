package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveOverlayStylePolicyTest {

    @Test
    fun adaptiveTextLayoutPhase_tableDriven_distinguishesPlaceholderFromFinalText() {
        val cases = listOf(
            "ellipsis placeholder" to ("…" to AdaptiveTextLayoutPhase.PLACEHOLDER),
            "three dots are real text" to ("..." to AdaptiveTextLayoutPhase.FINAL),
            "empty final text" to ("" to AdaptiveTextLayoutPhase.FINAL),
            "translated text" to ("译文" to AdaptiveTextLayoutPhase.FINAL),
        )

        cases.forEach { (name, inputAndExpected) ->
            assertEquals(
                name,
                inputAndExpected.second,
                resolveAdaptiveTextLayoutPhase(inputAndExpected.first),
            )
        }
    }

    @Test
    fun adaptiveTextLayoutPhasePolicy_tableDriven_fitsStreamingButReportsOnlyStableStates() {
        data class Case(
            val phase: AdaptiveTextLayoutPhase,
            val expectedFit: Boolean,
            val expectedReport: Boolean,
        )

        val cases = listOf(
            Case(AdaptiveTextLayoutPhase.PLACEHOLDER, expectedFit = false, expectedReport = true),
            Case(AdaptiveTextLayoutPhase.STREAMING, expectedFit = true, expectedReport = false),
            Case(AdaptiveTextLayoutPhase.FINAL, expectedFit = true, expectedReport = true),
        )

        cases.forEach { case ->
            assertEquals(case.phase.name, case.expectedFit, shouldFitAdaptiveFinalBounds(case.phase))
            assertEquals(case.phase.name, case.expectedReport, shouldReportAdaptiveTextLayout(case.phase))
        }
    }

    @Test
    fun adaptiveLargestFittingTextSizePx_tableDriven_returnsLargestValidCandidate() {
        data class Case(
            val name: String,
            val min: Int,
            val max: Int,
            val largestFitting: Int,
            val expectedSize: Int,
            val expectedNextFits: Boolean?,
        )

        val cases = listOf(
            Case("middle candidate", 4, 28, 17, 17, false),
            Case("maximum fits", 4, 28, 28, 28, null),
            Case("only minimum fits", 4, 28, 4, 4, false),
            Case("even minimum overflows returns safe minimum", 4, 28, 3, 4, false),
            Case("reversed range clamps max to min", 8, 4, 8, 8, null),
        )

        cases.forEach { case ->
            val result = adaptiveLargestFittingTextSizePx(case.min, case.max) { size ->
                size <= case.largestFitting
            }
            assertEquals("${case.name} size", case.expectedSize, result.sizePx)
            assertEquals("${case.name} next", case.expectedNextFits, result.nextSizeFits)
            assertTrue("${case.name} probes", result.probes > 0)
        }
    }

    @Test
    fun adaptiveAutoSizeMaxSp_tableDriven_usesFourSpMinimumAndSkipsEqualBoundary() {
        data class Case(val name: String, val input: Float, val expected: Int?)

        val cases = listOf(
            Case("below minimum clamps equal and skips", 3f, null),
            Case("exact minimum skips", 4f, null),
            Case("rounding to minimum skips", 4.4f, null),
            Case("first valid rounded maximum", 4.5f, 5),
            Case("previous ten-sp crash boundary now auto-sizes", 10f, 10),
            Case("normal adaptive maximum", 18.2f, 18),
            Case("above allowed range clamps", 40f, 28),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, adaptiveAutoSizeMaxSp(case.input))
        }
    }

    @Test
    fun estimateTextSize_tableDriven_reportsSourceAndInputsForDiagnostics() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val glyphs: Int,
            val density: Float,
            val sourceThickness: IntArray,
            val expectedSource: AdaptiveTextSizeSource,
            val expectedMedianPx: Float?,
            val expectedMaxSp: Float,
        )

        val cases = listOf(
            Case(
                name = "invalid geometry uses safe default",
                width = 0,
                height = 40,
                glyphs = 8,
                density = 3f,
                sourceThickness = intArrayOf(24, 30),
                expectedSource = AdaptiveTextSizeSource.SAFE_DEFAULT,
                expectedMedianPx = null,
                expectedMaxSp = 14f,
            ),
            Case(
                name = "source boxes use even median",
                width = 320,
                height = 100,
                glyphs = 12,
                density = 3f,
                sourceThickness = intArrayOf(24, 30, 42, 36),
                expectedSource = AdaptiveTextSizeSource.SOURCE_BOX_MEDIAN,
                expectedMedianPx = 33f,
                expectedMaxSp = 11f,
            ),
            Case(
                name = "missing source boxes uses area per glyph",
                width = 240,
                height = 72,
                glyphs = 10,
                density = 3f,
                sourceThickness = IntArray(0),
                expectedSource = AdaptiveTextSizeSource.AREA_PER_GLYPH,
                expectedMedianPx = null,
                expectedMaxSp = 14.55f,
            ),
        )

        cases.forEach { case ->
            val result = AdaptiveOverlayStylePolicy.estimateTextSize(
                widthPx = case.width,
                heightPx = case.height,
                sourceGlyphCount = case.glyphs,
                scaledDensity = case.density,
                sourceLineThicknessPx = case.sourceThickness,
            )

            assertEquals(case.name, case.expectedSource, result.source)
            assertEquals("${case.name} glyphs", case.glyphs, result.sourceGlyphCount)
            assertEquals("${case.name} density", case.density, result.scaledDensity, 0.001f)
            assertEquals("${case.name} max", case.expectedMaxSp, result.maxTextSizeSp, 0.02f)
            if (case.expectedMedianPx == null) {
                assertEquals("${case.name} median", null, result.sourceLineMedianPx)
            } else {
                assertEquals(
                    "${case.name} median",
                    case.expectedMedianPx,
                    result.sourceLineMedianPx ?: Float.NaN,
                    0.001f,
                )
            }
        }
    }

    @Test
    fun adaptiveTextLayoutOverflow_tableDriven_coversClippingHeightLinesAndEllipsis() {
        data class Case(
            val name: String,
            val textLength: Int = 20,
            val visibleTextEnd: Int = 20,
            val layoutHeightPx: Int = 60,
            val contentHeightPx: Int = 60,
            val lineCount: Int = 2,
            val maxLines: Int = 10,
            val ellipsized: Boolean = false,
            val expected: Boolean,
            val expectedReasons: Set<AdaptiveTextOverflowReason> = emptySet(),
        )

        val cases = listOf(
            Case(name = "exact fit", expected = false),
            Case(
                name = "text end clipped",
                visibleTextEnd = 18,
                expected = true,
                expectedReasons = setOf(AdaptiveTextOverflowReason.TEXT_END),
            ),
            Case(
                name = "layout taller than content",
                layoutHeightPx = 61,
                expected = true,
                expectedReasons = setOf(AdaptiveTextOverflowReason.HEIGHT),
            ),
            Case(
                name = "line limit exceeded",
                lineCount = 11,
                expected = true,
                expectedReasons = setOf(AdaptiveTextOverflowReason.MAX_LINES),
            ),
            Case(
                name = "ellipsis reported",
                ellipsized = true,
                expected = true,
                expectedReasons = setOf(AdaptiveTextOverflowReason.ELLIPSIS),
            ),
            Case(
                name = "multiple reasons are retained",
                visibleTextEnd = 10,
                layoutHeightPx = 70,
                lineCount = 12,
                ellipsized = true,
                expected = true,
                expectedReasons = AdaptiveTextOverflowReason.entries.toSet(),
            ),
            Case(
                name = "negative empty metrics normalize safely",
                textLength = 0,
                visibleTextEnd = -1,
                layoutHeightPx = -1,
                contentHeightPx = -1,
                lineCount = 0,
                maxLines = 0,
                expected = false,
            ),
        )

        cases.forEach { case ->
            val reasons = adaptiveTextLayoutOverflowReasons(
                textLength = case.textLength,
                visibleTextEnd = case.visibleTextEnd,
                layoutHeightPx = case.layoutHeightPx,
                contentHeightPx = case.contentHeightPx,
                lineCount = case.lineCount,
                maxLines = case.maxLines,
                ellipsized = case.ellipsized,
            )
            assertEquals("${case.name} reasons", case.expectedReasons, reasons)
            assertEquals(
                case.name,
                case.expected,
                adaptiveTextLayoutOverflow(
                    textLength = case.textLength,
                    visibleTextEnd = case.visibleTextEnd,
                    layoutHeightPx = case.layoutHeightPx,
                    contentHeightPx = case.contentHeightPx,
                    lineCount = case.lineCount,
                    maxLines = case.maxLines,
                    ellipsized = case.ellipsized,
                ),
            )
        }
    }

    @Test
    fun resolve_tableDriven_selectsReadableColorsOrSafeFallback() {
        data class Case(
            val name: String,
            val colors: IntArray,
            val width: Int = 180,
            val height: Int = 54,
            val expectedForeground: Int? = null,
            val expectedBackground: Int? = null,
            val expectedFallback: AdaptiveStyleFallbackReason,
        )

        val cases = listOf(
            Case(
                name = "dark flat background",
                colors = IntArray(64) { 0xFF101820.toInt() },
                expectedForeground = 0xFFFFFFFF.toInt(),
                expectedFallback = AdaptiveStyleFallbackReason.NONE,
            ),
            Case(
                name = "light flat background",
                colors = IntArray(64) { 0xFFF4EEDC.toInt() },
                expectedForeground = 0xFF000000.toInt(),
                expectedFallback = AdaptiveStyleFallbackReason.NONE,
            ),
            Case(
                name = "middle gray still meets normal text contrast",
                colors = IntArray(64) { 0xFF777777.toInt() },
                expectedForeground = 0xFF000000.toInt(),
                expectedFallback = AdaptiveStyleFallbackReason.NONE,
            ),
            Case(
                name = "blue flat background",
                colors = IntArray(64) { 0xFF1565C0.toInt() },
                expectedForeground = 0xFFFFFFFF.toInt(),
                expectedFallback = AdaptiveStyleFallbackReason.NONE,
            ),
            Case(
                name = "busy checkerboard",
                colors = IntArray(64) { if (it % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() },
                expectedForeground = 0xFFFFFFFF.toInt(),
                expectedBackground = 0xFF000000.toInt(),
                expectedFallback = AdaptiveStyleFallbackReason.BUSY_BACKGROUND,
            ),
            Case(
                name = "busy mostly light bubble keeps dominant light background",
                colors = IntArray(64) { if (it < 34) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() },
                expectedForeground = 0xFF000000.toInt(),
                expectedBackground = 0xFFFFFFFF.toInt(),
                expectedFallback = AdaptiveStyleFallbackReason.BUSY_BACKGROUND,
            ),
            Case(
                name = "transparent samples",
                colors = IntArray(64) { 0x00112233 },
                expectedFallback = AdaptiveStyleFallbackReason.TOO_FEW_SAMPLES,
            ),
            Case(
                name = "tiny OCR region",
                colors = IntArray(64) { 0xFFFFFFFF.toInt() },
                width = 7,
                height = 20,
                expectedFallback = AdaptiveStyleFallbackReason.INVALID_REGION,
            ),
        )

        cases.forEach { case ->
            val result = AdaptiveOverlayStylePolicy.resolve(
                AdaptiveStyleSample(
                    edgeColors = case.colors,
                    boxWidthPx = case.width,
                    boxHeightPx = case.height,
                    sourceGlyphCount = 8,
                    scaledDensity = 3f,
                )
            )

            assertEquals(case.name, case.expectedFallback, result.fallbackReason)
            assertEquals("${case.name} background must cover source text", 0xFF, result.backgroundColor ushr 24)
            case.expectedBackground?.let { expected ->
                assertEquals(case.name, expected, result.backgroundColor)
            }
            case.expectedForeground?.let { expected ->
                assertEquals(case.name, expected, result.foregroundColor)
                assertEquals(
                    case.name,
                    case.expectedFallback != AdaptiveStyleFallbackReason.NONE,
                    result.usedFallback,
                )
                assertTrue(
                    "${case.name} contrast",
                    AdaptiveOverlayStylePolicy.contrastRatio(
                        result.foregroundColor,
                        result.backgroundColor,
                    ) >= 4.5,
                )
            }
        }
    }

    @Test
    fun estimateMaxTextSizeSp_tableDriven_clampsAndRespondsToGeometry() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val glyphs: Int,
            val density: Float,
            val expectedMin: Float,
            val expectedMax: Float,
            val sourceLineThicknessPx: IntArray = IntArray(0),
        )

        val cases = listOf(
            Case("invalid density uses safe default", 100, 40, 8, 0f, 14f, 14f),
            Case("small block clamps to minimum", 30, 15, 10, 3f, expectedMin = 4f, expectedMax = 4f),
            Case("common dialogue block", 240, 72, 10, 3f, 13f, 15f),
            Case("large single glyph clamps to maximum", 240, 240, 1, 2f, 28f, 28f),
            Case("long text reduces maximum size", 240, 72, 40, 3f, expectedMin = 7f, expectedMax = 8f),
            Case(
                name = "source column median wins over loose bubble geometry",
                width = 343,
                height = 340,
                glyphs = 35,
                density = 3f,
                sourceLineThicknessPx = intArrayOf(28, 30, 32),
                expectedMin = 10f,
                expectedMax = 10f,
            ),
            Case(
                name = "tiny source line clamps to four sp",
                width = 180,
                height = 240,
                glyphs = 12,
                density = 3f,
                sourceLineThicknessPx = intArrayOf(6, 8),
                expectedMin = 4f,
                expectedMax = 4f,
            ),
            Case(
                name = "large source line clamps to maximum",
                width = 300,
                height = 300,
                glyphs = 4,
                density = 3f,
                sourceLineThicknessPx = intArrayOf(100, 120),
                expectedMin = 28f,
                expectedMax = 28f,
            ),
        )

        cases.forEach { case ->
            val result = AdaptiveOverlayStylePolicy.estimateMaxTextSizeSp(
                widthPx = case.width,
                heightPx = case.height,
                sourceGlyphCount = case.glyphs,
                scaledDensity = case.density,
                sourceLineThicknessPx = case.sourceLineThicknessPx,
            )
            assertTrue("${case.name}: $result", result >= case.expectedMin)
            assertTrue("${case.name}: $result", result <= case.expectedMax)
        }
    }

    @Test
    fun adaptiveOverlaySize_tableDriven_neverExpandsBeyondOriginalBox() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val expected: AdaptiveOverlaySize,
        )

        val cases = listOf(
            Case("manga bubble stays exact", 129, 252, AdaptiveOverlaySize(129, 252)),
            Case("wide title stays exact", 433, 134, AdaptiveOverlaySize(433, 134)),
            Case("zero-sized OCR box stays drawable", 0, 0, AdaptiveOverlaySize(1, 1)),
            Case("negative malformed box stays drawable", -8, -3, AdaptiveOverlaySize(1, 1)),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, adaptiveOverlaySize(case.width, case.height))
        }
    }

    @Test
    fun adaptiveHorizontalOverflowHeight_tableDriven_expandsOnlyInsideSafeLowerBoundary() {
        data class Case(
            val name: String,
            val currentHeight: Int,
            val contentHeight: Int,
            val requiredHeight: Int,
            val top: Int,
            val screenHeight: Int,
            val lowerBoundary: Int,
            val gap: Int,
            val expected: Int,
        )

        val cases = listOf(
            Case("already fits", 22, 22, 22, 100, 1000, 1000, 8, 22),
            Case("adds exact missing content height", 22, 22, 28, 100, 1000, 1000, 8, 28),
            Case("neighbor caps expansion", 30, 30, 80, 100, 1000, 150, 8, 42),
            Case("screen edge caps expansion", 30, 30, 80, 960, 1000, 1000, 0, 40),
            Case("invalid current height remains drawable", 0, 0, 0, 0, 0, 0, 0, 1),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                adaptiveHorizontalOverflowHeight(
                    currentHeightPx = case.currentHeight,
                    contentHeightPx = case.contentHeight,
                    requiredContentHeightPx = case.requiredHeight,
                    topPx = case.top,
                    screenHeightPx = case.screenHeight,
                    lowerBoundaryPx = case.lowerBoundary,
                    gapPx = case.gap,
                ),
            )
        }
    }

    @Test
    fun adaptiveEraseRects_tableDriven_coversOnlyDetectedSourceText() {
        data class Case(
            val name: String,
            val block: OverlayIntRect,
            val sourceBoxes: List<OverlayIntRect>,
            val expected: List<OverlayIntRect>,
        )

        val block = OverlayIntRect(100, 200, 300, 500)
        val cases = listOf(
            Case(
                name = "missing source geometry falls back to full block",
                block = block,
                sourceBoxes = emptyList(),
                expected = listOf(OverlayIntRect(0, 0, 200, 300)),
            ),
            Case(
                name = "single text line becomes a small relative erase rect",
                block = block,
                sourceBoxes = listOf(OverlayIntRect(120, 230, 160, 290)),
                expected = listOf(OverlayIntRect(18, 28, 62, 92)),
            ),
            Case(
                name = "edge line clamps expansion inside bubble",
                block = block,
                sourceBoxes = listOf(OverlayIntRect(100, 200, 135, 250)),
                expected = listOf(OverlayIntRect(0, 0, 37, 52)),
            ),
            Case(
                name = "multiple columns stay separate",
                block = block,
                sourceBoxes = listOf(
                    OverlayIntRect(240, 220, 270, 430),
                    OverlayIntRect(190, 230, 220, 440),
                ),
                expected = listOf(
                    OverlayIntRect(138, 18, 172, 232),
                    OverlayIntRect(88, 28, 122, 242),
                ),
            ),
            Case(
                name = "fully outside source falls back to full block",
                block = block,
                sourceBoxes = listOf(OverlayIntRect(400, 600, 450, 650)),
                expected = listOf(OverlayIntRect(0, 0, 200, 300)),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                adaptiveEraseRects(case.block, case.sourceBoxes),
            )
        }
    }
}
