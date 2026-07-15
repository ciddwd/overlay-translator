package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationCardLayoutPolicyTest {

    @Test
    fun adaptiveHeight_usesNaturalHeightUntilTheMaximum() {
        data class Case(
            val name: String,
            val naturalHeightPx: Int,
            val maxHeightPx: Int,
            val expectedHeightPx: Int,
        )

        val cases = listOf(
            Case("short content stays compact", 240, 800, 240),
            Case("exact maximum is unchanged", 800, 800, 800),
            Case("long content is capped", 1200, 800, 800),
            Case("zero natural height remains measurable", 0, 800, 1),
            Case("invalid maximum remains measurable", 240, 0, 1),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedHeightPx,
                translationCardAdaptiveHeightPx(case.naturalHeightPx, case.maxHeightPx),
            )
        }
    }

    @Test
    fun layoutSpec_adaptsCardBoundsForPortraitAndLandscape() {
        data class Case(
            val name: String,
            val widthPx: Int,
            val heightPx: Int,
            val insets: TranslationCardSafeInsets,
            val verticalPaddingPx: Int,
            val expectedSafeWidthPx: Int,
            val expectedSafeHeightPx: Int,
            val expectedWidthFraction: Float,
            val expectedHeightFraction: Float,
            val expectedShellWidthPx: Int,
            val expectedCardHeightPx: Int,
        )

        val cases = listOf(
            Case(
                "portrait without insets", 1080, 2400, TranslationCardSafeInsets(), 48,
                1080, 2400, 0.88f, 0.72f, 950, 1680,
            ),
            Case(
                "landscape without insets", 2400, 1080, TranslationCardSafeInsets(), 48,
                2400, 1080, 0.78f, 0.88f, 1872, 902,
            ),
            Case(
                "landscape status and bottom navigation bars", 2400, 1080,
                TranslationCardSafeInsets(top = 90, bottom = 60), 48,
                2400, 930, 0.78f, 0.88f, 1872, 770,
            ),
            Case(
                "landscape side cutout and side navigation", 2400, 1080,
                TranslationCardSafeInsets(left = 100, top = 40, right = 120, bottom = 48), 72,
                2180, 992, 0.78f, 0.88f, 1700, 801,
            ),
            Case(
                "square safe area treated as portrait", 1200, 1200,
                TranslationCardSafeInsets(), 24,
                1200, 1200, 0.88f, 0.72f, 1056, 840,
            ),
            Case(
                "oversized insets clamp to positive dimensions", 100, 80,
                TranslationCardSafeInsets(left = 70, top = 50, right = 70, bottom = 50), 24,
                1, 1, 0.88f, 0.72f, 1, 1,
            ),
        )

        cases.forEach { case ->
            val result = translationCardLayoutSpec(case.widthPx, case.heightPx, case.insets)
            assertEquals(case.name, case.expectedSafeWidthPx, result.safeWidthPx)
            assertEquals(case.name, case.expectedSafeHeightPx, result.safeHeightPx)
            assertEquals(case.name, case.expectedWidthFraction, result.widthFraction, 0.0001f)
            assertEquals(case.name, case.expectedHeightFraction, result.maxHeightFraction, 0.0001f)
            assertEquals(case.name, case.expectedShellWidthPx, result.shellWidthPx())
            assertEquals(case.name, case.expectedCardHeightPx, result.cardHeightPx(case.verticalPaddingPx))
        }
    }
}
