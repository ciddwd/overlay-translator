package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitQuickSourceLanguageTest {
    @Test
    fun quickSources_tableDrivenMapToExplicitTranslationLanguages() {
        val expected = listOf(
            "LATIN" to "en",
            "CHINESE" to "zh-CN",
            "JAPANESE" to "ja",
            "KOREAN" to "ko",
        )

        assertEquals(
            expected,
            MlKitQuickSourceLanguage.entries.map { it.name to it.languageTag },
        )
        assertFalse(MlKitQuickSourceLanguage.entries.any { it.languageTag == "auto" })
    }

    @Test
    fun sourceMatching_tableDrivenSelectsOnlyItsVisibleEntry() {
        data class Case(
            val source: String,
            val expected: MlKitQuickSourceLanguage?,
        )

        val cases = listOf(
            Case("en", MlKitQuickSourceLanguage.LATIN),
            Case("en-US", MlKitQuickSourceLanguage.LATIN),
            Case("zh-TW", MlKitQuickSourceLanguage.CHINESE),
            Case("ja-JP", MlKitQuickSourceLanguage.JAPANESE),
            Case("ko-KR", MlKitQuickSourceLanguage.KOREAN),
            Case("auto", null),
            Case("es", null),
        )

        cases.forEach { case ->
            assertEquals(
                case.source,
                case.expected,
                MlKitQuickSourceLanguage.fromLanguageTag(case.source),
            )
        }
    }

    @Test
    fun settings_placesAllOnDeviceChoicesInLocalLlmGroupWithManualDownload() {
        val source = listOf(
            File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt"),
            File("app/src/main/java/com/gameocr/app/ui/SettingsScreen.kt"),
        ).first(File::isFile).readText()
        val start = source.indexOf("R.string.settings_translator_group_local_llm")
        val end = source.indexOf("R.string.settings_translator_group_cloud_llm", start)
        assertTrue("local on-device group", start >= 0 && end > start)
        val group = source.substring(start, end)

        assertTrue("four translation choices", group.contains("MlKitQuickSourceLanguage.entries"))
        assertTrue("Sakura shares the group", group.contains("TranslatorEngine.LOCAL_SAKURA"))
        assertTrue("HY-MT2 shares the group", group.contains("TranslatorEngine.LOCAL_HY_MT2"))
        assertFalse("no separate on-device translation heading", group.contains("settings_translator_group_on_device"))
        assertTrue(
            "manual model download action",
            source.contains("R.string.settings_mlkit_download_pair"),
        )
        assertTrue(
            "ready state replaces download action",
            source.contains("currentPairReady -> Text("),
        )
        assertTrue(
            "missing model dialog offers download",
            source.contains("startMlKitModelDownload(prompt.pair)"),
        )
        assertTrue(
            "missing model dialog offers cloud LLM switch",
            source.contains("R.string.mlkit_missing_models_dialog_switch_llm"),
        )
    }
}
