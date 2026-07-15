package com.gameocr.app.llm

internal data class LocalLlmSamplingConfig(
    val temperature: Float,
    val topP: Float,
    val frequencyPenalty: Float,
)

internal object LocalLlmSamplingPolicy {
    const val TEMPERATURE_ENV = "GAMEOCR_SAMPLER_TEMPERATURE"
    const val TOP_P_ENV = "GAMEOCR_SAMPLER_TOP_P"
    const val FREQUENCY_PENALTY_ENV = "GAMEOCR_SAMPLER_FREQUENCY_PENALTY"

    fun forModel(kind: LlmModelKind): LocalLlmSamplingConfig = when (kind) {
        LlmModelKind.SAKURA_1_5B_Q4 -> LocalLlmSamplingConfig(
            temperature = 0.1f,
            topP = 0.3f,
            frequencyPenalty = 0.1f,
        )

        LlmModelKind.HY_MT2_1_8B_Q4_K_M -> LocalLlmSamplingConfig(
            temperature = 0.3f,
            topP = 0.95f,
            frequencyPenalty = 0.0f,
        )
    }

    fun nativeEnvironment(kind: LlmModelKind): Map<String, String> = forModel(kind).let { config ->
        mapOf(
            TEMPERATURE_ENV to config.temperature.toString(),
            TOP_P_ENV to config.topP.toString(),
            FREQUENCY_PENALTY_ENV to config.frequencyPenalty.toString(),
        )
    }
}
