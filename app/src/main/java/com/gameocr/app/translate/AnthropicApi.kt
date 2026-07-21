package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val ANTHROPIC_API_VERSION = "2023-06-01"

@Serializable
internal data class AnthropicMessageRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicInputMessage>,
    val system: String,
    val temperature: Double,
    val stream: Boolean,
)

@Serializable
internal data class AnthropicInputMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class AnthropicMessageResponse(
    val content: List<AnthropicContentBlock> = emptyList(),
)

@Serializable
private data class AnthropicContentBlock(
    val type: String = "",
    val text: String? = null,
)

@Serializable
private data class AnthropicModelsResponse(
    val data: List<AnthropicModelInfo> = emptyList(),
)

@Serializable
private data class AnthropicModelInfo(
    val id: String? = null,
)

@Serializable
private data class AnthropicErrorResponse(
    val error: AnthropicErrorBody? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
private data class AnthropicErrorBody(
    val type: String = "",
    val message: String = "",
)

@Serializable
private data class AnthropicStreamEnvelope(
    val type: String = "",
    @SerialName("content_block") val contentBlock: AnthropicContentBlock? = null,
    val delta: AnthropicStreamDelta? = null,
    val error: AnthropicErrorBody? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
private data class AnthropicStreamDelta(
    val type: String = "",
    val text: String? = null,
)

internal sealed interface AnthropicStreamEvent {
    data class Text(val value: String) : AnthropicStreamEvent
    data class Error(val detail: String) : AnthropicStreamEvent
    data object Stop : AnthropicStreamEvent
    data object Ignore : AnthropicStreamEvent
}

internal fun buildAnthropicMessageRequest(
    settings: Settings,
    systemPrompt: String,
    userText: String,
    maxTokens: Int,
    temperature: Double,
    stream: Boolean,
    json: Json,
): Request {
    val payload = json.encodeToString(
        AnthropicMessageRequest(
            model = settings.anthropicModel,
            maxTokens = maxTokens,
            messages = listOf(AnthropicInputMessage(role = "user", content = userText)),
            system = systemPrompt,
            temperature = temperature,
            stream = stream,
        )
    )
    return Request.Builder()
        .url(anthropicApiUrl(settings.anthropicBaseUrl, "messages"))
        .header("x-api-key", settings.anthropicApiKey)
        .header("anthropic-version", ANTHROPIC_API_VERSION)
        .header("Content-Type", "application/json")
        .header("Accept", if (stream) "text/event-stream" else "application/json")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
}

internal fun buildAnthropicModelsRequest(settings: Settings): Request = Request.Builder()
    .url(anthropicApiUrl(settings.anthropicBaseUrl, "models"))
    .header("x-api-key", settings.anthropicApiKey)
    .header("anthropic-version", ANTHROPIC_API_VERSION)
    .header("Accept", "application/json")
    .get()
    .build()

internal fun parseAnthropicResponseText(raw: String, json: Json): String? = runCatching {
    json.decodeFromString<AnthropicMessageResponse>(raw)
        .content
        .asSequence()
        .filter { it.type == "text" }
        .mapNotNull(AnthropicContentBlock::text)
        .joinToString("")
        .trim()
        .takeIf(String::isNotEmpty)
}.getOrNull()

internal fun parseAnthropicModelIds(raw: String, json: Json): List<String> = runCatching {
    json.decodeFromString<AnthropicModelsResponse>(raw)
        .data
        .mapNotNull(AnthropicModelInfo::id)
        .filter(String::isNotBlank)
        .distinct()
}.getOrDefault(emptyList())

internal fun parseAnthropicStreamEvent(raw: String, json: Json): AnthropicStreamEvent {
    val event = runCatching { json.decodeFromString<AnthropicStreamEnvelope>(raw) }
        .getOrNull() ?: return AnthropicStreamEvent.Ignore
    return when (event.type) {
        "content_block_start" -> event.contentBlock
            ?.takeIf { it.type == "text" }
            ?.text
            ?.takeIf(String::isNotEmpty)
            ?.let(AnthropicStreamEvent::Text)
            ?: AnthropicStreamEvent.Ignore
        "content_block_delta" -> event.delta
            ?.takeIf { it.type == "text_delta" }
            ?.text
            ?.takeIf(String::isNotEmpty)
            ?.let(AnthropicStreamEvent::Text)
            ?: AnthropicStreamEvent.Ignore
        "message_stop" -> AnthropicStreamEvent.Stop
        "error" -> AnthropicStreamEvent.Error(
            formatAnthropicError(event.error, event.requestId, raw)
        )
        else -> AnthropicStreamEvent.Ignore
    }
}

internal fun anthropicErrorDetail(raw: String, json: Json): String {
    val parsed = runCatching { json.decodeFromString<AnthropicErrorResponse>(raw) }.getOrNull()
    return formatAnthropicError(parsed?.error, parsed?.requestId, raw)
}

private fun formatAnthropicError(
    error: AnthropicErrorBody?,
    requestId: String?,
    raw: String,
): String {
    if (error == null || (error.type.isBlank() && error.message.isBlank())) {
        return raw.trim().take(200).ifBlank { "empty response body" }
    }
    return buildString {
        if (error.type.isNotBlank()) append(error.type)
        if (error.type.isNotBlank() && error.message.isNotBlank()) append(": ")
        if (error.message.isNotBlank()) append(error.message)
        requestId?.takeIf(String::isNotBlank)?.let { append(" (").append(it).append(')') }
    }
}

private fun anthropicApiUrl(baseUrl: String, endpoint: String): String =
    baseUrl.trim().trimEnd('/').let { normalized ->
        val versionedBase = if (normalized.endsWith("/v1")) normalized else "$normalized/v1"
        "$versionedBase/${endpoint.trimStart('/')}"
    }
