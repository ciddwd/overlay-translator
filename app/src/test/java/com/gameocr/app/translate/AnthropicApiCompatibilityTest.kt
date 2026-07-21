package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicApiCompatibilityTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun messageRequest_tableDrivenUsesAnthropicHeadersAndTopLevelSystem() {
        data class Case(
            val name: String,
            val baseUrl: String,
            val stream: Boolean,
            val maxTokens: Int,
            val expectedUrl: String,
            val expectedAccept: String,
        )

        val settings = Settings(
            anthropicApiKey = "anthropic-secret",
            anthropicModel = "claude-test-model",
        )
        listOf(
            Case(
                name = "official non-stream request",
                baseUrl = "https://api.anthropic.com/v1/",
                stream = false,
                maxTokens = 1024,
                expectedUrl = "https://api.anthropic.com/v1/messages",
                expectedAccept = "application/json",
            ),
            Case(
                name = "custom gateway stream request",
                baseUrl = "https://gateway.example/anthropic/v1",
                stream = true,
                maxTokens = 600,
                expectedUrl = "https://gateway.example/anthropic/v1/messages",
                expectedAccept = "text/event-stream",
            ),
            Case(
                name = "DeepSeek SDK-style base URL",
                baseUrl = "https://api.deepseek.com/anthropic",
                stream = true,
                maxTokens = 4096,
                expectedUrl = "https://api.deepseek.com/anthropic/v1/messages",
                expectedAccept = "text/event-stream",
            ),
        ).forEach { case ->
            val request = buildAnthropicMessageRequest(
                settings = settings.copy(anthropicBaseUrl = case.baseUrl),
                systemPrompt = "Translate accurately",
                userText = "こんにちは",
                maxTokens = case.maxTokens,
                temperature = 0.3,
                stream = case.stream,
                json = json,
            )
            val body = json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
            val messages = body.getValue("messages").jsonArray

            assertEquals(case.name, case.expectedUrl, request.url.toString())
            assertEquals(case.name, "anthropic-secret", request.header("x-api-key"))
            assertEquals(case.name, ANTHROPIC_API_VERSION, request.header("anthropic-version"))
            assertEquals(case.name, "application/json", request.header("Content-Type"))
            assertEquals(case.name, case.expectedAccept, request.header("Accept"))
            assertNull(case.name, request.header("Authorization"))
            assertEquals(case.name, "Translate accurately", body.getValue("system").jsonPrimitive.content)
            assertEquals(case.name, case.maxTokens, body.getValue("max_tokens").jsonPrimitive.int)
            assertEquals(case.name, case.stream, body.getValue("stream").jsonPrimitive.boolean)
            assertEquals(case.name, 1, messages.size)
            assertEquals(case.name, "user", messages.single().jsonObject.getValue("role").jsonPrimitive.content)
            assertEquals(case.name, "こんにちは", messages.single().jsonObject.getValue("content").jsonPrimitive.content)
            assertFalse(case.name, messages.any { it.jsonObject["role"]?.jsonPrimitive?.content == "system" })
        }
    }

    @Test
    fun modelsRequest_tableDrivenUsesSameVersionedAuthentication() {
        data class Case(val baseUrl: String, val expectedUrl: String)

        listOf(
            Case("https://api.anthropic.com/v1/", "https://api.anthropic.com/v1/models"),
            Case("https://proxy.example/v1", "https://proxy.example/v1/models"),
            Case(
                "https://api.deepseek.com/anthropic",
                "https://api.deepseek.com/anthropic/v1/models",
            ),
        ).forEach { case ->
            val request = buildAnthropicModelsRequest(
                Settings(
                    anthropicBaseUrl = case.baseUrl,
                    anthropicApiKey = "key",
                )
            )
            assertEquals(case.expectedUrl, request.url.toString())
            assertEquals("key", request.header("x-api-key"))
            assertEquals(ANTHROPIC_API_VERSION, request.header("anthropic-version"))
            assertEquals("application/json", request.header("Accept"))
        }
    }

    @Test
    fun responseText_tableDrivenCollectsOnlyTextBlocks() {
        data class Case(val name: String, val raw: String, val expected: String?)

        listOf(
            Case(
                "single text block",
                """{"content":[{"type":"text","text":"译文"}]}""",
                "译文",
            ),
            Case(
                "multiple text blocks skip thinking and unknown content",
                """{"content":[{"type":"thinking","thinking":"hidden"},{"type":"text","text":"第一段"},{"type":"future_block","value":"ignored"},{"type":"text","text":"第二段"}]}""",
                "第一段第二段",
            ),
            Case("no text blocks", """{"content":[{"type":"thinking","thinking":"hidden"}]}""", null),
            Case("malformed response", "not-json", null),
        ).forEach { case ->
            assertEquals(case.name, case.expected, parseAnthropicResponseText(case.raw, json))
        }
    }

    @Test
    fun modelIds_tableDrivenPreservesServerOrderAndIgnoresInvalidEntries() {
        data class Case(val name: String, val raw: String, val expected: List<String>)

        listOf(
            Case(
                "official model list with future fields",
                """{"data":[{"type":"model","id":"claude-new","display_name":"New","capabilities":{"thinking":{"supported":true}}},{"type":"model","id":"claude-fast"}],"has_more":false}""",
                listOf("claude-new", "claude-fast"),
            ),
            Case(
                "duplicates and blank ids are removed",
                """{"data":[{"id":"claude-a"},{"id":""},{"id":null},{"id":"claude-a"}]}""",
                listOf("claude-a"),
            ),
            Case("malformed model response", "not-json", emptyList()),
        ).forEach { case ->
            assertEquals(case.name, case.expected, parseAnthropicModelIds(case.raw, json))
        }
    }

    @Test
    fun streamEvent_tableDrivenHandlesTextStopErrorsAndFutureEvents() {
        data class Case(
            val name: String,
            val raw: String,
            val expected: AnthropicStreamEvent,
        )

        listOf(
            Case(
                "initial text in content block start",
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"开头"}}""",
                AnthropicStreamEvent.Text("开头"),
            ),
            Case(
                "incremental text delta",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"增量"}}""",
                AnthropicStreamEvent.Text("增量"),
            ),
            Case(
                "thinking delta is ignored",
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hidden"}}""",
                AnthropicStreamEvent.Ignore,
            ),
            Case("ping is ignored", """{"type":"ping"}""", AnthropicStreamEvent.Ignore),
            Case("future event is ignored", """{"type":"future_event","extra":true}""", AnthropicStreamEvent.Ignore),
            Case("message stop ends stream", """{"type":"message_stop"}""", AnthropicStreamEvent.Stop),
            Case(
                "stream error keeps type message and request id",
                """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"},"request_id":"req_123"}""",
                AnthropicStreamEvent.Error("overloaded_error: Overloaded (req_123)"),
            ),
            Case("malformed event is ignored", "not-json", AnthropicStreamEvent.Ignore),
        ).forEach { case ->
            assertEquals(case.name, case.expected, parseAnthropicStreamEvent(case.raw, json))
        }
    }

    @Test
    fun errorDetail_tableDrivenParsesOfficialShapeAndFallsBackToRawBody() {
        data class Case(val name: String, val raw: String, val expected: String)

        listOf(
            Case(
                "official error",
                """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"},"request_id":"req_auth"}""",
                "authentication_error: invalid x-api-key (req_auth)",
            ),
            Case("compatible gateway plain text", "upstream unavailable", "upstream unavailable"),
        ).forEach { case ->
            assertEquals(case.name, case.expected, anthropicErrorDetail(case.raw, json))
        }
    }
}

private fun okhttp3.RequestBody.utf8(): String {
    val buffer = okio.Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
