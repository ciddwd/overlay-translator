package com.gameocr.app.asr

import com.gameocr.app.data.AsrCloudConnections
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal data class NativeSpeechResult(val key: Long, val text: String, val final: Boolean)
internal data class NativeSpeechPacket(
    val results: List<NativeSpeechResult> = emptyList(), val error: Int? = null, val finished: Boolean = false,
    val errorMessage: String? = null,
    val translations: List<TencentSpeechTranslationResult> = emptyList(),
)

/** Official protocol and raw SDK response examples, verified HTTP 200:
 * https://cloud.tencent.com/document/product/1093/131127
 * https://cloud.tencent.com/document/product/1093/133355
 */
internal object TencentSpeechProtocol {
    const val MODEL = "16k_zh_en_2.0"
    fun request(config: AsrCloudConnections, voice: String, now: Long, nonce: Int, secretId: String, secretKey: String, language: com.gameocr.app.data.AsrLanguage = com.gameocr.app.data.AsrLanguage.ZH): Request {
        require(!config.tencentModel.translatesSpeech)
        require(config.tencentAppId.trim().matches(Regex("[0-9]+")))
        val params = sortedMapOf(
            "engine_model_type" to if (config.tencentModel == com.gameocr.app.data.TencentAsrModel.STANDARD) com.gameocr.app.data.TencentSpeechLanguagePolicy.standardEngine(language) else config.tencentModel.modelId, "expired" to (now + 3600).toString(),
            "needvad" to "1", "nonce" to nonce.toString(), "secretid" to secretId.trim(),
            "sentence_strategy" to "1", "timestamp" to now.toString(), "voice_format" to "1", "voice_id" to voice,
        )
        if (config.tencentModel == com.gameocr.app.data.TencentAsrModel.STANDARD) {
            params.remove("sentence_strategy")
            if (language in listOf(com.gameocr.app.data.AsrLanguage.ZH, com.gameocr.app.data.AsrLanguage.EN)) params["vad_silence_time"] = "1300"
            if (language == com.gameocr.app.data.AsrLanguage.ZH) params["max_speak_time"] = "10000"
        }
        return signedRequest("asr.cloud.tencent.com/asr/v2/${config.tencentAppId.trim()}", params, secretKey)
    }

    internal fun signedRequest(path: String, params: java.util.SortedMap<String, String>, secretKey: String): Request {
        val raw = "$path?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secretKey.trim().toByteArray(), "HmacSHA1")) }
        val signature = mac.doFinal(raw.toByteArray()).toByteString().base64()
        val url = "https://$path".toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("signature", signature)
        }.build()
        return Request.Builder().url(url).build()
    }

    fun parse(message: String): NativeSpeechPacket {
        val root = Json.parseToJsonElement(message).jsonObject
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: error("Missing Tencent status")
        if (code != 0) return NativeSpeechPacket(error = code, errorMessage = root["message"]?.jsonPrimitive?.contentOrNull)
        val regular = root["result"] as? JsonObject
        if (regular?.get("voice_text_str") != null) {
            val id = regular["index"]?.jsonPrimitive?.longOrNull ?: error("Missing Tencent sentence index")
            val slice = regular["slice_type"]?.jsonPrimitive?.intOrNull ?: error("Missing Tencent slice type")
            return NativeSpeechPacket(results = listOf(NativeSpeechResult(id,
                regular["voice_text_str"]?.jsonPrimitive?.contentOrNull.orEmpty(), slice == 2)),
                finished = root["final"]?.jsonPrimitive?.intOrNull == 1)
        }
        val sentences = root["sentences"] ?: (root["result"] as? JsonObject)?.get("speaker_sentences")
        val entries = when (sentences) {
            is JsonArray -> sentences.toList()
            is JsonObject -> (sentences["sentence_list"] as? JsonArray)?.toList() ?: listOf(sentences)
            null, JsonNull -> emptyList()
            else -> error("Unexpected Tencent sentences")
        }
        return NativeSpeechPacket(results = entries.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val id = item["sentence_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            NativeSpeechResult(id, item["sentence"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                item["sentence_type"]?.jsonPrimitive?.intOrNull == 1)
        }, finished = root["final"]?.jsonPrimitive?.intOrNull == 1)
    }
}

/** Official binary v1 ASR protocol (both URLs verified HTTP 200):
 * https://docs.volcengine.com/docs/DoubaoVoice/LargemodelstreamingautomaticspeechrecognitionAPI?lang=zh
 * https://docs.volcengine.com/docs/DoubaoVoice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh
 */
internal object VolcSpeechProtocol {
    const val RESOURCE = "volc.seedasr.sauc.duration"
    fun request(apiKey: String, id: String, resource: String = RESOURCE): Request = Request.Builder()
        .url("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async")
        .header("X-Api-Key", apiKey.trim()).header("X-Api-Resource-Id", resource)
        .header("X-Api-Request-Id", id).build()

    fun configuration(sequence: Int = 1): ByteString = frame(0x11, 0x11, buildJsonObject {
        putJsonObject("user") { put("uid", "overlay-translator") }
        putJsonObject("audio") {
            put("format", "pcm"); put("codec", "raw"); put("rate", 16000); put("bits", 16); put("channel", 1)
        }
        putJsonObject("request") {
            put("model_name", "bigmodel"); put("enable_itn", true); put("enable_punc", true)
            put("show_utterances", true); put("enable_nonstream", true)
            // Allow sentence-internal pauses, matching the restored Paraformer setting.
            put("end_window_size", 1300)
        }
    }.toString().toByteArray(), sequence)

    fun audio(pcm: ByteString, last: Boolean = false, sequence: Int = 2): ByteString =
        frame(if (last) 0x23 else 0x21, 0x01, pcm.toByteArray(), if (last) -sequence else sequence)

    private fun frame(type: Int, serialization: Int, payload: ByteArray, sequence: Int): ByteString {
        require(sequence != 0)
        val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(payload) } }.toByteArray()
        return ByteBuffer.allocate(12 + compressed.size).apply {
            put(0x11.toByte()); put(type.toByte()); put(serialization.toByte()); put(0)
            putInt(sequence); putInt(compressed.size); put(compressed)
        }.array().toByteString()
    }

    fun parse(frame: ByteString): NativeSpeechPacket {
        val bytes = frame.toByteArray()
        require(bytes.size >= 8 && bytes[0].toInt() ushr 4 == 1)
        val headerSize = (bytes[0].toInt() and 15) * 4
        require(headerSize >= 4 && headerSize <= bytes.size - 4)
        val type = (bytes[1].toInt() and 255) ushr 4
        val flags = bytes[1].toInt() and 15
        var offset = headerSize
        if (type == 15) {
            require(offset + 8 <= bytes.size)
            val code = ByteBuffer.wrap(bytes, offset, 4).int
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).int
            offset += 8
            val raw = decodePayload(bytes, offset, size, bytes[2].toInt() and 15)
            return NativeSpeechPacket(error = code, errorMessage = raw.decodeToString())
        }
        require(type == 9)
        if (flags and 1 != 0) offset += 4
        require(offset + 4 <= bytes.size)
        val size = ByteBuffer.wrap(bytes, offset, 4).int
        offset += 4
        val payload = decodePayload(bytes, offset, size, bytes[2].toInt() and 15)
        val root = Json.parseToJsonElement(payload.decodeToString()).jsonObject
        val result = root["result"]
        val results = when (result) {
            is JsonObject -> listOf(result)
            is JsonArray -> result.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
        return NativeSpeechPacket(results = results.flatMap { group ->
            (group["utterances"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val start = item["start_time"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                NativeSpeechResult(start, item["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    item["definite"]?.jsonPrimitive?.booleanOrNull == true)
            }
        }, finished = flags and 2 != 0)
    }

    private fun decodePayload(bytes: ByteArray, offset: Int, size: Int, compression: Int): ByteArray {
        require(size in 0..MAX_PAYLOAD && offset >= 0 && offset <= bytes.size && size <= bytes.size - offset)
        val raw = bytes.copyOfRange(offset, offset + size)
        if (raw.isEmpty()) return raw
        return when (compression) {
            0 -> raw
            1 -> GZIPInputStream(ByteArrayInputStream(raw)).use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    require(output.size() + count <= MAX_PAYLOAD)
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            else -> error("Unsupported ASR compression")
        }
    }

    private const val MAX_PAYLOAD = 4 * 1024 * 1024
}

/** Native protocols may repeat the complete history on every audio packet. */
internal class NativeSpeechItems {
    private data class Item(val id: Long, var text: String = "", var final: Boolean = false)
    private val items = linkedMapOf<Long, Item>()
    private var retiredThrough = -1L
    private var nextId = 0L
    fun reset() { items.clear(); retiredThrough = -1L }
    fun accept(result: NativeSpeechResult): SpeechEvent? {
        if (result.key < 0 || result.key <= retiredThrough) return null
        val item = items.getOrPut(result.key) { Item(nextId++) }
        if (item.final || (!result.final && item.text == result.text)) return null
        item.text = result.text; item.final = result.final
        while (items.size > 256) {
            val first = items.keys.minOrNull() ?: break
            items.remove(first); retiredThrough = maxOf(retiredThrough, first)
        }
        return SpeechEvent(item.id, result.text, result.final).takeIf { it.text.isNotBlank() }
    }
}
