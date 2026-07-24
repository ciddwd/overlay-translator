package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationLanguagePairPolicyTest {

    @Test
    fun conflict_tableDriven_comparesNormalizedLanguageCodes() {
        data class Case(
            val name: String,
            val source: String,
            val target: String,
            val expected: Boolean,
        )

        listOf(
            Case("same language", "ja", "ja", true),
            Case("case and whitespace are ignored", " JA ", "ja", true),
            Case("different regional variants remain distinct", "zh-CN", "zh-TW", false),
            Case("automatic source differs from concrete target", "auto", "zh-CN", false),
            Case("both automatic conflict", "auto", "AUTO", true),
            Case("blank source is not a language", "", "en", false),
            Case("blank target is not a language", "en", " ", false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                translationLanguageCodesConflict(case.source, case.target),
            )
        }
    }
}
