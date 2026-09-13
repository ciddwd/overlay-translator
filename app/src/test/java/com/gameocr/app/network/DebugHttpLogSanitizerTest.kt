package com.gameocr.app.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugHttpLogSanitizerTest {
    @Test
    fun headerRedaction_tableDriven_hidesCredentialsOnly() {
        data class Case(val name: String, val value: String, val expected: String)

        listOf(
            Case("Authorization", "Bearer private", "***"),
            Case("X-API-Key", "private", "***"),
            Case("Set-Cookie", "session=private", "***"),
            Case("Content-Type", "application/json", "application/json"),
            Case("X-Request-Id", "request-1", "request-1"),
        ).forEach { case ->
            assertEquals(case.name, case.expected, DebugHttpLogSanitizer.header(case.name, case.value))
        }
    }

    @Test
    fun payloadRedaction_tableDriven_preservesTranslationContent() {
        data class Case(val raw: String, val secrets: List<String>, val visible: String)

        listOf(
            Case(
                """{"apikey":"key-1","srcText":"原文","authStr":"sig-1"}""",
                listOf("key-1", "sig-1"),
                "原文",
            ),
            Case(
                "appId=public&apiKey=key-2&text=hello",
                listOf("key-2"),
                "text=hello",
            ),
            Case(
                """{"messages":[{"role":"user","content":"完整请求内容"}]}""",
                emptyList(),
                "完整请求内容",
            ),
        ).forEach { case ->
            val sanitized = DebugHttpLogSanitizer.payload(case.raw)
            case.secrets.forEach { secret -> assertFalse(case.raw, sanitized.contains(secret)) }
            assertTrue(case.raw, sanitized.contains(case.visible))
        }
    }

    @Test
    fun urlAndBodyTypePolicy_tableDriven() {
        val sanitized = DebugHttpLogSanitizer.url(
            "https://example.com/v1?q=hello&access_token=private".toHttpUrl(),
        )
        assertTrue(sanitized.contains("q=hello"))
        assertFalse(sanitized.contains("private"))

        listOf(
            "application/json" to true,
            "application/problem+json" to true,
            "text/event-stream" to true,
            "image/png" to false,
            "application/octet-stream" to false,
        ).forEach { (type, expected) ->
            assertEquals(type, expected, DebugHttpLogSanitizer.isTextBody(type.toMediaTypeOrNull()))
        }
    }
}
