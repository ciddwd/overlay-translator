package com.gameocr.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLlmThreadPolicyTest {

    @Test
    fun select_coversAbVariantsAndDeviceCaps() {
        data class Case(
            val name: String,
            val availableProcessors: Int,
            val requestedGenerationThreads: Int,
            val expectedGenerationThreads: Int,
            val expectedPromptThreads: Int,
        )

        val cases = listOf(
            Case("invalid cores clamp both to one", 0, 4, 1, 1),
            Case("dual core caps TG and PP", 2, 6, 2, 2),
            Case("four cores keep two-thread policy", 4, 4, 2, 2),
            Case("six cores cap requested TG6 to four", 6, 6, 4, 4),
            Case("eight cores TG4 PP6 experiment", 8, 4, 4, 6),
            Case("eight cores TG6 PP6 control", 8, 6, 6, 6),
            Case("invalid requested TG clamps to one", 8, 0, 1, 6),
            Case("oversized requested TG caps at PP", 12, 99, 6, 6),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                LocalLlmThreadConfig(case.expectedGenerationThreads, case.expectedPromptThreads),
                LocalLlmThreadPolicy.select(
                    case.availableProcessors,
                    case.requestedGenerationThreads,
                ),
            )
        }
    }

    @Test
    fun nativeEnvironment_mapsTgAndPpIndependently() {
        val environment = LocalLlmThreadPolicy.nativeEnvironment(
            LocalLlmThreadConfig(generationThreads = 4, promptThreads = 6),
        )

        assertEquals(
            linkedMapOf(
                "GAMEOCR_GENERATION_THREADS" to "4",
                "GAMEOCR_PROMPT_THREADS" to "6",
            ),
            environment,
        )
    }
}
