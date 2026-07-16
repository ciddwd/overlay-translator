package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveOverlayStylePolicyTest {

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
