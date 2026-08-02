package com.gameocr.app.overlay

import com.gameocr.app.ocr.TextRegionGranularity
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveRegionRenderPolicyTest {

    @Test
    fun resolve_tableDriven_usesTransparentOutlinedFallbackForEveryRegionKind() {
        data class Case(
            val name: String,
            val granularity: TextRegionGranularity,
            val foreground: Int,
            val expectedBackground: AdaptiveRegionBackgroundMode,
            val expectedStroke: Boolean,
            val expectedStrokeColor: Int,
            val pixelMaskPipeline: Boolean = true,
        )

        val cases = listOf(
            Case(
                name = "black free text gets a white outline without an erase rectangle",
                granularity = TextRegionGranularity.FREE_TEXT,
                foreground = 0xFF000000.toInt(),
                expectedBackground = AdaptiveRegionBackgroundMode.TRANSPARENT,
                expectedStroke = true,
                expectedStrokeColor = 0xFFFFFFFF.toInt(),
            ),
            Case(
                name = "white free text gets a black outline without an erase rectangle",
                granularity = TextRegionGranularity.FREE_TEXT,
                foreground = 0xFFFFFFFF.toInt(),
                expectedBackground = AdaptiveRegionBackgroundMode.TRANSPARENT,
                expectedStroke = true,
                expectedStrokeColor = 0xFF000000.toInt(),
            ),
            Case(
                name = "bubble waits transparently for a capability patch",
                granularity = TextRegionGranularity.BUBBLE,
                foreground = 0xFF000000.toInt(),
                expectedBackground = AdaptiveRegionBackgroundMode.TRANSPARENT,
                expectedStroke = true,
                expectedStrokeColor = 0xFFFFFFFF.toInt(),
            ),
            Case(
                name = "unknown region does not trigger an opaque rectangle",
                granularity = TextRegionGranularity.UNKNOWN,
                foreground = 0xFFFFFFFF.toInt(),
                expectedBackground = AdaptiveRegionBackgroundMode.TRANSPARENT,
                expectedStroke = true,
                expectedStrokeColor = 0xFF000000.toInt(),
            ),
            Case(
                name = "line region uses the same safe fallback",
                granularity = TextRegionGranularity.LINE,
                foreground = 0xFF000000.toInt(),
                expectedBackground = AdaptiveRegionBackgroundMode.TRANSPARENT,
                expectedStroke = true,
                expectedStrokeColor = 0xFFFFFFFF.toInt(),
            ),
            Case(
                name = "ordinary OCR bubble keeps its existing adaptive erase background",
                granularity = TextRegionGranularity.BUBBLE,
                foreground = 0xFF000000.toInt(),
                expectedBackground = AdaptiveRegionBackgroundMode.ERASE_SOURCE,
                expectedStroke = false,
                expectedStrokeColor = 0xFF000000.toInt(),
                pixelMaskPipeline = false,
            ),
            Case(
                name = "ordinary OCR unknown region keeps its existing adaptive erase background",
                granularity = TextRegionGranularity.UNKNOWN,
                foreground = 0xFFFFFFFF.toInt(),
                expectedBackground = AdaptiveRegionBackgroundMode.ERASE_SOURCE,
                expectedStroke = false,
                expectedStrokeColor = 0xFF000000.toInt(),
                pixelMaskPipeline = false,
            ),
            Case(
                name = "legacy free text remains transparent without the pixel mask pipeline",
                granularity = TextRegionGranularity.FREE_TEXT,
                foreground = 0xFF000000.toInt(),
                expectedBackground = AdaptiveRegionBackgroundMode.TRANSPARENT,
                expectedStroke = true,
                expectedStrokeColor = 0xFFFFFFFF.toInt(),
                pixelMaskPipeline = false,
            ),
        )

        cases.forEach { case ->
            val plan = AdaptiveRegionRenderPolicy.resolve(
                granularity = case.granularity,
                foregroundColor = case.foreground,
                pixelMaskPatchPipelineEnabled = case.pixelMaskPipeline,
            )
            assertEquals("${case.name} background", case.expectedBackground, plan.backgroundMode)
            assertEquals("${case.name} stroke", case.expectedStroke, plan.textStyle.strokeEnabled)
            assertEquals("${case.name} stroke color", case.expectedStrokeColor, plan.textStyle.strokeColor)
        }
    }
}
