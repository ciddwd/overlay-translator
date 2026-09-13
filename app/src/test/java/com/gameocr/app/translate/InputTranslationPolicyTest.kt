package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.data.OpenAiRequestOptions
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTranslationPolicyTest {

    @Test
    fun rejectedCases_areTableDriven() {
        data class Case(
            val name: String,
            val text: String,
            val settings: Settings,
            val expected: InputTranslationPreparationError,
        )

        listOf(
            Case("blank", "  ", Settings(), InputTranslationPreparationError.EMPTY_INPUT),
            Case(
                "automatic output",
                "hello",
                Settings(sourceLang = "auto", targetLang = "en"),
                InputTranslationPreparationError.AUTO_TARGET,
            ),
            Case(
                "image translator",
                "hello",
                Settings(
                    sourceLang = "zh-CN",
                    targetLang = "en",
                    translatorEngine = TranslatorEngine.YOUDAO_PICTRANS,
                ),
                InputTranslationPreparationError.UNSUPPORTED_ENGINE,
            ),
            Case(
                "mlkit cannot reverse from auto",
                "hello",
                Settings(
                    sourceLang = "zh-CN",
                    targetLang = "auto",
                    translatorEngine = TranslatorEngine.GOOGLE_ML_KIT,
                ),
                InputTranslationPreparationError.UNSUPPORTED_ENGINE,
            ),
            Case(
                "sakura unsupported reverse language",
                "hello",
                Settings(
                    sourceLang = "fr",
                    targetLang = "en",
                    translatorEngine = TranslatorEngine.LOCAL_SAKURA,
                ),
                InputTranslationPreparationError.UNSUPPORTED_ENGINE,
            ),
        ).forEach { case ->
            val actual = InputTranslationPolicy.prepare(case.text, case.settings)
            assertEquals(case.name, case.expected, (actual as InputTranslationPreparation.Rejected).error)
        }
    }

    @Test
    fun readyCases_reverseLanguagePairAndRemoveScreenContext() {
        data class Case(val name: String, val engine: TranslatorEngine)

        listOf(
            Case("OpenAI compatible", TranslatorEngine.OPENAI),
            Case("Anthropic compatible", TranslatorEngine.ANTHROPIC),
            Case("Hy-MT2", TranslatorEngine.LOCAL_HY_MT2),
            Case("ML Kit", TranslatorEngine.GOOGLE_ML_KIT),
        ).forEach { case ->
            val original = Settings(
                sourceLang = "ja",
                targetLang = "zh-CN",
                translatorEngine = case.engine,
                translationContextMode = TranslationContextMode.CONTINUOUS_CONTEXT,
                openAiRequestOptions = OpenAiRequestOptions(sendScreenImage = true),
                runtimeTranslationContext = "page context",
                runtimeTranslationPromptContext = RuntimeTranslationPromptContext(
                    currentPage = listOf("page text"),
                ),
            )

            val ready = InputTranslationPolicy.prepare("翻译我", original)
                as InputTranslationPreparation.Ready

            assertEquals(case.name, "翻译我", ready.sourceText)
            assertEquals(case.name, "zh-CN", ready.requestSettings.sourceLang)
            assertEquals(case.name, "ja", ready.requestSettings.targetLang)
            assertEquals(case.name, TranslationContextMode.FAST_PER_SEGMENT, ready.requestSettings.translationContextMode)
            assertFalse(case.name, ready.requestSettings.openAiRequestOptions.sendScreenImage)
            assertTrue(case.name, ready.requestSettings.runtimeTranslationContext.isEmpty())
            assertTrue(case.name, ready.requestSettings.runtimeTranslationPromptContext.currentPage.isEmpty())
            assertNull(case.name, ready.requestSettings.runtimeTranslationVisualContext)
        }
    }
}
