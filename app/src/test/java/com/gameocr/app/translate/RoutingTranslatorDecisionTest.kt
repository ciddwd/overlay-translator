package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingTranslatorDecisionTest {

    @Test
    fun shouldUseLocalSakura_requiresDeviceCapabilityJapaneseSourceAndSimplifiedChineseTarget() {
        data class Case(
            val sourceLang: String,
            val targetLang: String,
            val deviceCapable: Boolean,
            val expected: Boolean,
        )

        val cases = listOf(
            Case(sourceLang = "ja", targetLang = "zh-CN", deviceCapable = true, expected = true),
            Case(sourceLang = "ja-JP", targetLang = "zh-CN", deviceCapable = true, expected = true),
            Case(sourceLang = " ja ", targetLang = " zh-cn ", deviceCapable = true, expected = true),
            Case(sourceLang = "ja", targetLang = "zh-CN", deviceCapable = false, expected = false),
            Case(sourceLang = "en", targetLang = "zh-CN", deviceCapable = true, expected = false),
            Case(sourceLang = "auto", targetLang = "zh-CN", deviceCapable = true, expected = false),
            Case(sourceLang = "ja", targetLang = "zh-TW", deviceCapable = true, expected = false),
            Case(sourceLang = "ja", targetLang = "en", deviceCapable = true, expected = false),
            Case(sourceLang = "ja", targetLang = "auto", deviceCapable = true, expected = false),
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                RoutingTranslator.shouldUseLocalSakura(case.sourceLang, case.targetLang, case.deviceCapable)
            )
        }
    }

    @Test
    fun shouldUseLocalHyMt2_requiresOnlyDeviceCapability() {
        data class Case(
            val name: String,
            val deviceCapable: Boolean,
            val expected: Boolean,
        )

        val cases = listOf(
            Case(name = "capable Android device", deviceCapable = true, expected = true),
            Case(name = "unsupported Android device", deviceCapable = false, expected = false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                RoutingTranslator.shouldUseLocalHyMt2(case.deviceCapable)
            )
        }
    }
}
