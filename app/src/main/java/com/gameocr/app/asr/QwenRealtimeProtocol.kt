package com.gameocr.app.asr

import com.gameocr.app.data.AsrLanguage
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.ByteString

/** Official Qwen-ASR-Realtime client/server events, verified 2026-10-17:
 * https://help.aliyun.com/zh/model-studio/qwen-asr-realtime-client-events
 * https://help.aliyun.com/zh/model-studio/qwen-asr-realtime-server-events
 */
internal object QwenRealtimeProtocol {
    fun session(language: AsrLanguage): String = buildJsonObject {
        put("event_id", UUID.randomUUID().toString())
        put("type", "session.update")
        putJsonObject("session") {
            put("input_audio_format", "pcm")
            put("sample_rate", 16000)
            putJsonObject("input_audio_transcription") {
                if (language != AsrLanguage.AUTO) put("language", language.code)
            }
            putJsonObject("turn_detection") {
                put("type", "server_vad")
                put("threshold", 0.0)
                put("silence_duration_ms", 400)
            }
        }
    }.toString()

    fun audio(frame: ByteString): String = buildJsonObject {
        put("event_id", UUID.randomUUID().toString())
        put("type", "input_audio_buffer.append")
        put("audio", frame.base64())
    }.toString()

    fun finish(): String = buildJsonObject {
        put("event_id", UUID.randomUUID().toString())
        put("type", "session.finish")
    }.toString()

    fun parse(text: String, includeTranscription: Boolean = true): ParaformerEvent? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        if (!includeTranscription && root.string("type")?.startsWith("conversation.item.input_audio_transcription.") == true) return null
        when (root.string("type")) {
            "session.updated" -> ParaformerEvent.Started
            "session.finished" -> ParaformerEvent.Finished
            "error", "conversation.item.input_audio_transcription.failed" -> {
                val error = root["error"]?.jsonObject
                ParaformerEvent.Failed(error?.string("code").orEmpty(), error?.string("message").orEmpty())
            }
            "conversation.item.input_audio_transcription.text" -> {
                val id = root.string("item_id")?.takeIf { it.isNotBlank() } ?: return null
                ParaformerEvent.Result(root.string("text").orEmpty() + root.string("stash").orEmpty(), false, false, id)
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val id = root.string("item_id")?.takeIf { it.isNotBlank() } ?: return null
                ParaformerEvent.Result(root.string("transcript").orEmpty(), true, false, id)
            }
            else -> null
        }
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}

/** Item IDs keep delayed finals attached to their own partial, even when sentences overlap. */
internal class CloudSpeechItems {
    private data class Item(val segment: Long, var text: String = "", var complete: Boolean = false)
    private val items = linkedMapOf<String, Item>()
    private var nextSegment = 0L

    fun accept(id: String, text: String, final: Boolean): SpeechEvent? {
        val item = items.getOrPut(id) { Item(nextSegment++) }
        while (items.size > 256) items.remove(items.keys.first())
        if (item.complete || (!final && item.text == text)) return null
        item.complete = final
        item.text = text
        return SpeechEvent(item.segment, text, final).takeIf { text.isNotEmpty() }
    }

    fun reset() { items.clear() } // Keep the counter monotonic across pause/reconnect.
}
