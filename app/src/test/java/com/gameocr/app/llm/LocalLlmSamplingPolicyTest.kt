package com.gameocr.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLlmSamplingPolicyTest {

    @Test
    fun specializedModels_ignoreStoredSamplingUntilUnlocked() {
        val overrides = LocalModelConfig(temperature = 0.4f, topP = 0.9f, topK = 10, repeatPenalty = 1.2f)
        for (kind in LlmModelKind.entries.filter { it.specialized }) {
            val defaults = LocalLlmSamplingPolicy.forModel(kind)
            assertEquals(kind.name, defaults, LocalLlmSamplingPolicy.configured(kind, overrides))
            val unlocked = LocalLlmSamplingPolicy.configured(kind, overrides.copy(unlocked = true))
            assertEquals(kind.name, defaults.copy(temperature = 0.4f, topP = 0.9f, topK = 10, repeatPenalty = 1.2f), unlocked)
            // Unlocked with blank fields keeps the official values.
            assertEquals(kind.name, defaults, LocalLlmSamplingPolicy.configured(kind, LocalModelConfig(unlocked = true)))
        }
    }

    @Test
    fun importedModels_useNeutralDefaultsNotThePresetProfile() {
        val kind = LlmModelKind.QWEN3_0_6B_Q8_0
        assertEquals(LocalLlmSamplingPolicy.IMPORTED_DEFAULTS, LocalLlmSamplingPolicy.forModel(kind, imported = true))
        assertEquals(LocalLlmSamplingPolicy.IMPORTED_DEFAULTS, LocalLlmSamplingPolicy.configured(kind, null, imported = true))
        assertEquals(0f, LocalLlmSamplingPolicy.forModel(kind, imported = true).presencePenalty)
        assertEquals("0.4", LocalLlmSamplingPolicy.nativeEnvironment(kind,
            LocalModelConfig(temperature = 0.4f), imported = true)[LocalLlmSamplingPolicy.TEMPERATURE_ENV])
    }

    @Test
    fun perModelOverrides_keepDefaultsAndNormalizeBoundaries_tableDriven() {
        for ((kind, imported) in LlmModelKind.entries.filterNot { it.specialized }.map { it to false } +
            (LlmModelKind.QWEN3_0_6B_Q8_0 to true)) {
            val defaults = LocalLlmSamplingPolicy.forModel(kind, imported)
            assertEquals(defaults, LocalLlmSamplingPolicy.configured(kind, null, imported))
            assertEquals(defaults, LocalLlmSamplingPolicy.configured(kind, LocalModelConfig(), imported))
            val cases = listOf(
                LocalModelConfig(temperature = 0.4f, topP = 0.9f, topK = 10, repeatPenalty = 1.2f) to
                    listOf("0.4", "0.9", "10", "1.2"),
                LocalModelConfig(temperature = -1f, topP = 2f, topK = -1, repeatPenalty = 9f) to
                    listOf("0.0", "1.0", "0", "2.0"),
            )
            for ((overrides, expected) in cases) {
                val actual = LocalLlmSamplingPolicy.nativeEnvironment(kind, overrides, imported)
                assertEquals(expected, listOf(actual[LocalLlmSamplingPolicy.TEMPERATURE_ENV],
                    actual[LocalLlmSamplingPolicy.TOP_P_ENV], actual[LocalLlmSamplingPolicy.TOP_K_ENV],
                    actual[LocalLlmSamplingPolicy.REPEAT_PENALTY_ENV]))
                assertEquals(defaults.frequencyPenalty.toString(), actual[LocalLlmSamplingPolicy.FREQUENCY_PENALTY_ENV])
                assertEquals(if (kind.usesGgufTemplate) "1" else "0", actual["GAMEOCR_GGUF_TEMPLATE"])
            }
        }
    }

    @Test
    fun modelProfiles_coverEveryLocalModelKind() {
        data class Case(
            val name: String,
            val kind: LlmModelKind,
            val expected: LocalLlmSamplingConfig,
        )

        val cases = listOf(
            Case("Qwen non-thinking profile", LlmModelKind.QWEN3_0_6B_Q8_0,
                LocalLlmSamplingConfig(0.7f, 0.8f, 20, 1f, 0f, 0f, 1.5f)),
            Case("Index uses official greedy decoding", LlmModelKind.INDEX_TRANSLATE_2B_Q4_K_M,
                LocalLlmSamplingConfig(0f, 1f, 0, 1f, 0f)),
            Case(
                name = "Sakura follows official anti-degeneration settings",
                kind = LlmModelKind.SAKURA_1_5B_Q4,
                expected = LocalLlmSamplingConfig(
                    temperature = 0.1f,
                    topP = 0.3f,
                    topK = 40,
                    repeatPenalty = 1.0f,
                    frequencyPenalty = 0.1f,
                ),
            ),
            Case(
                name = "Hy-MT preserves the existing native defaults",
                kind = LlmModelKind.HY_MT2_1_8B_Q4_K_M,
                expected = LocalLlmSamplingConfig(
                    temperature = 0.7f,
                    topP = 0.6f,
                    topK = 20,
                    repeatPenalty = 1.05f,
                    frequencyPenalty = 0.0f,
                ),
            ),
        )

        cases.forEach { case ->
            val actual = LocalLlmSamplingPolicy.forModel(case.kind)
            assertEquals(case.name, case.expected, actual)
            assertEquals(
                case.name,
                mapOf(
                    "GAMEOCR_GGUF_TEMPLATE" to if (case.kind.usesGgufTemplate) "1" else "0",
                    "GAMEOCR_TRANSLATEGEMMA" to if (case.kind == LlmModelKind.TRANSLATEGEMMA_4B_Q4_K_M) "1" else "0",
                    "GAMEOCR_SAMPLER_MIN_P" to case.expected.minP.toString(),
                    "GAMEOCR_SAMPLER_PRESENCE_PENALTY" to case.expected.presencePenalty.toString(),
                    LocalLlmSamplingPolicy.TEMPERATURE_ENV to case.expected.temperature.toString(),
                    LocalLlmSamplingPolicy.TOP_P_ENV to case.expected.topP.toString(),
                    LocalLlmSamplingPolicy.TOP_K_ENV to case.expected.topK.toString(),
                    LocalLlmSamplingPolicy.REPEAT_PENALTY_ENV to case.expected.repeatPenalty.toString(),
                    LocalLlmSamplingPolicy.FREQUENCY_PENALTY_ENV to case.expected.frequencyPenalty.toString(),
                ),
                LocalLlmSamplingPolicy.nativeEnvironment(case.kind),
            )
        }
    }
}
