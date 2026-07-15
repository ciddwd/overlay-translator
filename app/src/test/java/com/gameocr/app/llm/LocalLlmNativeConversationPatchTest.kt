package com.gameocr.app.llm

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmNativeConversationPatchTest {

    @Test
    fun buildPatch_coversIndependentRequestsAndExactGenerationLimit() {
        val source = listOf(
            File("../llama-android/src/main/cpp/CMakeLists.txt"),
            File("llama-android/src/main/cpp/CMakeLists.txt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("llama-android CMakeLists.txt not found")

        data class Case(val name: String, val marker: String)
        listOf(
            Case("sampler history resets for every OCR block", "common_sampler_reset(g_sampler)"),
            Case("KV history after the system prompt is removed", "system_prompt_position, -1"),
            Case("system chat message is retained", "chat_msgs.resize(keep_system_message ? 1 : 0)"),
            Case("position uses the post-truncation token count", "current_position += (int) user_tokens.size()"),
            Case("generation limit adds only requested tokens", "current_position + n_predict"),
            Case("upstream conversation patch has an exact-match guard", "conversation reset patch no longer matches upstream"),
            Case("upstream token-limit patch has an exact-match guard", "generation limit patch no longer matches upstream"),
        ).forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }

        assertFalse(
            "user prompt length must not be counted twice in the patched expression",
            source.contains("GAMEOCR_STOP_POSITION_PATCHED [=[\n    current_position += user_prompt_size;"),
        )
    }
}
