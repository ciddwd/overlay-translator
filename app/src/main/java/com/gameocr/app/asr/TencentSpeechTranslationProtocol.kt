package com.gameocr.app.asr

import com.gameocr.app.data.*
import kotlinx.serialization.json.*
import okhttp3.Request

/** https://cloud.tencent.com/document/product/1093/127565 (verified HTTP 200). */
internal object TencentSpeechTranslationProtocol {
    fun request(config: AsrCloudConnections, voice: String, now: Long, nonce: Int,
        secretId: String, secretKey: String, source: AsrLanguage, target: String): Request {
        require(config.tencentModel.translatesSpeech)
        require(config.tencentAppId.trim().matches(Regex("[0-9]+")))
        require(TencentSpeechLanguagePolicy.supports(source, target))
        val params = sortedMapOf("expired" to (now + 3600).toString(), "nonce" to nonce.toString(),
            "secretid" to secretId.trim(), "source" to source.code,
            "target" to TencentSpeechLanguagePolicy.target(target), "timestamp" to now.toString(),
            "trans_model" to config.tencentModel.modelId, "voice_format" to "1", "voice_id" to voice,
            "enable_tts" to "0")
        if (source in listOf(AsrLanguage.ZH, AsrLanguage.EN, AsrLanguage.ZH_EN)) {
            params["vad_silence_time"] = "1300"
            params["max_speak_time"] = "10000"
        }
        return TencentSpeechProtocol.signedRequest("asr.cloud.tencent.com/asr/speech_translate/${config.tencentAppId.trim()}", params, secretKey)
    }

    fun parse(message: String): NativeSpeechPacket {
        val root = Json.parseToJsonElement(message).jsonObject
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: error("Missing Tencent translation status")
        if (code != 0) return NativeSpeechPacket(error = code, errorMessage = root["message"]?.jsonPrimitive?.contentOrNull)
        val finished = root["final"]?.jsonPrimitive?.intOrNull == 1
        val result = root["result"] as? JsonObject ?: return NativeSpeechPacket(finished = finished)
        val id = root["sentence_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("Missing Tencent translation sentence id")
        return NativeSpeechPacket(finished = finished, translations = listOf(TencentSpeechTranslationResult(id,
            result["source_text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            result["target_text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            result["sentence_end"]?.jsonPrimitive?.booleanOrNull == true)))
    }
}

internal data class TencentSpeechTranslationResult(val id: String, val source: String, val target: String, val final: Boolean)

/** Stable local segment ids keep partial and final translation on the same subtitle row. */
internal class TencentSpeechTranslationItems {
    private data class Item(val segment: Long, var source: String = "", var target: String = "", var final: Boolean = false)
    private val items = linkedMapOf<String, Item>()
    private var nextId = 0L
    fun reset() { items.clear() }
    fun accept(result: TencentSpeechTranslationResult): SpeechEvent? {
        val item = items.getOrPut(result.id) { Item(nextId++) }
        if (item.final) return null
        val source = result.source.ifEmpty { item.source }
        val target = result.target.ifEmpty { item.target }
        if (!result.final && source == item.source && target == item.target) return null
        item.source = source; item.target = target; item.final = result.final
        while (items.size > 256) items.remove(items.keys.first())
        return SpeechEvent(item.segment, source, result.final, providerTranslation = target,
            providerTranslationFailed = result.final && target.isBlank())
            .takeIf { source.isNotBlank() || target.isNotBlank() }
    }
}
