package com.gameocr.app.translate

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiStreamEventTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseLine_tableDriven_distinguishesContentKeepAliveCompletionAndMalformedData() {
        data class Case(
            val name: String,
            val line: String,
            val expected: OpenAiStreamEvent,
        )

        listOf(
            Case("blank separator", "", OpenAiStreamEvent.Ignore),
            Case("SSE keep alive", ": keep-alive", OpenAiStreamEvent.KeepAlive),
            Case("event metadata", "event: message", OpenAiStreamEvent.Ignore),
            Case("done marker", "data: [DONE]", OpenAiStreamEvent.Done),
            Case(
                "role-only valid data",
                """data: {"choices":[{"delta":{"role":"assistant","content":""},"finish_reason":null}]}""",
                OpenAiStreamEvent.Data(content = "", finishReason = null),
            ),
            Case(
                "content data",
                """data:{"choices":[{"delta":{"content":"译文"},"finish_reason":null}]}""",
                OpenAiStreamEvent.Data(content = "译文", finishReason = null),
            ),
            Case(
                "finish reason without content",
                """data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}""",
                OpenAiStreamEvent.Data(content = "", finishReason = "stop"),
            ),
            Case(
                "usage-only valid data",
                """data: {"choices":[],"usage":{"total_tokens":12}}""",
                OpenAiStreamEvent.Data(content = "", finishReason = null),
            ),
            Case("malformed data", "data: not-json", OpenAiStreamEvent.Malformed("not-json")),
        ).forEach { case ->
            assertEquals(case.name, case.expected, parseOpenAiStreamLine(case.line, json))
        }
    }
}
