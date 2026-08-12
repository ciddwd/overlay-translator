package com.gameocr.app.llm

internal data class LocalLlmSamplingConfig(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val repeatPenalty: Float,
    val frequencyPenalty: Float,
)

internal object LocalLlmSamplingPolicy {
    const val TEMPERATURE_ENV = "GAMEOCR_SAMPLER_TEMPERATURE"
    const val TOP_P_ENV = "GAMEOCR_SAMPLER_TOP_P"
    const val TOP_K_ENV = "GAMEOCR_SAMPLER_TOP_K"
    const val REPEAT_PENALTY_ENV = "GAMEOCR_SAMPLER_REPEAT_PENALTY"
    const val FREQUENCY_PENALTY_ENV = "GAMEOCR_SAMPLER_FREQUENCY_PENALTY"

    fun forModel(kind: LlmModelKind): LocalLlmSamplingConfig = when (kind) {
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
    }

    fun nativeEnvironment(kind: LlmModelKind): Map<String, String> = forModel(kind).let { config ->
        mapOf(
            TEMPERATURE_ENV to config.temperature.toString(),
            TOP_P_ENV to config.topP.toString(),
            TOP_K_ENV to config.topK.toString(),
            REPEAT_PENALTY_ENV to config.repeatPenalty.toString(),
            FREQUENCY_PENALTY_ENV to config.frequencyPenalty.toString(),
        )
    }
}
