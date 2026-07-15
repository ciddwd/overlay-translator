package com.gameocr.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLlmSamplingPolicyTest {

    @Test
    fun modelProfiles_coverEveryLocalModelKind() {
        data class Case(
            val name: String,
            val kind: LlmModelKind,
            val expected: LocalLlmSamplingConfig,
        )

        val cases = listOf(
            Case(
                name = "Sakura follows official anti-degeneration settings",
                kind = LlmModelKind.SAKURA_1_5B_Q4,
                expected = LocalLlmSamplingConfig(
                    temperature = 0.1f,
                    topP = 0.3f,
                    frequencyPenalty = 0.1f,
                ),
            ),
            Case(
                name = "Hy-MT preserves the existing native defaults",
                kind = LlmModelKind.HY_MT2_1_8B_Q4_K_M,
                expected = LocalLlmSamplingConfig(
                    temperature = 0.3f,
                    topP = 0.95f,
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
                    LocalLlmSamplingPolicy.TEMPERATURE_ENV to case.expected.temperature.toString(),
                    LocalLlmSamplingPolicy.TOP_P_ENV to case.expected.topP.toString(),
                    LocalLlmSamplingPolicy.FREQUENCY_PENALTY_ENV to case.expected.frequencyPenalty.toString(),
                ),
                LocalLlmSamplingPolicy.nativeEnvironment(case.kind),
            )
        }
    }
}
