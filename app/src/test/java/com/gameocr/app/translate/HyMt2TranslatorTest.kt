package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class HyMt2TranslatorTest {

    @Test
    fun normalizeTargetLang_defaultsBlankAndAutoToSimplifiedChinese() {
        data class Case(
            val targetLang: String,
            val expected: String,
        )

        val cases = listOf(
            Case("zh-CN", "zh-CN"),
            Case(" zh-TW ", "zh-TW"),
            Case("en", "en"),
            Case("auto", "zh-CN"),
            Case(" AUTO ", "zh-CN"),
            Case("", "zh-CN"),
            Case("   ", "zh-CN"),
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                HyMt2Translator.normalizeTargetLang(case.targetLang)
            )
        }
    }
}
