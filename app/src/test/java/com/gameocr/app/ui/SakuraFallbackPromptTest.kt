package com.gameocr.app.ui

import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.llm.LlmModelKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SakuraFallbackPromptTest {

    @Test
    fun supportsSakuraSource_acceptsOnlyJapaneseSourceCodes() {
        data class Case(
            val sourceLang: String,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("ja", true),
            Case("ja-JP", true),
            Case(" ja ", true),
            Case("auto", false),
            Case("en", false),
            Case("zh-CN", false),
            Case("", false),
        )

        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, supportsSakuraSource(case.sourceLang))
        }
    }

    @Test
    fun supportsSakuraTarget_acceptsOnlySimplifiedChineseTargetCode() {
        data class Case(
            val targetLang: String,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("zh-CN", true),
            Case(" zh-cn ", true),
            Case("zh-TW", false),
            Case("zh", false),
            Case("auto", false),
            Case("en", false),
            Case("", false),
        )

        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, supportsSakuraTarget(case.targetLang))
        }
    }

    @Test
    fun sakuraLanguageIssue_reportsUnsupportedSourceAndTargetIndependently() {
        data class Case(
            val name: String,
            val sourceLang: String,
            val targetLang: String,
            val expected: SakuraLanguageIssue,
        )

        val cases = listOf(
            Case(
                name = "supported pair",
                sourceLang = "ja",
                targetLang = "zh-CN",
                expected = SakuraLanguageIssue(sourceUnsupported = false, targetUnsupported = false),
            ),
            Case(
                name = "unsupported source only",
                sourceLang = "en",
                targetLang = "zh-CN",
                expected = SakuraLanguageIssue(sourceUnsupported = true, targetUnsupported = false),
            ),
            Case(
                name = "unsupported target only",
                sourceLang = "ja",
                targetLang = "zh-TW",
                expected = SakuraLanguageIssue(sourceUnsupported = false, targetUnsupported = true),
            ),
            Case(
                name = "unsupported source and target",
                sourceLang = "auto",
                targetLang = "en",
                expected = SakuraLanguageIssue(sourceUnsupported = true, targetUnsupported = true),
            ),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, sakuraLanguageIssue(case.sourceLang, case.targetLang))
            assertEquals(case.name, !case.expected.sourceUnsupported && !case.expected.targetUnsupported, supportsSakuraLanguagePair(case.sourceLang, case.targetLang))
        }
    }

    @Test
    fun missingOpenAiFallbackFields_reportsActionableMissingConfig() {
        data class Case(
            val name: String,
            val baseUrl: String,
            val apiKey: String,
            val model: String,
            val expected: List<OpenAiFallbackField>,
        )

        val cases = listOf(
            Case("ready", "https://api.example/v1/", "sk-test", "model", emptyList()),
            Case("missing api key", "https://api.example/v1/", "", "model", listOf(OpenAiFallbackField.API_KEY)),
            Case("missing model", "https://api.example/v1/", "sk-test", "", listOf(OpenAiFallbackField.MODEL)),
            Case("missing base url", "", "sk-test", "model", listOf(OpenAiFallbackField.BASE_URL)),
            Case(
                "missing all",
                "",
                "",
                "",
                listOf(OpenAiFallbackField.BASE_URL, OpenAiFallbackField.API_KEY, OpenAiFallbackField.MODEL),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                missingOpenAiFallbackFields(case.baseUrl, case.apiKey, case.model)
            )
        }
    }

    @Test
    fun localLlmEngineMapping_coversCurrentLocalModels() {
        data class Case(
            val engine: TranslatorEngine,
            val expectedLocal: Boolean,
            val expectedKind: LlmModelKind?,
        )

        val cases = listOf(
            Case(TranslatorEngine.LOCAL_SAKURA, true, LlmModelKind.SAKURA_1_5B_Q4),
            Case(TranslatorEngine.LOCAL_HY_MT2, true, LlmModelKind.HY_MT2_1_8B_Q4_K_M),
            Case(TranslatorEngine.OPENAI, false, null),
            Case(TranslatorEngine.DEEPL, false, null),
            Case(TranslatorEngine.TENCENT, false, null),
        )

        cases.forEach { case ->
            assertEquals(case.engine.name, case.expectedLocal, isLocalLlmEngine(case.engine))
            assertEquals(case.engine.name, case.expectedKind, localLlmModelKindFor(case.engine))
        }
    }
}
