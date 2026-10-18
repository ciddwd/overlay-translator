package com.gameocr.app.ui

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.llm.LocalModelCatalog
import com.gameocr.app.llm.LocalModelConfig
import com.gameocr.app.llm.LocalModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelTrialPolicyTest {
    private fun entry(kind: LlmModelKind?, id: String = kind?.let(LocalModelCatalog::builtInId) ?: "imported:x") =
        LocalModelEntry(id, "m", kind, null, 0, "", "", "", LocalModelConfig())

    @Test
    fun trialRoutesToTheModelWithAUsableLanguagePair_tableDriven() {
        data class Case(val kind: LlmModelKind?, val source: String, val target: String,
            val engine: TranslatorEngine, val expectedSource: String, val expectedTarget: String, val localId: String?)
        listOf(
            Case(LlmModelKind.SAKURA_1_5B_Q4, "en", "en", TranslatorEngine.LOCAL_SAKURA, "ja", "zh-CN", null),
            Case(LlmModelKind.HY_MT2_1_8B_Q4_K_M, "ko", "zh-CN", TranslatorEngine.LOCAL_HY_MT2, "ko", "zh-CN", null),
            Case(LlmModelKind.HY_MT2_1_8B_Q4_K_M, "auto", "zh-CN", TranslatorEngine.LOCAL_HY_MT2, "ja", "zh-CN", null),
            Case(null, "fr", "ja", TranslatorEngine.LOCAL_QWEN3, "en", "ja", "imported:x"),
            Case(null, "auto", "ja", TranslatorEngine.LOCAL_QWEN3, "en", "ja", "imported:x"),
        ).forEach { case ->
            val settings = ModelTrialPolicy.settings(
                Settings(sourceLang = case.source, targetLang = case.target, translatorEngine = TranslatorEngine.OPENAI),
                entry(case.kind))
            assertEquals(case.toString(), case.engine, settings.translatorEngine)
            assertEquals(case.toString(), case.expectedSource, settings.sourceLang)
            assertEquals(case.toString(), case.expectedTarget, settings.targetLang)
            assertEquals(case.toString(), case.localId, settings.localModelId)
            assertNotEquals(case.toString(), settings.sourceLang, settings.targetLang)
        }
    }

    @Test
    fun realTextKeepsTheUserLanguageAndAlwaysRunsTheModel() {
        val base = Settings(sourceLang = "auto", targetLang = "zh-CN", translationMemoryEnabled = true)
        val real = ModelTrialPolicy.settings(base, entry(LlmModelKind.HY_MT2_1_8B_Q4_K_M), usesSample = false)
        assertEquals("auto", real.sourceLang)
        // Cache and translation memory would answer instantly without running the model.
        assertEquals(false, com.gameocr.app.translate.TranslationCachePolicy.isEnabled(real))
        assertEquals(false, real.translationMemoryEnabled)
        val sakura = ModelTrialPolicy.settings(base, entry(LlmModelKind.SAKURA_1_5B_Q4), usesSample = false)
        assertEquals("ja" to "zh-CN", sakura.sourceLang to sakura.targetLang)
    }

    @Test
    fun recentSourceText_keepsTheLatestNonBlankTextBounded() {
        val recent = com.gameocr.app.translate.RecentSourceText()
        assertEquals(null, recent.latest)
        recent.record(listOf(" 「待って！」 ", "", "「どこへ行くの？」"))
        assertEquals("「待って！」\n「どこへ行くの？」", recent.latest)
        recent.record(listOf("  ", ""))
        assertEquals("blank frames keep the previous text", "「待って！」\n「どこへ行くの？」", recent.latest)
        recent.record(listOf("あ".repeat(1000)))
        assertEquals(300, recent.latest!!.length)
    }

    @Test
    fun unknownLanguagesFallBackToTheEnglishSample() {
        assertEquals(ModelTrialPolicy.sample("en"), ModelTrialPolicy.sample("fr"))
        assertNotEquals(ModelTrialPolicy.sample("en"), ModelTrialPolicy.sample("ja"))
    }
}
