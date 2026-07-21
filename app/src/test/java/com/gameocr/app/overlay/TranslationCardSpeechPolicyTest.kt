package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationCardSpeechPolicyTest {

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
