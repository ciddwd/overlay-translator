package com.gameocr.app.translate

import java.nio.charset.StandardCharsets
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationRequestAuditTest {

    @Test
    fun chunkUtf8_tableDriven_preservesExactPayloadWithinByteLimit() {
        data class Case(
            val name: String,
            val payload: String,
            val maxBytes: Int,
            val expectedParts: Int,
        )

        listOf(
            Case("empty body", "", 8, 1),
            Case("exact ASCII boundary", "12345678", 8, 1),
            Case("ASCII overflow", "123456789", 8, 2),
            Case("CJK boundary", "翻译请求", 6, 2),
            Case("emoji surrogate pair", "A😀B😀C", 5, 3),
            Case("escaped JSON lines", "{\"content\":\"a\\nb\"}", 8, 3),
        ).forEach { case ->
            val chunks = TranslationRequestAudit.chunkUtf8(case.payload, case.maxBytes)

            assertEquals(case.name, case.payload, chunks.joinToString(""))
            assertEquals(case.name, case.expectedParts, chunks.size)
            assertTrue(
                case.name,
                chunks.all { it.toByteArray(StandardCharsets.UTF_8).size <= case.maxBytes },
            )
            chunks.forEach { chunk ->
                assertFalse(case.name, chunk.firstOrNull()?.isLowSurrogate() == true)
                assertFalse(case.name, chunk.lastOrNull()?.isHighSurrogate() == true)
            }
        }
    }

    @Test
    fun requestPayload_containsOnlyBodyAndNeverHeaders() {
        val payload = "{\"messages\":[{\"role\":\"user\",\"content\":\"こんにちは\"}]}"
        val request = Request.Builder()
            .url("https://example.com/v1/chat/completions")
            .header("Authorization", "Bearer sk-must-not-log")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val audited = TranslationRequestAudit.requestPayload(request)

        assertEquals(payload, audited)
        assertFalse(audited.contains("sk-must-not-log"))
        assertFalse(audited.contains("Authorization"))
    }
}
