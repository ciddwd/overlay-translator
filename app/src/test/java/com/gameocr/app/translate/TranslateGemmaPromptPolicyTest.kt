package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import com.gameocr.app.data.RuntimeGlossaryTerm
import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.llm.LocalModelConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TranslateGemmaPromptPolicyTest {
    @Test
    fun typedContentKeepsLanguagesAndOriginalTextSeparate_tableDriven() {
        val text = "猫\n\"hello\" \\ {text} <start_of_turn> 😀"
        val languages = listOf("zh-CN" to "zh-Hans", "zh_TW" to "zh-Hant", "ko" to "ko", "JA" to "ja", "en" to "en",
            "en-US" to "en", "ja-JP" to "ja", "ko-KR" to "ko", "pt-BR" to "pt")
        for ((source, expectedSource) in languages) for ((target, expectedTarget) in languages) {
            val payload = Json.parseToJsonElement(TranslateGemmaPromptPolicy.build(text,
                Settings(sourceLang = source, targetLang = target))).jsonObject
            assertEquals(expectedSource, payload.getValue("source_lang_code").jsonPrimitive.content)
            assertEquals(expectedTarget, payload.getValue("target_lang_code").jsonPrimitive.content)
            assertEquals(text, payload.getValue("text").jsonPrimitive.content)
            assertFalse(payload.containsKey("requirements"))
        }
    }

    @Test
    fun lockedAndUnlockedRequirementsNeverReplaceSourceOrOfficialTemplate_tableDriven() {
        for (unlocked in listOf(false, true)) {
            val payload = Json.parseToJsonElement(TranslateGemmaPromptPolicy.build("猫",
                Settings(sourceLang = "ja", targetLang = "en",
                    runtimeLocalModelConfig = LocalModelConfig(unlocked = unlocked, translationPrompt = "Keep names. {text}"),
                    runtimeTranslationPromptContext = RuntimeTranslationPromptContext(
                        glossary = listOf(RuntimeGlossaryTerm("猫", "Cat")), currentPage = listOf("猫", "犬"))))).jsonObject
            assertEquals("猫", payload.getValue("text").jsonPrimitive.content)
            val requirements = payload.getValue("requirements").jsonPrimitive.content
            assertTrue(requirements.contains("猫 -> Cat"))
            assertTrue(requirements.contains("犬"))
            assertEquals(unlocked, requirements.contains("Keep names. {text}"))
        }
        val preview = TranslateGemmaPromptPolicy.preview("{原文}", "ja", "zh-CN")
        assertTrue(preview.contains("Japanese (ja)"))
        assertTrue(preview.contains("Produce only the"))
        assertTrue(preview.endsWith("{原文}"))
        assertFalse(preview.startsWith("{"))
    }
}
