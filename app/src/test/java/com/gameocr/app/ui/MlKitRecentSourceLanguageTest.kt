package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitRecentSourceLanguageTest {
    @Test
    fun recentSources_tableDriven_keepFourMostRecentlyUsedSupportedLanguages() {
        data class Case(
            val name: String,
            val stored: List<String>,
            val selected: String?,
            val expected: List<String>,
        )

        val defaults = listOf("en", "zh-CN", "ja", "ko")
        val cases = listOf(
            Case("empty storage uses defaults", emptyList(), null, defaults),
            Case("Russian moves to front", defaults, "ru", listOf("ru", "en", "zh-CN", "ja")),
            Case(
                "Korean moves ahead of Russian",
                listOf("ru", "en", "zh-CN", "ja"),
                "ko",
                listOf("ko", "ru", "en", "zh-CN"),
            ),
            Case(
                "unsupported and duplicate canonical languages are removed",
                listOf("yue", "zh-CN", "zh-TW", "en", "ja", "ko"),
                null,
                listOf("zh-CN", "en", "ja", "ko"),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                mlKitRecentSourceLanguages(case.stored, case.selected),
            )
        }
    }

    @Test
    fun downloadedPickerCodes_mapCanonicalModelsToVisibleLanguageOptions() {
        assertEquals(
            listOf("ru", "nb", "zh-CN", "zh-TW"),
            mlKitDownloadedPickerLanguageCodes(linkedSetOf("ru", "no", "zh")),
        )
    }
}
