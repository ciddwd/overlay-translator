package com.gameocr.app.asr

import com.gameocr.app.data.AsrCloudConnections
import com.gameocr.app.data.AsrLanguage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenAI audio transcription and SiliconFlow multipart file/model protocol, verified HTTP 200:
 * https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create
 * https://docs.siliconflow.cn/docs/api/audio-transcriptions-post
 */
internal object OpenAiAsrProtocol {
    fun endpoint(base: String): HttpUrl? {
        val url = base.trim().toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        val path = url.encodedPath.trimEnd('/')
        if (path.endsWith("/audio/transcriptions")) return url.newBuilder().encodedPath(path).build()
        if (path.endsWith("/chat/completions") || path.endsWith("/messages")) return null
        return url.newBuilder().encodedPath("${path.ifEmpty { "/v1" }}/audio/transcriptions").build()
    }

    fun transcript(body: String): String {
        val root = Json.parseToJsonElement(body).jsonObject
        val text = (root["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("ASR response has no text field")
        return SpeechTextPolicy.clean(text)
    }

    fun modelsEndpoint(base: String): HttpUrl? = endpoint(base)?.let { transcription ->
        transcription.newBuilder()
            .encodedPath(transcription.encodedPath.removeSuffix("/audio/transcriptions") + "/models")
            .build()
    }

    fun request(config: AsrCloudConnections, language: AsrLanguage, pcm: ByteArray): Request = Request.Builder()
        .url(endpoint(config.openAiBaseUrl) ?: error("Invalid ASR endpoint"))
        .header("Authorization", "Bearer ${config.openAiApiKey.trim()}")
        .post(MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("model", config.openAiModel.trim())
            addFormDataPart("file", "speech.wav", wav(pcm).toRequestBody("audio/wav".toMediaType()))
            if (language != AsrLanguage.AUTO) addFormDataPart("language", language.code)
        }.build()).build()

    fun wav(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0)
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
}
