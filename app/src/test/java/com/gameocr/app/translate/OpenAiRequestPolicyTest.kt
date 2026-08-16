package com.gameocr.app.translate

import com.gameocr.app.data.OpenAiRequestOptions
import com.gameocr.app.data.RemoteReasoningEffort
import com.gameocr.app.data.RemoteThinkingParameterFormat
import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.data.TranslationContextMode
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRequestPolicyTest {
    @Test
    fun `user text encoding changes only the outbound text placeholder_tableDriven`() {
        data class Case(
            val name: String,
            val source: String,
            val options: OpenAiRequestOptions,
            val expectedPlaceholder: String,
        )

        listOf(
            Case(
                name = "disabled keeps the original OCR text",
                source = "原文",
                options = OpenAiRequestOptions(),
                expectedPlaceholder = "原文",
            ),
            Case(
                name = "Base64 uses UTF-8 without line wrapping",
                source = "原文",
                options = OpenAiRequestOptions(encodeUserTextBase64 = true),
                expectedPlaceholder = "5Y6f5paH",
            ),
            Case(
                name = "Unicode escapes every UTF-16 unit including newline and surrogate pair",
                source = "A日\n😀",
                options = OpenAiRequestOptions(encodeUserTextUnicode = true),
                expectedPlaceholder = "\\u0041\\u65E5\\u000A\\uD83D\\uDE00",
            ),
            Case(
                name = "malformed imported preset deterministically prefers Base64",
                source = "abc",
                options = OpenAiRequestOptions(
                    encodeUserTextBase64 = true,
                    encodeUserTextUnicode = true,
                ),
                expectedPlaceholder = "YWJj",
            ),
            Case(
                name = "empty source remains empty under Base64",
                source = "",
                options = OpenAiRequestOptions(encodeUserTextBase64 = true),
                expectedPlaceholder = "",
            ),
        ).forEach { case ->
            val resolved = OpenAiRequestPolicy.resolve(
                text = case.source,
                systemPromptTemplate = "translate",
                sourceDisplay = "Japanese",
                targetDisplay = "Chinese",
                runtimeContext = "",
                options = case.options.copy(
                    userMessageTemplate = "before:{text}:after",
                    systemPromptSuffix = "",
                ),
                networkRequestTimeoutSeconds = 30,
            )

            assertEquals(
                case.name,
                "before:${case.expectedPlaceholder}:after",
                resolved.userMessage,
            )
        }
    }

    @Test
    fun `encoded source protocol is automatic and unique across translation modes_tableDriven`() {
        data class EncodingCase(
            val name: String,
            val options: OpenAiRequestOptions,
            val expectedSource: String,
            val expectedProtocol: String?,
        )

        val source = "A日\n😀"
        val encodings = listOf(
            EncodingCase(
                name = "plain",
                options = OpenAiRequestOptions(),
                expectedSource = source,
                expectedProtocol = null,
            ),
            EncodingCase(
                name = "Base64",
                options = OpenAiRequestOptions(encodeUserTextBase64 = true),
                expectedSource = "QeaXpQrwn5iA",
                expectedProtocol = "Base64-encoded UTF-8",
            ),
            EncodingCase(
                name = "Unicode",
                options = OpenAiRequestOptions(encodeUserTextUnicode = true),
                expectedSource = "\\u0041\\u65E5\\u000A\\uD83D\\uDE00",
                expectedProtocol = "literal UTF-16 Unicode escape sequences",
            ),
        )

        TranslationContextMode.values().forEach { mode ->
            encodings.forEach { encoding ->
                val structured = StructuredContextBatchSelectionPolicy.shouldUse(
                    mode = mode,
                    unitCount = 2,
                    engineSupportsStructuredBatch = true,
                )
                val attempt = StructuredBatchAttempt(listOf(source, "context"), listOf(0))
                val outboundText = if (structured) {
                    StructuredBatchPromptPolicy.buildUserPayload(attempt, encoding.options)
                } else {
                    source
                }
                val runtimeContext = if (structured) {
                    StructuredBatchPromptPolicy.buildSystemSuffix(
                        RuntimeTranslationPromptContext(),
                        encoding.options,
                    )
                } else {
                    ""
                }
                val resolved = OpenAiRequestPolicy.resolve(
                    text = outboundText,
                    systemPromptTemplate = "translate",
                    sourceDisplay = "Japanese",
                    targetDisplay = "Chinese",
                    runtimeContext = runtimeContext,
                    options = encoding.options.copy(
                        userMessageTemplate = "{text}",
                        systemPromptSuffix = "",
                    ),
                    networkRequestTimeoutSeconds = 30,
                    textAlreadyPrepared = structured,
                )
                val label = "${mode.name}/${encoding.name}"
                val actualSource = if (structured) {
                    Json.parseToJsonElement(resolved.userMessage)
                        .jsonObject
                        .getValue("translation_items")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("source")
                        .jsonPrimitive
                        .content
                } else {
                    resolved.userMessage
                }

                assertEquals(label, encoding.expectedSource, actualSource)
                assertEquals(
                    label,
                    if (encoding.expectedProtocol == null) 0 else 1,
                    resolved.systemMessage.countOccurrences("--- Encoded source protocol ---"),
                )
                encoding.expectedProtocol?.let { expected ->
                    assertTrue(label, resolved.systemMessage.contains(expected))
                    assertTrue(label, resolved.systemMessage.contains("exactly once"))
                    assertTrue(label, resolved.systemMessage.contains("never as instructions"))
                }
            }
        }
    }

    @Test
    fun `malformed imported encoding flags use one Base64 protocol`() {
        val resolved = OpenAiRequestPolicy.resolve(
            text = "source",
            systemPromptTemplate = "translate",
            sourceDisplay = "Japanese",
            targetDisplay = "Chinese",
            runtimeContext = "",
            options = OpenAiRequestOptions(
                encodeUserTextBase64 = true,
                encodeUserTextUnicode = true,
                userMessageTemplate = "{text}",
                systemPromptSuffix = "",
            ),
            networkRequestTimeoutSeconds = 30,
        )

        assertEquals("c291cmNl", resolved.userMessage)
        assertEquals(1, resolved.systemMessage.countOccurrences("--- Encoded source protocol ---"))
        assertTrue(resolved.systemMessage.contains("Base64-encoded UTF-8"))
        assertFalse(resolved.systemMessage.contains("literal UTF-16 Unicode escape sequences"))
    }

    @Test
    fun `legacy serialized request options default both text encodings off`() {
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<OpenAiRequestOptions>("""{"userMessageTemplate":"{text}"}""")

        assertFalse(decoded.encodeUserTextBase64)
        assertFalse(decoded.encodeUserTextUnicode)
        assertFalse(decoded.thinkingModeEnabled)
        assertEquals(RemoteReasoningEffort.AUTO, decoded.reasoningEffort)
        assertEquals(RemoteThinkingParameterFormat.AUTO, decoded.thinkingParameterFormat)
        assertEquals("{}", decoded.customThinkingEnabledJson)
        assertEquals("{}", decoded.customThinkingDisabledJson)
    }

    @Test
    fun `thinking control maps explicit formats and official endpoint families_tableDriven`() {
        data class Case(
            val name: String,
            val baseUrl: String,
            val model: String = "model",
            val options: OpenAiRequestOptions,
            val style: OpenAiThinkingWireStyle,
            val expectedFields: String,
        )

        listOf(
            Case(
                name = "unknown compatible endpoint omits unsupported fields when off",
                baseUrl = "http://192.168.0.159:1234/v1/",
                options = OpenAiRequestOptions(),
                style = OpenAiThinkingWireStyle.OMIT,
                expectedFields = "{}",
            ),
            Case(
                name = "unknown compatible endpoint omits unsupported fields when on",
                baseUrl = "https://gateway.example/v1",
                options = OpenAiRequestOptions(
                    thinkingModeEnabled = true,
                    reasoningEffort = RemoteReasoningEffort.HIGH,
                ),
                style = OpenAiThinkingWireStyle.OMIT,
                expectedFields = "{}",
            ),
            Case(
                name = "official OpenAI model that supports none is explicitly disabled",
                baseUrl = "https://api.openai.com/v1/",
                model = "gpt-5.6",
                options = OpenAiRequestOptions(),
                style = OpenAiThinkingWireStyle.REASONING_EFFORT,
                expectedFields = """{"reasoning_effort":"none"}""",
            ),
            Case(
                name = "unknown official OpenAI model avoids unsupported none",
                baseUrl = "https://api.openai.com/v1/",
                model = "third-party-model",
                options = OpenAiRequestOptions(),
                style = OpenAiThinkingWireStyle.REASONING_EFFORT,
                expectedFields = "{}",
            ),
            Case(
                name = "DeepSeek disables thinking with its documented object",
                baseUrl = "https://api.deepseek.com/v1/",
                options = OpenAiRequestOptions(),
                style = OpenAiThinkingWireStyle.THINKING_OBJECT,
                expectedFields = """{"thinking":{"type":"disabled"}}""",
            ),
            Case(
                name = "DeepSeek sends its toggle and selected effort",
                baseUrl = "https://api.deepseek.com/v1/",
                options = OpenAiRequestOptions(
                    thinkingModeEnabled = true,
                    reasoningEffort = RemoteReasoningEffort.LOW,
                ),
                style = OpenAiThinkingWireStyle.THINKING_OBJECT,
                expectedFields =
                    """{"thinking":{"type":"enabled"},"reasoning_effort":"low"}""",
            ),
            Case(
                name = "DashScope uses enable_thinking",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                options = OpenAiRequestOptions(thinkingModeEnabled = true),
                style = OpenAiThinkingWireStyle.ENABLE_THINKING,
                expectedFields = """{"enable_thinking":true}""",
            ),
            Case(
                name = "explicit Responses shape uses a nested reasoning object",
                baseUrl = "https://gateway.example/v1/",
                options = OpenAiRequestOptions(
                    thinkingModeEnabled = true,
                    reasoningEffort = RemoteReasoningEffort.XHIGH,
                    thinkingParameterFormat = RemoteThinkingParameterFormat.OPENAI_RESPONSES,
                ),
                style = OpenAiThinkingWireStyle.RESPONSES_REASONING,
                expectedFields = """{"reasoning":{"effort":"xhigh"}}""",
            ),
            Case(
                name = "custom JSON substitutes the selected custom effort",
                baseUrl = "https://gateway.example/v1/",
                options = OpenAiRequestOptions(
                    thinkingModeEnabled = true,
                    reasoningEffort = RemoteReasoningEffort.CUSTOM,
                    customReasoningEffort = "fast_plus",
                    thinkingParameterFormat = RemoteThinkingParameterFormat.CUSTOM_JSON,
                    customThinkingEnabledJson =
                        """{"custom_thinking":true,"level":"{effort}"}""",
                ),
                style = OpenAiThinkingWireStyle.CUSTOM_JSON,
                expectedFields = """{"custom_thinking":true,"level":"fast_plus"}""",
            ),
        ).forEach { case ->
            val actual = RemoteThinkingPolicy.openAi(case.baseUrl, case.model, case.options)
            assertEquals(case.name, case.style, actual.style)
            assertEquals(case.name, case.expectedFields, actual.fields.toString())
        }
    }

    @Test
    fun `Anthropic thinking control separates the switch and effort_tableDriven`() {
        data class Case(
            val name: String,
            val options: OpenAiRequestOptions,
            val expectedFields: String,
        )

        listOf(
            Case(
                "disabled is explicit",
                OpenAiRequestOptions(),
                """{"thinking":{"type":"disabled"}}""",
            ),
            Case(
                "enabled auto uses provider default effort",
                OpenAiRequestOptions(thinkingModeEnabled = true),
                """{"thinking":{"type":"adaptive","display":"omitted"}}""",
            ),
            Case(
                "enabled medium sends output_config effort",
                OpenAiRequestOptions(
                    thinkingModeEnabled = true,
                    reasoningEffort = RemoteReasoningEffort.MEDIUM,
                ),
                """{"thinking":{"type":"adaptive","display":"omitted"},"output_config":{"effort":"medium"}}""",
            ),
        ).forEach { case ->
            val actual = RemoteThinkingPolicy.anthropic(case.options)
            assertEquals(case.name, OpenAiThinkingWireStyle.ANTHROPIC_OUTPUT_CONFIG, actual.style)
            assertEquals(case.name, case.expectedFields, actual.fields.toString())
        }
    }

    @Test
    fun `custom thinking JSON rejects invalid shapes and protected request fields_tableDriven`() {
        data class Case(val name: String, val customJson: String)

        listOf(
            Case("array is not a root object", "[]"),
            Case("malformed JSON", "{"),
            Case("model is protected", """{"model":"replacement"}"""),
            Case("messages are protected", """{"messages":[]}"""),
            Case("system is protected", """{"system":"replacement"}"""),
            Case("stream is protected", """{"stream":true}"""),
        ).forEach { case ->
            val result = runCatching {
                RemoteThinkingPolicy.openAi(
                    baseUrl = "https://gateway.example/v1/",
                    model = "model",
                    options = OpenAiRequestOptions(
                        thinkingModeEnabled = true,
                        thinkingParameterFormat = RemoteThinkingParameterFormat.CUSTOM_JSON,
                        customThinkingEnabledJson = case.customJson,
                    ),
                )
            }
            assertTrue(case.name, result.isFailure)
        }
    }

    @Test
    fun `thinking fields merge into request body without changing core fields_tableDriven`() {
        data class Case(
            val name: String,
            val baseUrl: String,
            val options: OpenAiRequestOptions,
            val expectedField: String?,
        )

        listOf(
            Case(
                "unknown auto endpoint adds nothing",
                "https://gateway.example/v1/",
                OpenAiRequestOptions(thinkingModeEnabled = true),
                null,
            ),
            Case(
                "DeepSeek adds thinking and effort",
                "https://api.deepseek.com/v1/",
                OpenAiRequestOptions(
                    thinkingModeEnabled = true,
                    reasoningEffort = RemoteReasoningEffort.HIGH,
                ),
                "reasoning_effort",
            ),
            Case(
                "custom format adds provider field",
                "https://gateway.example/v1/",
                OpenAiRequestOptions(
                    thinkingModeEnabled = true,
                    thinkingParameterFormat = RemoteThinkingParameterFormat.CUSTOM_JSON,
                    customThinkingEnabledJson = """{"provider_thinking":true}""",
                ),
                "provider_thinking",
            ),
        ).forEach { case ->
            val serializer = Json { explicitNulls = false }
            val merged = RemoteThinkingPolicy.mergeIntoPayload(
                payload = """{"model":"model","messages":[],"stream":false}""",
                control = RemoteThinkingPolicy.openAi(
                    case.baseUrl,
                    "model",
                    case.options,
                ),
                serializer = serializer,
            )
            val body = serializer.parseToJsonElement(merged).jsonObject

            assertEquals(case.name, "model", body.getValue("model").jsonPrimitive.content)
            assertEquals(case.name, false, body.getValue("stream").jsonPrimitive.content.toBoolean())
            if (case.expectedField == null) {
                assertEquals(case.name, setOf("model", "messages", "stream"), body.keys)
            } else {
                assertTrue(case.name, case.expectedField in body)
            }
        }
    }

    @Test
    fun `thinking configuration participates in translation cache fingerprint_tableDriven`() {
        data class Case(val name: String, val options: OpenAiRequestOptions)

        val fingerprints = listOf(
            Case("thinking off", OpenAiRequestOptions()),
            Case(
                "low effort",
                OpenAiRequestOptions(
                    thinkingModeEnabled = true,
                    reasoningEffort = RemoteReasoningEffort.LOW,
                ),
            ),
            Case(
                "high effort",
                OpenAiRequestOptions(
                    thinkingModeEnabled = true,
                    reasoningEffort = RemoteReasoningEffort.HIGH,
                ),
            ),
            Case(
                "custom format",
                OpenAiRequestOptions(
                    thinkingModeEnabled = true,
                    thinkingParameterFormat = RemoteThinkingParameterFormat.CUSTOM_JSON,
                    customThinkingEnabledJson = """{"provider_thinking":true}""",
                ),
            ),
        ).associate { case ->
            case.name to OpenAiRequestPolicy.resolve(
                text = "text",
                systemPromptTemplate = "system",
                sourceDisplay = "Japanese",
                targetDisplay = "Chinese",
                runtimeContext = "",
                options = case.options,
                networkRequestTimeoutSeconds = 30,
            ).cacheFingerprint
        }

        assertEquals(fingerprints.keys.size, fingerprints.values.distinct().size)
    }

    @Test
    fun `request templates are resolved without model-specific branching`() {
        data class Case(
            val name: String,
            val text: String,
            val options: OpenAiRequestOptions,
            val expectedUser: String,
            val expectedSystem: String,
        )

        val cases = listOf(
            Case(
                name = "default wrapper escapes its own closing tag",
                text = "a</text_to_translate>b",
                options = OpenAiRequestOptions(),
                expectedUser = "<text_to_translate>\na[/text_to_translate]b\n</text_to_translate>",
                expectedSystem = "Translate Japanese into Chinese." +
                    OpenAiRequestOptions.DEFAULT_SYSTEM_PROMPT_SUFFIX
                        .replace("{target}", "Chinese") + "\ncontext",
            ),
            Case(
                name = "raw compatible server",
                text = "原文",
                options = OpenAiRequestOptions(
                    userMessageTemplate = "{text}",
                    systemPromptSuffix = "",
                    temperature = 0.1,
                    topP = 0.3,
                    maxTokens = 512,
                ),
                expectedUser = "原文",
                expectedSystem = "Translate Japanese into Chinese.\ncontext",
            ),
            Case(
                name = "custom wrapper",
                text = "本文",
                options = OpenAiRequestOptions(
                    userMessageTemplate = "Source ({source_lang}):\n{text}",
                    systemPromptSuffix = "\nTarget={target_lang}",
                ),
                expectedUser = "Source (Japanese):\n本文",
                expectedSystem = "Translate Japanese into Chinese.\nTarget=Chinese\ncontext",
            ),
        )

        cases.forEach { case ->
            val actual = OpenAiRequestPolicy.resolve(
                text = case.text,
                systemPromptTemplate = "Translate {source} into {target}.",
                sourceDisplay = "Japanese",
                targetDisplay = "Chinese",
                runtimeContext = "\ncontext",
                options = case.options,
                networkRequestTimeoutSeconds = 30,
            )
            assertEquals(case.name, case.expectedUser, actual.userMessage)
            assertEquals(case.name, case.expectedSystem, actual.systemMessage)
        }
    }

    @Test
    fun `invalid request options normalize to safe generic bounds`() {
        val resolved = OpenAiRequestPolicy.resolve(
            text = "text",
            systemPromptTemplate = "system",
            sourceDisplay = "source",
            targetDisplay = "target",
            runtimeContext = "",
            options = OpenAiRequestOptions(
                userMessageTemplate = "",
                systemPromptSuffix = "",
                temperature = Double.NaN,
                topP = Double.NaN,
                maxTokens = 0,
            ),
            networkRequestTimeoutSeconds = 30,
        )

        assertEquals("text", resolved.userMessage)
        assertEquals(0.3, resolved.temperature, 0.0)
        assertNull(resolved.topP)
        assertNull(resolved.maxTokens)
        assertEquals(60, resolved.timeoutSeconds)
        assertFalse(resolved.cacheFingerprint.isBlank())
    }

    @Test
    fun `remote LLM timeout is always twice the shared network timeout_tableDriven`() {
        data class Case(val name: String, val networkSeconds: Int, val expectedSeconds: Int)

        listOf(
            Case("below minimum is normalized before doubling", 1, 10),
            Case("minimum network timeout", 5, 10),
            Case("default network timeout", 30, 60),
            Case("upper network timeout", 300, 600),
            Case("above maximum is capped before doubling", 999, 600),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedSeconds,
                OpenAiRequestPolicy.remoteLlmTimeoutSeconds(case.networkSeconds),
            )
        }
    }
}

private fun String.countOccurrences(value: String): Int {
    if (value.isEmpty()) return 0
    var count = 0
    var start = 0
    while (true) {
        val next = indexOf(value, start)
        if (next < 0) return count
        count++
        start = next + value.length
    }
}
