package com.gameocr.app.translate

import com.gameocr.app.llm.LlmModelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalLlamaTranslationCacheKeyTest {

    @Test
    fun build_stableForWhitespaceAndLanguageCase() {
        val cache = TranslationCache()
        val first = LocalLlamaTranslationCacheKey.build(
            cache = cache,
            source = "  こんにちは  ",
            modelKind = LlmModelKind.SAKURA_1_5B_Q4,
            sourceLang = " JA ",
            targetLang = " ZH-CN ",
            maxNewTokens = 256,
            systemPrompt = "system",
            userPrompt = "translate: こんにちは",
        )
        val second = LocalLlamaTranslationCacheKey.build(
            cache = cache,
            source = "こんにちは",
            modelKind = LlmModelKind.SAKURA_1_5B_Q4,
            sourceLang = "ja",
            targetLang = "zh-cn",
            maxNewTokens = 256,
            systemPrompt = "system",
            userPrompt = "translate: こんにちは",
        )

        assertEquals(first, second)
    }

    @Test
    fun build_splitsSemanticallyDifferentLocalRequests_tableDriven() {
        val cache = TranslationCache()
        val baseline = key(cache, Case(name = "baseline"))
        val cases = listOf(
            Case(name = "source text", source = "こんばんは"),
            Case(name = "model kind", modelKind = LlmModelKind.HY_MT2_1_8B_Q4_K_M),
            Case(name = "source language", sourceLang = "en"),
            Case(name = "target language", targetLang = "zh-TW"),
            Case(name = "max new tokens", maxNewTokens = 128),
            Case(name = "system prompt", systemPrompt = "other system"),
            Case(name = "no system prompt", systemPrompt = null),
            Case(name = "user prompt", userPrompt = "Translate only: こんにちは"),
        )

        cases.forEach { case ->
            assertNotEquals(case.name, baseline, key(cache, case))
        }
    }

    private fun key(cache: TranslationCache, case: Case): String =
        LocalLlamaTranslationCacheKey.build(
            cache = cache,
            source = case.source,
            modelKind = case.modelKind,
            sourceLang = case.sourceLang,
            targetLang = case.targetLang,
            maxNewTokens = case.maxNewTokens,
            systemPrompt = case.systemPrompt,
            userPrompt = case.userPrompt,
        )

    private data class Case(
        val name: String,
        val source: String = "こんにちは",
        val modelKind: LlmModelKind = LlmModelKind.SAKURA_1_5B_Q4,
        val sourceLang: String = "ja",
        val targetLang: String = "zh-CN",
        val maxNewTokens: Int = 256,
        val systemPrompt: String? = "system",
        val userPrompt: String = "translate: こんにちは",
    )
}
