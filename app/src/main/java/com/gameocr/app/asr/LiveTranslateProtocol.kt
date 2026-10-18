package com.gameocr.app.asr

import com.gameocr.app.data.AsrCloudModel
import com.gameocr.app.data.AsrLanguage
import java.util.Locale
import kotlinx.serialization.json.*

/** Official client/server events, verified with HTTP 200 on 2026-10-17:
 * https://help.aliyun.com/zh/model-studio/live-translator-client-events
 * https://help.aliyun.com/zh/model-studio/live-translator-server-events
 */
internal object LiveTranslateProtocol {
    fun targetLanguage(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace('_', '-').substringBefore('-').let {
            when (it) { "nb", "no" -> "nb"; "tl" -> "fil"; "iw" -> "he"; else -> it }
        }

    fun session(model: AsrCloudModel, language: AsrLanguage, target: String): String {
        require(model.translatesSpeech)
        return buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                if (model == AsrCloudModel.QWEN_LIVE_TRANSLATE_38) {
                    putJsonArray("output_modalities") { add("text") }
                    // The service also validates voice for text-only output. Explicitly select
                    // the documented 3.8 voice instead of inheriting the incompatible Chelsie.
                    put("voice", "Tina")
                    put("enable_voice_clone", false)
                    // 3.8 defaults to 16 kHz PCM and always returns source transcription.
                    putJsonObject("audio") {
                        putJsonObject("input") {
                            putJsonObject("turn_detection") { put("type", "server_vad") }
                        }
                    }
                } else {
                    putJsonArray("modalities") { add("text") }
                    put("sample_rate", 16000)
                    put("input_audio_format", "pcm")
                    putJsonObject("input_audio_transcription") {
                        put("model", "qwen3-asr-flash-realtime")
                        if (language != AsrLanguage.AUTO) put("language", language.code)
                    }
                    putJsonObject("turn_detection") {
                        put("type", "server_vad")
                        put("silence_duration_ms", 400)
                        // Continuous playback must not interrupt the preceding translation.
                        put("interrupt_response", false)
                    }
                }
                putJsonObject("translation") { put("language", targetLanguage(target)) }
            }
        }.toString()
    }
}

/** Joins ASR item ids to assistant item ids via previous_item_id, never via arrival order.
 * No text is emitted under an invented source id if the relationship hasn't arrived yet.
 * All state is bounded; resets keep segment ids monotonic so reconnects cannot merge rows.
 */
internal class LiveTranslateItems {
    private data class Text(var text: String = "", var final: Boolean = false, var failed: Boolean = false)
    private data class Segment(val id: Long, val source: Text = Text(), val translation: Text = Text())
    private val sources = linkedMapOf<String, Segment>()
    private val outputs = linkedMapOf<String, Text>()
    private val parents = linkedMapOf<String, String>()
    private val responses = linkedMapOf<String, MutableSet<String>>()
    private val seen = linkedSetOf<String>()
    private var nextId = 0L
    private val json = Json { ignoreUnknownKeys = true }

    fun reset() { sources.clear(); outputs.clear(); parents.clear(); responses.clear(); seen.clear() }

    fun accept(message: String): List<SpeechEvent> = runCatching {
        val root = json.parseToJsonElement(message) as? JsonObject ?: return emptyList()
        root.str("event_id")?.let {
            if (!seen.add(it)) return emptyList()
            while (seen.size > LIMIT * 8) seen.remove(seen.first())
        }
        val changed = linkedSetOf<String>()
        fun source(id: String) = sources.getOrPut(id) { Segment(nextId++) }
        fun link(item: JsonObject, previous: String?) {
            if (item.str("role") != "assistant" || previous.isNullOrEmpty()) return
            val id = item.str("id") ?: return
            parents[id] = previous
            val segment = source(previous)
            outputs[id]?.let { snapshot ->
                segment.translation.apply { text = snapshot.text; final = snapshot.final; failed = snapshot.failed }
            }
            changed += previous
        }
        fun translate(id: String, update: (Text) -> Unit) {
            val output = outputs.getOrPut(id) { Text() }
            update(output)
            val parent = parents[id] ?: return
            source(parent).translation.apply { text = output.text; final = output.final; failed = output.failed }
            changed += parent
        }
        when (root.str("type")) {
            "input_audio_buffer.speech_started" -> root.str("item_id")?.let { source(it) }
            "conversation.item.created" -> (root["item"] as? JsonObject)?.let { link(it, root.str("previous_item_id")) }
            "conversation.item.input_audio_transcription.delta",
            "conversation.item.input_audio_transcription.text",
            "conversation.item.input_audio_transcription.completed",
            "conversation.item.input_audio_transcription.failed" -> {
                val id = root.str("item_id") ?: return emptyList()
                val text = source(id).source
                if (!text.final) {
                    when (root.str("type")) {
                        "conversation.item.input_audio_transcription.delta" -> text.text += root.str("delta").orEmpty()
                        "conversation.item.input_audio_transcription.text" -> text.text = root.str("text").orEmpty() + root.str("stash").orEmpty()
                        "conversation.item.input_audio_transcription.completed" -> {
                            text.text = root.str("transcript").orEmpty(); text.final = true
                        }
                        else -> { text.final = true; text.failed = true }
                    }
                    changed += id
                }
            }
            "response.text.delta", "response.text.text", "response.text.done" -> {
                val id = root.str("item_id") ?: return emptyList()
                root.str("response_id")?.let { responses.getOrPut(it) { linkedSetOf() }.add(id) }
                translate(id) { text ->
                    if (!text.final) {
                        when (root.str("type")) {
                            "response.text.delta" -> text.text += root.str("delta").orEmpty()
                            "response.text.text" -> text.text = root.str("text").orEmpty() + root.str("stash").orEmpty()
                            else -> { text.text = root.str("text") ?: text.text; text.final = true }
                        }
                    }
                }
            }
            "response.done" -> {
                val response = root["response"] as? JsonObject ?: return emptyList()
                val failed = response.str("status") != "completed"
                val updated = mutableSetOf<String>()
                (response["output"] as? JsonArray).orEmpty().forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    val id = item.str("id") ?: return@forEach
                    updated += id
                    val full = (item["content"] as? JsonArray).orEmpty().mapNotNull {
                        (it as? JsonObject)?.takeIf { part -> part.str("type") == "text" }?.str("text")
                    }.joinToString("")
                    translate(id) {
                        if (full.isNotEmpty()) it.text = full
                        it.final = true; it.failed = failed
                    }
                }
                // Errors/cancellations may omit output; finish the items already seen for this response.
                responses[response.str("id")].orEmpty().filterNot { it in updated }.forEach { id ->
                    translate(id) { it.final = true; it.failed = failed }
                }
            }
        }
        val events = changed.mapNotNull { key -> sources[key]?.let {
            SpeechEvent(it.id, it.source.text, it.translation.final,
                providerTranslation = it.translation.text, providerTranslationFailed = it.translation.failed)
        } }
        trim(sources); trim(outputs); trim(parents); trim(responses)
        events
    }.getOrDefault(emptyList())

    private fun <T> trim(map: MutableMap<String, T>) { while (map.size > LIMIT) map.remove(map.keys.first()) }
    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    private companion object { const val LIMIT = 256 }
}
