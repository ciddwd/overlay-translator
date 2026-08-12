package com.gameocr.app.ui

import com.gameocr.app.data.TranslatorEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSakuraLanguageFallbackPolicyTest {
    @Test
    fun languageChanges_keepTheChosenLanguageAndRouteUnsupportedSakuraPairsToHyMt2_tableDriven() {
        data class Case(
            val name: String,
            val engine: TranslatorEngine,
            val source: String,
            val target: String,
            val expected: TranslatorEngine,
        )

        listOf(
            Case("supported Japanese manga pair keeps Sakura", TranslatorEngine.LOCAL_SAKURA, "ja", "zh-CN", TranslatorEngine.LOCAL_SAKURA),
            Case("Korean source keeps language and selects Hy-MT2", TranslatorEngine.LOCAL_SAKURA, "ko", "zh-CN", TranslatorEngine.LOCAL_HY_MT2),
            Case("Japanese to English selects Hy-MT2", TranslatorEngine.LOCAL_SAKURA, "ja", "en", TranslatorEngine.LOCAL_HY_MT2),
            Case("existing Hy-MT2 is unchanged", TranslatorEngine.LOCAL_HY_MT2, "en", "zh-CN", TranslatorEngine.LOCAL_HY_MT2),
            Case("cloud engine is unchanged", TranslatorEngine.OPENAI, "fr", "zh-CN", TranslatorEngine.OPENAI),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                translationEngineAfterLanguageChange(case.engine, case.source, case.target),
            )
        }
    }
}
