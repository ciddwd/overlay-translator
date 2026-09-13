package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import java.io.File
import org.junit.Test

class MlKitRecentSourceLanguageTest {
    @Test
    fun recentHistoryIsRecordedOnSelectionWithoutTheSaveButton() {
        val screen = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val viewModel = File("src/main/java/com/gameocr/app/ui/SettingsViewModel.kt").readText()
        for ((start, end, argument) in listOf(
            Triple("fun selectMlKitSourceLanguage(", "fun swapSelectedLanguages(", "languageTag"),
            Triple("fun swapSelectedLanguages(", "fun buildSnapshot(", "swapped.first"),
        )) {
            assertTrue(screen.substringAfter(start).substringBefore(end)
                .contains("viewModel.rememberMlKitSourceLanguage($argument)"))
        }
        assertFalse(screen.contains("mlKitRecentSourceLanguages = mlKitRecentSources"))
        val save = viewModel.substringAfter("suspend fun save(")
        assertFalse(save.contains("mlKitRecentSourceLanguages"))
        val remember = viewModel.substringAfter("internal fun rememberMlKitSourceLanguage(")
            .substringBefore("private val autoOcrSettingsSaver")
        assertTrue(remember.contains("viewModelScope.launch"))
        assertTrue(remember.contains("recentLanguageMutex.withLock"))
        assertTrue(remember.contains("repo.update { current ->"))
        assertTrue(remember.contains("current.mlKitRecentSourceLanguages, languageTag"))
    }

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
