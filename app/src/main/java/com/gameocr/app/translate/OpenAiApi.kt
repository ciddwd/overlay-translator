package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeTranslationVisualContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** OpenAI 兼容 chat completions 请求 / 响应 DTO（M0 非流式）。 */
@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<OpenAiRequestMessage>,
    val temperature: Double = 0.3,
    @SerialName("top_p") val topP: Double? = null,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("response_format") val responseFormat: ChatResponseFormat? = null,
)

@Serializable
internal data class OpenAiRequestMessage(
    val role: String,
    val content: JsonElement,
)

@Serializable
internal data class ChatResponseFormat(
    val type: String,
)

@Serializable
internal data class ChatMessage(
    val role: String,
    val content: String
)

internal fun buildOpenAiUserContent(
    text: String,
    visualContext: RuntimeTranslationVisualContext?,
    imageDetail: String? = "auto",
): JsonElement = visualContext?.let { visual ->
    buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
        })
        add(buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject {
                put("url", "data:${visual.mimeType};base64,${visual.base64Data}")
                imageDetail?.takeIf { it.isNotBlank() }?.let { put("detail", it) }
            })
        })
    }
} ?: JsonPrimitive(text)

internal fun buildOpenAiChatMessages(
    resolved: ResolvedOpenAiRequest,
    visualContext: RuntimeTranslationVisualContext? = null,
    imageDetail: String? = "auto",
): List<OpenAiRequestMessage> =
    buildList {
        add(OpenAiRequestMessage(role = "system", content = JsonPrimitive(resolved.systemMessage)))
        resolved.conversationMessages.forEach { message ->
            add(OpenAiRequestMessage(role = message.role, content = JsonPrimitive(message.content)))
        }
        add(
            OpenAiRequestMessage(
                role = "user",
                content = buildOpenAiUserContent(resolved.userMessage, visualContext, imageDetail),
            )
        )
    }

@Serializable
internal data class ChatResponse(
    val id: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

@Serializable
internal data class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
internal data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

/** SSE 流式片段。OpenAI 兼容 stream=true 时每个 `data: {...}` 行的 schema。 */
@Serializable
internal data class ChatStreamChunk(
    val id: String? = null,
    val choices: List<ChatStreamChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

@Serializable
internal data class ChatStreamChoice(
    val index: Int = 0,
    val delta: ChatStreamDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class ChatStreamDelta(
    val role: String? = null,
    val content: String? = null
)

internal sealed interface OpenAiStreamEvent {
    data class Data(
        val content: String,
        val finishReason: String?,
        val usage: ChatUsage? = null,
    ) : OpenAiStreamEvent

    data class Malformed(val payload: String) : OpenAiStreamEvent
    data object KeepAlive : OpenAiStreamEvent
    data object Done : OpenAiStreamEvent
    data object Ignore : OpenAiStreamEvent
}

internal fun parseOpenAiStreamLine(
    line: String,
    json: kotlinx.serialization.json.Json,
): OpenAiStreamEvent {
    if (line.isBlank()) return OpenAiStreamEvent.Ignore
    if (line.startsWith(':')) return OpenAiStreamEvent.KeepAlive
    if (!line.startsWith("data:")) return OpenAiStreamEvent.Ignore
    val payload = line.substring(5).trim()
    if (payload == "[DONE]") return OpenAiStreamEvent.Done
    val chunk = runCatching { json.decodeFromString<ChatStreamChunk>(payload) }
        .getOrNull() ?: return OpenAiStreamEvent.Malformed(payload)
    val choice = chunk.choices.firstOrNull()
    return OpenAiStreamEvent.Data(
        content = choice?.delta?.content.orEmpty(),
        finishReason = choice?.finishReason,
        usage = chunk.usage,
    )
}

/** `GET /v1/models` 响应。Ollama / vLLM / OpenAI / DeepSeek 全部用这个 schema。 */
@Serializable
internal data class ModelsResponse(
    val data: List<ModelInfo> = emptyList()
)

@Serializable
internal data class ModelInfo(
    val id: String? = null
)
