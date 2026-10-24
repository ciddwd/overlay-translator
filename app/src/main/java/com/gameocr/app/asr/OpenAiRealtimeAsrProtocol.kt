package com.gameocr.app.asr

import com.gameocr.app.data.AsrLanguage
import com.gameocr.app.data.AsrHintMode
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString

/** OpenAI GA transcription sessions, not conversation sessions or file-response SSE.
 * https://developers.openai.com/api/docs/guides/realtime-transcription (verified HTTP 200).
 * Use client VAD commits for both live and committed-turn transcription models.
 */
internal object OpenAiRealtimeAsrProtocol {
    fun endpoint(base: String): HttpUrl? {
        val normalized = base.trim().replaceFirst(Regex("^wss://"), "https://")
            .replaceFirst(Regex("^ws://"), "http://")
        val url = normalized.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        if (url.query != null && url.query != "intent=transcription") return null
        val path = url.encodedPath.trimEnd('/')
        if (path.endsWith("/chat/completions") || path.endsWith("/messages")) return null
        val root = when {
            path.endsWith("/audio/transcriptions") -> path.removeSuffix("/audio/transcriptions")
            path.endsWith("/realtime") -> path.removeSuffix("/realtime")
            else -> path.ifEmpty { "/v1" }
        }
        return url.newBuilder().encodedPath("$root/realtime").query(null)
            .addQueryParameter("intent", "transcription").build()
    }

    fun session(model: String, language: AsrLanguage, hints: AsrRecognitionHints = AsrRecognitionHints()): String = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "transcription")
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", 24000) }
                    putJsonObject("transcription") {
                        put("model", model.trim())
                        hints.promptWith(if (hints.mode == AsrHintMode.PROMPT_HOTWORDS) 200 else 2000)
                            .takeIf(String::isNotBlank)?.let { put("prompt", it) }
                        if (hints.mode == AsrHintMode.KEYWORDS && hints.hotwords.isNotEmpty())
                            putJsonArray("keywords") { hints.hotwords.forEach { add(it) } }
                        if (language != AsrLanguage.AUTO) {
                            if (model.trim().startsWith("gpt-live-transcribe") || model.trim().startsWith("gpt-transcribe")) {
                                putJsonArray("languages") { add(language.code) }
                            } else put("language", language.code)
                        }
                    }
                    put("turn_detection", JsonNull)
                }
            }
        }
    }.toString()

    fun audio(pcm: ByteString): String = buildJsonObject {
        put("type", "input_audio_buffer.append"); put("audio", pcm.base64())
    }.toString()

    fun commit(): String = """{"type":"input_audio_buffer.commit"}"""

    fun parse(message: String): JsonObject? = runCatching {
        Json.parseToJsonElement(message) as? JsonObject
    }.getOrNull()
}

/** Bounds utterances and reconciles deltas/finals by item_id, never completion arrival order. */
internal class OpenAiRealtimeAsrItems {
    private data class Item(val segment: Long, val startedAt: Long, var text: String = "", var final: Boolean = false,
        var emittedPartial: String? = null)
    private val items = linkedMapOf<String, Item>()
    private val completed = linkedSetOf<String>()
    private var nextSegment = 0L

    fun reset() { items.clear(); completed.clear() } // Segment IDs never repeat after reset.

    fun timedOut(nowMs: Long): Boolean = items.values.firstOrNull()?.let { nowMs - it.startedAt >= 30000 } ?: false

    fun accept(root: JsonObject, nowMs: Long = 0L): List<SpeechEvent> {
        val type = root.str("type") ?: return emptyList()
        if (type !in setOf("input_audio_buffer.committed",
                "conversation.item.input_audio_transcription.delta",
                "conversation.item.input_audio_transcription.completed")) return emptyList()
        val id = root.str("item_id")?.takeIf(String::isNotBlank) ?: return emptyList()
        if (id in completed) return emptyList()
        val existing = items[id]
        // A final without a prior commit/delta cannot safely establish speech ordering.
        if (existing == null && type.endsWith(".completed")) return emptyList()
        if (existing == null && items.size >= 64) error("Too many pending transcription items")
        val item = existing ?: Item(nextSegment++, nowMs).also { items[id] = it }
        if (item.final || type == "input_audio_buffer.committed") return emptyList()
        if (type.endsWith(".delta")) {
            val delta = root.str("delta")?.takeIf(String::isNotEmpty) ?: return emptyList()
            // Limit retained untrusted server text, including malformed streams without finals.
            check(item.text.length + delta.length <= 32768)
            item.text += delta
        } else {
            val text = root.str("transcript") ?: return emptyList()
            check(text.length <= 32768)
            item.text = text
            item.final = true
        }
        return buildList {
            // The shared transcript has one open row: defer later items until earlier finals.
            while (items.isNotEmpty()) {
                val first = items.entries.first()
                val value = first.value
                if (!value.final) {
                    val text = SpeechTextPolicy.clean(value.text)
                    if (text.isNotEmpty() && text != value.emittedPartial) {
                        value.emittedPartial = text
                        add(SpeechEvent(value.segment, text, false))
                    }
                    break
                }
                add(SpeechEvent(value.segment, SpeechTextPolicy.clean(value.text), true))
                items.remove(first.key)
                completed.add(first.key)
                while (completed.size > 256) completed.remove(completed.first())
            }
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content

/** Stateful 3:2 linear resampling; chunk boundaries produce the same PCM as a single input. */
internal class RealtimePcmResampler {
    private var previous: Float? = null
    private var inputIndex = -1L
    private var nextOutputPosition = 0L // Source position in units of 1/3 sample.

    fun reset() { previous = null; inputIndex = -1; nextOutputPosition = 0 }

    fun convert(samples: FloatArray): ByteString {
        val output = okio.Buffer()
        for (sample in samples) {
            val current = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
            inputIndex++
            val end = inputIndex * 3
            while (nextOutputPosition <= end) {
                val before = previous ?: current
                val fraction = (nextOutputPosition - (inputIndex - 1) * 3).toFloat() / 3f
                val value = if (previous == null) current else before + (current - before) * fraction
                output.writeShortLe((value * 32767f).toInt().coerceIn(-32768, 32767))
                nextOutputPosition += 2
            }
            previous = current
        }
        return output.readByteString()
    }
}
