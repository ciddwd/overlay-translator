package com.gameocr.app.asr

import com.gameocr.app.data.*
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class AsrHintProtocolTest {
    private val c = AsrCloudConnections(openAiApiKey = "openai-key", openAiModel = "gpt-transcribe",
        openAiBaseUrl = "https://api.openai.com/v1", tencentAppId = "123456")
    private val hints = AsrRecognitionHints("Medical course", listOf("Winterfell", "Stark"),
        vocabularyId = "vocab-123", mode = AsrHintMode.KEYWORDS)
    private fun body(request: Request) = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
    private fun multipart(request: Request): Map<String, List<String>> =
        (request.body as okhttp3.MultipartBody).parts.groupBy({ it.headers!!.get("Content-Disposition")!!
            .substringAfter("name=\"").substringBefore('"') }, { Buffer().also(it.body::writeTo).readUtf8() })

    @Test fun openAiMultipartUsesDocumentedFieldsAndOmitsEmptyHints() {
        val fields = multipart(OpenAiAsrProtocol.request(c, AsrLanguage.EN, byteArrayOf(0, 0), hints))
        assertEquals(listOf("Medical course"), fields["prompt"])
        assertEquals(listOf("Winterfell", "Stark"), fields["keywords[]"])
        assertEquals(listOf("en"), fields["languages[]"]); assertFalse(fields.containsKey("language"))
        assertEquals("RIFF", fields.getValue("file").single().take(4))
        val empty = multipart(OpenAiAsrProtocol.request(c.copy(openAiModel = "whisper-1"), AsrLanguage.EN, byteArrayOf(0, 0)))
        assertFalse(empty.containsKey("prompt")); assertFalse(empty.containsKey("keywords[]"))
        assertEquals(listOf("en"), empty["language"])
        val legacy = multipart(OpenAiAsrProtocol.request(c.copy(openAiModel = "whisper-1"), AsrLanguage.AUTO,
            byteArrayOf(0, 0), hints.copy(mode = AsrHintMode.PROMPT_HOTWORDS, prompt = "中".repeat(300))))
        assertTrue(legacy.getValue("prompt").single().startsWith("Winterfell, Stark\n"))
        assertTrue(legacy.getValue("prompt").single().toByteArray().size <= 200)
        assertFalse(legacy.containsKey("keywords[]"))
    }

    @Test fun tencentIncludesWeightedWordsInTheSignedQuery() {
        val plain = TencentSpeechProtocol.request(c, "voice", 1000, 9, "secret-id", "secret-key")
        val with = TencentSpeechProtocol.request(c, "voice", 1000, 9, "secret-id", "secret-key", hints = hints)
        assertNull(plain.url.queryParameter("hotword_list"))
        assertEquals("Winterfell|5,Stark|5", with.url.queryParameter("hotword_list"))
        assertNotEquals(plain.url.queryParameter("signature"), with.url.queryParameter("signature"))
        val params = with.url.queryParameterNames.filterNot { it == "signature" }
            .associateWith { with.url.queryParameter(it)!! }.toSortedMap()
        assertEquals(with.url, TencentSpeechProtocol.signedRequest(
            "asr.cloud.tencent.com/asr/v2/123456", params, "secret-key").url)
    }

    @Test fun volcCorpusContextIsAJsonStringInsideTheCompressedRequest() {
        fun configuration(h: AsrRecognitionHints): JsonObject {
            val bytes = VolcSpeechProtocol.configuration(hints = h).toByteArray()
            return Json.parseToJsonElement(GZIPInputStream(ByteArrayInputStream(bytes, 12, bytes.size - 12))
                .use { it.readBytes().decodeToString() }).jsonObject["request"]!!.jsonObject
        }
        assertFalse(configuration(AsrRecognitionHints()).containsKey("corpus"))
        val context = Json.parseToJsonElement(configuration(hints)["corpus"]!!.jsonObject["context"]!!.jsonPrimitive.content).jsonObject
        assertEquals("dialog_ctx", context["context_type"]!!.jsonPrimitive.content)
        assertEquals("Medical course", context["context_data"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("Winterfell", "Stark"), context["hotwords"]!!.jsonArray.map { it.jsonObject["word"]!!.jsonPrimitive.content })
    }

    @Test fun aliyunVocabularyIsSentOnlyOnTheModelsThatSupportIt() {
        for (model in AsrCloudModel.entries.filterNot { it.usesRealtimeProtocol }) {
            val params = Json.parseToJsonElement(ParaformerRealtimeEngine.runTask("task", AsrLanguage.AUTO, model, hints))
                .jsonObject["payload"]!!.jsonObject["parameters"]!!.jsonObject
            assertEquals(model.toString(), model in setOf(AsrCloudModel.PARAFORMER, AsrCloudModel.FUN_ASR,
                AsrCloudModel.QWEN_AUDIO_STREAMING), params.containsKey("vocabulary_id"))
            assertEquals(model == AsrCloudModel.QWEN_AUDIO_STREAMING, params.containsKey("vocabulary"))
            if (model == AsrCloudModel.QWEN_AUDIO_STREAMING) assertEquals(4, params["vocabulary"]!!.jsonObject["Stark"]!!.jsonPrimitive.int)
            assertFalse(params.containsKey("prompt")); assertFalse(params.containsKey("hotwords"))
        }
    }

    @Test fun realtimeSessionUsesOnlyConfiguredPromptAndKeywords() {
        fun transcription(model: String, h: AsrRecognitionHints) = Json.parseToJsonElement(
            OpenAiRealtimeAsrProtocol.session(model, AsrLanguage.EN, h))
            .jsonObject["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject["transcription"]!!.jsonObject
        val json = transcription("gpt-live-transcribe", hints)
        assertEquals("Medical course", json["prompt"]!!.jsonPrimitive.content)
        assertEquals(listOf("Winterfell", "Stark"), json["keywords"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("en"), json["languages"]!!.jsonArray.map { it.jsonPrimitive.content })
        val empty = transcription("gpt-4o-mini-transcribe", AsrRecognitionHints())
        assertEquals(setOf("model", "language"), empty.keys)
    }
}
