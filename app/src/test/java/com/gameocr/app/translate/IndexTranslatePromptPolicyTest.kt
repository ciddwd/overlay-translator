package com.gameocr.app.translate

import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.data.RuntimeGlossaryTerm
import com.gameocr.app.data.RuntimeDialogueTurn
import com.gameocr.app.llm.LlamaTemplateGeneration
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.ui.isLocalLlmEngine
import com.gameocr.app.ui.localLlmModelKindFor
import org.junit.Assert.*
import org.junit.Test

class IndexTranslatePromptPolicyTest {
    @Test fun genericPresetUsesBusinessPromptNotChatTokens_tableDriven() {
        for (source in listOf("Hello", "こんにちは\n안녕하세요", "{source_text}", "<think> is a literal tag")) {
            val prompt = GenericLocalPromptPolicy.build(source, com.gameocr.app.data.Settings(
                translatorEngine = TranslatorEngine.LOCAL_QWEN3, sourceLang = "auto", targetLang = "zh-CN"))
            assertTrue(prompt.startsWith("Translate the following text into 简体中文."))
            assertTrue(prompt.endsWith(source))
            assertFalse(prompt.contains("<|im_start|>"))
            assertFalse(prompt.contains("Source language:"))
        }
    }
    @Test fun structuredContextAndTrimming_tableDriven() {
        val context = RuntimeTranslationPromptContext(currentApplication = "Novel",
            glossary = listOf(RuntimeGlossaryTerm("猫", "cat")), currentPage = listOf("猫", "犬"),
            previousFrame = listOf(RuntimeDialogueTurn("昨日", "yesterday")))
        for (from in listOf("auto", "ja", "ko", "en")) {
            val prompt = IndexTranslatePromptPolicy.build("猫", "en", from, context)
            assertTrue(prompt.contains("【源文】\n猫"))
            assertTrue(prompt.contains("【硬性要求】专名/术语对照: 猫→cat"))
            assertTrue(prompt.contains("yesterday"))
            val trimmed = IndexTranslatePromptPolicy.build("猫", "en", from,
                context.copy(previousFrame = emptyList()))
            assertFalse(trimmed.contains("yesterday"))
            assertTrue(trimmed.contains("猫→cat"))
        }
    }

    @Test fun incompleteResultsNeverSucceed_tableDriven() {
        for (finish in listOf("complete", "output_limit", "input_limit", "cancelled", "parse_error", "empty", "error")) {
            for (text in listOf("translation", "", "  ")) {
                assertEquals("$finish/$text", finish == "complete" && text.isNotBlank(),
                    LlamaTemplateGeneration.Result(text, finish, 8).successful)
            }
        }
    }
    @Test fun targetsAndSourcePreservation_tableDriven() {
        for ((tag, name) in listOf("zh-CN" to "简体中文", "zh_TW" to "繁体中文",
            "en" to "英语", "ja" to "日语", "ko" to "韩语", "" to "简体中文", "auto" to "简体中文")) {
            for (source in listOf("hello", "こんにちは\n안녕하세요", "{name}: 42", "")) {
                val prompt = IndexTranslatePromptPolicy.build(source, tag)
                assertTrue(tag, prompt.startsWith("请将以下文本翻译为$name，"))
                assertTrue(prompt.endsWith("\n\n$source"))
                assertFalse(prompt.contains("<think>"))
            }
        }
    }

    @Test fun enginesRemainIndependentlySelectable_tableDriven() {
        for ((engine, kind) in listOf(
            TranslatorEngine.LOCAL_QWEN3 to LlmModelKind.QWEN3_0_6B_Q8_0,
            TranslatorEngine.LOCAL_INDEX_TRANSLATE to LlmModelKind.INDEX_TRANSLATE_2B_Q4_K_M,
            TranslatorEngine.LOCAL_HY_MT2 to LlmModelKind.HY_MT2_1_8B_Q4_K_M,
            TranslatorEngine.LOCAL_SAKURA to LlmModelKind.SAKURA_1_5B_Q4)) {
            assertTrue(isLocalLlmEngine(engine))
            assertEquals(kind, localLlmModelKindFor(engine))
            assertTrue(TranslationPromptContextPolicy.supportsContext(engine))
        }
        assertFalse(isLocalLlmEngine(TranslatorEngine.OPENAI))
    }
}
