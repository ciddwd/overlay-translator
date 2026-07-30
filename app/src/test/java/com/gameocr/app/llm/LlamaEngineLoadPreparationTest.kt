package com.gameocr.app.llm

import com.arm.aichat.InferenceEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class LlamaEngineLoadPreparationTest {

    @Test
    fun `load preparation normalizes every relevant engine state`() {
        data class Case(
            val name: String,
            val state: InferenceEngine.State,
            val expected: LlamaEngineLoadPreparation,
        )

        listOf(
            Case(
                "cold engine loads normally",
                InferenceEngine.State.Initialized,
                LlamaEngineLoadPreparation.LOAD,
            ),
            Case(
                "untracked ready engine resets before loading",
                InferenceEngine.State.ModelReady,
                LlamaEngineLoadPreparation.RESET_THEN_LOAD,
            ),
            Case(
                "failed engine resets before retry",
                InferenceEngine.State.Error(IllegalStateException("failed")),
                LlamaEngineLoadPreparation.RESET_THEN_LOAD,
            ),
            Case(
                "native initialization remains queued",
                InferenceEngine.State.Initializing,
                LlamaEngineLoadPreparation.LOAD,
            ),
            Case(
                "in-flight generation is not synchronously cleaned",
                InferenceEngine.State.Generating,
                LlamaEngineLoadPreparation.LOAD,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                llamaEngineLoadPreparation(case.state),
            )
        }
    }
}
