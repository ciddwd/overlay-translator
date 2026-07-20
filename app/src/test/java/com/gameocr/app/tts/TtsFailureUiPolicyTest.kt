package com.gameocr.app.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsFailureUiPolicyTest {

    @Test
    fun failureReason_coversUserVisibleCases() {
        data class Case(
            val name: String,
            val error: Throwable,
            val expected: TtsFailureUiReason,
        )

        val longMessage = "x".repeat(200)
        val cases = listOf(
            Case(
                name = "unsupported language",
                error = SystemTtsLanguageUnavailableException("zh-TW"),
                expected = TtsFailureUiReason.LanguageUnavailable("zh-TW"),
            ),
            Case(
                name = "wrapped unsupported language",
                error = RuntimeException(
                    "wrapper",
                    SystemTtsLanguageUnavailableException("ja-JP"),
                ),
                expected = TtsFailureUiReason.LanguageUnavailable("ja-JP"),
            ),
            Case(
                name = "generic message",
                error = IllegalStateException("engine unavailable"),
                expected = TtsFailureUiReason.Generic("engine unavailable"),
            ),
            Case(
                name = "multiline message",
                error = RuntimeException("network\n  timeout"),
                expected = TtsFailureUiReason.Generic("network timeout"),
            ),
            Case(
                name = "blank message",
                error = IllegalArgumentException("   "),
                expected = TtsFailureUiReason.Generic("IllegalArgumentException"),
            ),
            Case(
                name = "long message",
                error = RuntimeException(longMessage),
                expected = TtsFailureUiReason.Generic("x".repeat(159) + "…"),
            ),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, ttsFailureUiReason(case.error))
        }
    }
}
