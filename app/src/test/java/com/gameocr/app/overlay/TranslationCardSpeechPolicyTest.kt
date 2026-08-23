package com.gameocr.app.overlay

import com.gameocr.app.data.OverlayTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationCardSpeechPolicyTest {

    @Test
    fun translationActionAccentColor_tableDriven_isSharedAcrossThemes() {
        data class Case(
            val name: String,
            val theme: OverlayTheme,
            val customBorder: Int = 0,
            val customForeground: Int = 0,
            val expected: Int,
        )

        listOf(
            Case("classic", OverlayTheme.CLASSIC_DARK, expected = 0xFF90CAF9.toInt()),
            Case("amber", OverlayTheme.AMBER_GOLD, expected = 0xFFB8860B.toInt()),
            Case("paper", OverlayTheme.PAPER_LIGHT, expected = 0xFFB68850.toInt()),
            Case("frost", OverlayTheme.FROST_GLASS, expected = 0xFF60A5FA.toInt()),
            Case("custom border wins", OverlayTheme.CUSTOM, 0xFF123456.toInt(), 0xFFABCDEF.toInt(), 0xFF123456.toInt()),
            Case("custom foreground fallback", OverlayTheme.CUSTOM, 0, 0xFFABCDEF.toInt(), 0xFFABCDEF.toInt()),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                translationActionAccentColor(case.theme, case.customBorder, case.customForeground),
            )
        }
    }

    @Test
    fun translationActionMarkerColor_tableDriven_replacesAlphaWithHighlighterOpacity() {
        data class Case(val name: String, val input: Int, val expected: Int)

        listOf(
            Case("opaque blue", 0xFF90CAF9.toInt(), 0x6690CAF9),
            Case("transparent amber", 0x12B8860B, 0x66B8860B),
            Case("custom black", 0xFF000000.toInt(), 0x66000000),
            Case("custom white", 0xFFFFFFFF.toInt(), 0x66FFFFFF),
        ).forEach { case ->
            assertEquals(case.name, case.expected, translationActionMarkerColor(case.input))
        }
    }

    @Test
    fun translationCardSpeechButtonMetrics_keepsCompactStableProportions() {
        data class Case(
            val name: String,
            val density: Float,
            val expectedSizePx: Int,
            val expectedPaddingPx: Int,
        )

        val cases = listOf(
            Case("mdpi", 1f, 28, 6),
            Case("xhdpi", 2f, 56, 12),
            Case("xxhdpi", 3f, 84, 18),
            Case("fractional density", 2.625f, 73, 15),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                TranslationCardSpeechButtonMetrics(case.expectedSizePx, case.expectedPaddingPx),
                translationCardSpeechButtonMetrics(case.density),
            )
        }
    }

    @Test
    fun shouldShowTranslationCardSpeechButton_requiresEnabledNonBlankText() {
        data class Case(
            val name: String,
            val enabled: Boolean,
            val text: CharSequence?,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("disabled source", false, "source", false),
            Case("disabled translation", false, "translation", false),
            Case("enabled null", true, null, false),
            Case("enabled empty", true, "", false),
            Case("enabled whitespace", true, "  \n ", false),
            Case("enabled source", true, "source", true),
            Case("enabled translation", true, "translation", true),
            Case("enabled dictionary details", true, "part of speech\ndefinition", true),
            Case("enabled selected text", true, "selected phrase", true),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldShowTranslationCardSpeechButton(case.enabled, case.text),
            )
        }
    }
}
