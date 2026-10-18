package com.gameocr.app.llm

internal data class LocalLlmSamplingConfig(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val repeatPenalty: Float,
    val frequencyPenalty: Float,
    val minP: Float = 0.05f,
    val presencePenalty: Float = 0f,
)

internal object LocalLlmSamplingPolicy {
    const val TEMPERATURE_ENV = "GAMEOCR_SAMPLER_TEMPERATURE"
    const val TOP_P_ENV = "GAMEOCR_SAMPLER_TOP_P"
    const val TOP_K_ENV = "GAMEOCR_SAMPLER_TOP_K"
    const val REPEAT_PENALTY_ENV = "GAMEOCR_SAMPLER_REPEAT_PENALTY"
    const val FREQUENCY_PENALTY_ENV = "GAMEOCR_SAMPLER_FREQUENCY_PENALTY"

    /**
     * Imported GGUFs share the generic translator path with the Qwen3 preset, but not its
     * model-card sampling (notably presence penalty 1.5). Use neutral translation defaults.
     */
    val IMPORTED_DEFAULTS = LocalLlmSamplingConfig(
        temperature = 0.3f, topP = 0.9f, topK = 40, repeatPenalty = 1f, frequencyPenalty = 0f,
    )

    fun forModel(kind: LlmModelKind, imported: Boolean = false): LocalLlmSamplingConfig =
        if (imported) IMPORTED_DEFAULTS else forBuiltIn(kind)

    private fun forBuiltIn(kind: LlmModelKind): LocalLlmSamplingConfig = when (kind) {
        LlmModelKind.QWEN3_0_6B_Q8_0 -> LocalLlmSamplingConfig(
            temperature = 0.7f, topP = 0.8f, topK = 20, repeatPenalty = 1f,
            frequencyPenalty = 0f, minP = 0f, presencePenalty = 1.5f,
        )
        LlmModelKind.INDEX_TRANSLATE_2B_Q4_K_M -> LocalLlmSamplingConfig(
            temperature = 0f, topP = 1f, topK = 0, repeatPenalty = 1f, frequencyPenalty = 0f,
        )
        LlmModelKind.SAKURA_1_5B_Q4 -> LocalLlmSamplingConfig(
            temperature = 0.1f,
            topP = 0.3f,
            topK = 40,
            repeatPenalty = 1.0f,
            frequencyPenalty = 0.1f,
        )

        LlmModelKind.HY_MT2_1_8B_Q4_K_M -> LocalLlmSamplingConfig(
            temperature = 0.7f,
            topP = 0.6f,
            topK = 20,
            repeatPenalty = 1.05f,
            frequencyPenalty = 0.0f,
        )
        LlmModelKind.GEMMA4_E2B_Q4_0, LlmModelKind.GEMMA4_E4B_Q4_0 -> LocalLlmSamplingConfig(
            temperature = 1f, topP = 0.95f, topK = 64, repeatPenalty = 1f,
            frequencyPenalty = 0f, minP = 0f,
        )
        LlmModelKind.TRANSLATEGEMMA_4B_Q4_K_M -> LocalLlmSamplingConfig(
            temperature = 0f, topP = 1f, topK = 0, repeatPenalty = 1f,
            frequencyPenalty = 0f, minP = 0f,
        )
        // No translation-specific sampler is prescribed for these general-model presets.
        else -> IMPORTED_DEFAULTS
    }

    fun configured(kind: LlmModelKind, overrides: LocalModelConfig?, imported: Boolean = false): LocalLlmSamplingConfig {
        val base = forModel(kind, imported)
        val value = overrides?.normalized() ?: return base
        // Translation-tuned models ignore stored sampling until the user explicitly unlocks them.
        if (kind.specialized && !imported && !value.unlocked) return base
        return base.copy(
            temperature = value.temperature ?: base.temperature,
            topP = value.topP ?: base.topP,
            topK = value.topK ?: base.topK,
            repeatPenalty = value.repeatPenalty ?: base.repeatPenalty,
        )
    }

    fun nativeEnvironment(kind: LlmModelKind, overrides: LocalModelConfig? = null, imported: Boolean = false): Map<String, String> =
        configured(kind, overrides, imported).let { config ->
        mapOf(
            "GAMEOCR_GGUF_TEMPLATE" to if (kind.usesGgufTemplate) "1" else "0",
            "GAMEOCR_TRANSLATEGEMMA" to if (kind == LlmModelKind.TRANSLATEGEMMA_4B_Q4_K_M) "1" else "0",
            "GAMEOCR_SAMPLER_MIN_P" to config.minP.toString(),
            "GAMEOCR_SAMPLER_PRESENCE_PENALTY" to config.presencePenalty.toString(),
            TEMPERATURE_ENV to config.temperature.toString(),
            TOP_P_ENV to config.topP.toString(),
            TOP_K_ENV to config.topK.toString(),
            REPEAT_PENALTY_ENV to config.repeatPenalty.toString(),
            FREQUENCY_PENALTY_ENV to config.frequencyPenalty.toString(),
        )
    }
}
