package com.gameocr.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalModelInitializationPolicyTest {
    @Test
    fun environment_normalizesEveryAllocationOptionAndDoesNotOverrideThreadPolicy() {
        data class Case(val config: LocalModelConfig, val expected: Map<String, String>)
        val defaults = mapOf(
            "GAMEOCR_CONTEXT_TOKENS" to "2048", "GAMEOCR_BATCH_TOKENS" to "512",
            "GAMEOCR_MICRO_BATCH_TOKENS" to "512", "GAMEOCR_LOAD_MODE" to "auto",
            "GAMEOCR_CACHE_TYPE_K" to "f16",
            "GAMEOCR_CACHE_TYPE_V" to "f16", "GAMEOCR_FLASH_ATTENTION" to "auto",
            "GAMEOCR_GPU_LAYERS" to "0",
        )
        listOf(
            Case(LocalModelConfig(), defaults),
            Case(LocalModelConfig(contextTokens = 512, batchTokens = 9000, microBatchTokens = 0,
                loadMode = " MLOCK ", cacheTypeK = "Q8_0", cacheTypeV = "q4_0",
                flashAttention = "off", gpuLayers = -9), defaults + mapOf(
                "GAMEOCR_CONTEXT_TOKENS" to "512", "GAMEOCR_MICRO_BATCH_TOKENS" to "32",
                "GAMEOCR_LOAD_MODE" to "mlock",
                "GAMEOCR_CACHE_TYPE_K" to "q8_0", "GAMEOCR_FLASH_ATTENTION" to "off",
                "GAMEOCR_GPU_LAYERS" to "-1",
            )),
            Case(LocalModelConfig(loadMode = "unknown"), defaults),
            Case(LocalModelConfig(generationThreads = 8, promptThreads = 16, temperature = 0.5f,
                translationPrompt = "Translate", idleUnloadMinutes = 0), defaults),
        ).forEach { case ->
            val actual = LocalModelInitializationPolicy.nativeEnvironment(case.config)
            assertEquals(case.expected, actual)
            assertFalse(actual.containsKey("GAMEOCR_GENERATION_THREADS"))
            assertFalse(actual.containsKey("GAMEOCR_PROMPT_THREADS"))
        }
    }
}
