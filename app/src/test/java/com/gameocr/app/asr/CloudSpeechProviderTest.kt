package com.gameocr.app.asr

import com.gameocr.app.data.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class CloudSpeechProviderTest {
    private val connections = AsrCloudConnections(tencentAppId = "123456", volcApiKey = "volc-key", openAiApiKey = "http-key",
        openAiBaseUrl = "https://api.siliconflow.cn/v1", openAiModel = "FunAudioLLM/SenseVoiceSmall")

    @Test fun prerequisitesAndCredentialIsolation_tableDriven() {
        data class Case(val provider: AsrCloudProvider, val config: AsrCloudConnections, val ready: Boolean, val secretKey: String = "test-key")
        listOf(
            Case(AsrCloudProvider.TENCENT, connections, true),
            Case(AsrCloudProvider.TENCENT, connections.copy(tencentAppId = "abc"), false),
            Case(AsrCloudProvider.TENCENT, connections, false, ""),
            Case(AsrCloudProvider.VOLCENGINE, connections, true),
            Case(AsrCloudProvider.VOLCENGINE, connections.copy(volcApiKey = " "), false),
            Case(AsrCloudProvider.OPENAI_COMPATIBLE, connections, true),
            Case(AsrCloudProvider.OPENAI_COMPATIBLE, connections.copy(openAiModel = " "), false),
            Case(AsrCloudProvider.OPENAI_COMPATIBLE, connections.copy(openAiBaseUrl = "not a url"), false),
            Case(AsrCloudProvider.OPENAI_COMPATIBLE, connections.copy(openAiApiKey = ""), false),
        ).forEach { case ->
            val settings = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD, asrCloudProvider = case.provider,
                tencentSecretId = "test-id", tencentSecretKey = case.secretKey, asrCloudConnections = case.config, asrDashScopeApiKey = "ali-key")
            assertEquals(case.toString(), if (case.ready) null else SpeechStartBlock.CLOUD_CONFIGURATION_INCOMPLETE,
                SpeechStartPolicy.blockReason(settings, null))
        }
        assertEquals(null, SpeechStartPolicy.blockReason(Settings(asrEngine = AsrEngineKind.SENSE_VOICE,
            asrCloudProvider = AsrCloudProvider.TENCENT), true))
    }

    @Test fun onlyActiveProviderChangesRestartTheEngineAndNoSecretIsInTheKey() {
        val base = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD, asrCloudProvider = AsrCloudProvider.TENCENT,
            tencentSecretId = "test-id", tencentSecretKey = "test-key", asrCloudConnections = connections)
        assertNotEquals(SpeechStartPolicy.engineKey(base), SpeechStartPolicy.engineKey(base.copy(asrCloudProvider = AsrCloudProvider.VOLCENGINE)))
        assertNotEquals(SpeechStartPolicy.engineKey(base), SpeechStartPolicy.engineKey(base.copy(asrCloudConnections = connections.copy(tencentAppId = "123457"))))
        assertEquals(SpeechStartPolicy.engineKey(base), SpeechStartPolicy.engineKey(base.copy(asrCloudConnections = connections.copy(volcApiKey = "different"))))
        assertEquals(SpeechStartPolicy.engineKey(base), SpeechStartPolicy.engineKey(base.copy(tencentSecretKey = " test-key ")))
        assertFalse(SpeechStartPolicy.engineKey(base).contains("test-key"))
        assertFalse(connections.toString().contains("test-key"))
    }

    @Test fun everyProviderSelectionSurvivesExportButNoConnectionSecretDoes() {
        AsrCloudProvider.entries.forEach { provider ->
            val settings = Settings(asrCloudProvider = provider, asrCloudConnections = connections)
            val encoded = SettingsFieldPolicy.encodePortable(settings)
            assertFalse(encoded.containsKey("asrCloudConnections"))
            assertFalse(encoded.toString().contains("test-key"))
            assertFalse(encoded.toString().contains("http-key"))
            val decoded = SettingsFieldPolicy.decodePortable(encoded).settings
            assertEquals(provider, decoded.asrCloudProvider)
            assertEquals(connections, SettingsFieldPolicy.applyPortable(settings, decoded).asrCloudConnections)
        }
    }

    @Test fun openAiEndpointNormalizesOnlyAudioTranscriptionAddresses_tableDriven() {
        mapOf(
            "https://asr.example" to "https://asr.example/v1/audio/transcriptions",
            "https://asr.example/v1/" to "https://asr.example/v1/audio/transcriptions",
            "https://asr.example/proxy/v1" to "https://asr.example/proxy/v1/audio/transcriptions",
            "http://127.0.0.1:9000/v1/audio/transcriptions/" to "http://127.0.0.1:9000/v1/audio/transcriptions",
        ).forEach { (input, expected) -> assertEquals(expected, OpenAiAsrProtocol.endpoint(input).toString()) }
        listOf("bad", "ftp://asr.example", "https://key@asr.example/v1", "https://asr.example/v1?key=secret",
            "https://asr.example/v1/chat/completions", "https://asr.example/v1/messages").forEach {
            assertNull(it, OpenAiAsrProtocol.endpoint(it))
        }
    }

    @Test fun wavHeaderAndResponseValidateFormatAndMalformedBodies_tableDriven() {
        val pcm = byteArrayOf(0, 1, -1, 0)
        val wav = OpenAiAsrProtocol.wav(pcm)
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, buffer.getInt(24)); assertEquals(pcm.size, buffer.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        assertThrows(IllegalArgumentException::class.java) { OpenAiAsrProtocol.wav(byteArrayOf(0)) }
        assertEquals("hello", OpenAiAsrProtocol.transcript("""{"text":" hello "}"""))
        assertEquals("", OpenAiAsrProtocol.transcript("""{"text":""}"""))
        assertEquals("你好", OpenAiAsrProtocol.transcript("""{"text":"<|zh|><|NEUTRAL|>你好"}"""))
        listOf("bad", "{}", """{"text":123}""", """{"error":{"message":"denied"}}""").forEach {
            assertTrue(runCatching { OpenAiAsrProtocol.transcript(it) }.isFailure)
        }
    }

    @Test fun actualMultipartRequestContainsWaveFileAndOmitsAutomaticLanguage_tableDriven() {
        listOf(AsrLanguage.AUTO, AsrLanguage.EN).forEach { language ->
            val request = OpenAiAsrProtocol.request(connections, language, byteArrayOf(0, 0))
            assertEquals("https://api.siliconflow.cn/v1/audio/transcriptions", request.url.toString())
            assertEquals("Bearer http-key", request.header("Authorization"))
            assertTrue(request.body!!.contentType().toString().startsWith("multipart/form-data;"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val body = buffer.readUtf8()
            assertTrue(body.contains("name=\"file\"; filename=\"speech.wav\""))
            assertTrue(body.contains("Content-Type: audio/wav"))
            assertTrue(body.contains("RIFF"))
            assertTrue(body.contains("FunAudioLLM/SenseVoiceSmall"))
            assertEquals(language != AsrLanguage.AUTO, body.contains("name=\"language\""))
        }
    }

    @Test fun tencentSignatureMatchesKnownFixtureAndEncodesReservedCharacters() {
        val request = TencentSpeechProtocol.request(connections, "voice-1", 1700000000L, 123, "test-id", "test-key")
        assertEquals("NIHjhDOu/hPMLqEpQXCVOKwuAao=", request.url.queryParameter("signature"))
        assertTrue(request.url.encodedQuery.orEmpty().contains("%2F"))
        assertEquals(TencentSpeechProtocol.MODEL, request.url.queryParameter("engine_model_type"))
        assertEquals("1", request.url.queryParameter("sentence_strategy"))
        assertFalse(request.url.toString().contains("test-key"))
    }

    @Test fun tencentParsesNestedSentenceListAndCompatibilityShapes_tableDriven() {
        val sentence = """{"sentence_id":2,"sentence_type":1,"sentence":"原文"}"""
        val shapes = listOf("""{"sentence_list":[$sentence]}""", "[$sentence]", sentence)
        shapes.forEach { shape ->
            assertEquals(listOf(NativeSpeechResult(2, "原文", true)), TencentSpeechProtocol.parse("""{"code":0,"sentences":$shape}""").results)
        }
        assertEquals(listOf(NativeSpeechResult(2, "原文", true)), TencentSpeechProtocol.parse(
            """{"code":0,"result":{"speaker_sentences":{"sentence_list":[$sentence]}}}""").results)
        assertTrue(TencentSpeechProtocol.parse("""{"code":0}""").results.isEmpty())
        assertTrue(TencentSpeechProtocol.parse("""{"code":0,"final":1}""").finished)
        assertEquals(4002, TencentSpeechProtocol.parse("""{"code":4002}""").error)
        assertTrue(runCatching { TencentSpeechProtocol.parse("{}") }.isFailure)
    }

    private fun serverFrame(payload: String, flags: Int = 1, gzip: Boolean = true): ByteString {
        val bytes = payload.toByteArray()
        val data = if (gzip) ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray() else bytes
        return ByteBuffer.allocate(8 + (if (flags and 1 != 0) 4 else 0) + data.size).apply {
            put(0x11.toByte()); put((0x90 or flags).toByte()); put((if (gzip) 0x11 else 0x10).toByte()); put(0)
            if (flags and 1 != 0) putInt(if (flags and 2 != 0) -1 else 1)
            putInt(data.size); put(data)
        }.array().toByteString()
    }

    @Test fun volcConfigurationUsesPcmAndLongerPauseWithFinalizationEnabled() {
        val frame = VolcSpeechProtocol.configuration().toByteArray()
        assertEquals(0x11, frame[1].toInt())
        assertEquals(1, ByteBuffer.wrap(frame, 4, 4).int)
        val root = Json.parseToJsonElement(GZIPInputStream(ByteArrayInputStream(frame, 12, frame.size - 12)).readBytes().decodeToString()).jsonObject
        assertEquals("pcm", root.getValue("audio").jsonObject.getValue("format").jsonPrimitive.content)
        assertEquals(1300, root.getValue("request").jsonObject.getValue("end_window_size").jsonPrimitive.int)
        assertTrue(root.getValue("request").jsonObject.getValue("enable_nonstream").jsonPrimitive.boolean)
        val request = VolcSpeechProtocol.request("key", "id")
        assertEquals(VolcSpeechProtocol.RESOURCE, request.header("X-Api-Resource-Id"))
        assertEquals("key", request.header("X-Api-Key"))
        assertEquals(0x23, VolcSpeechProtocol.audio(ByteString.EMPTY, true)[1].toInt())
    }

    @Test fun volcServerFramesHandleSequenceCompressionFinalErrorsAndBadLengths_tableDriven() {
        val payload = """{"result":{"utterances":[{"start_time":100,"text":"hello","definite":true}]}}"""
        listOf(0, 1, 2, 3).forEach { flags -> listOf(false, true).forEach { gzip ->
            val parsed = VolcSpeechProtocol.parse(serverFrame(payload, flags, gzip))
            assertEquals(listOf(NativeSpeechResult(100, "hello", true)), parsed.results)
            assertEquals(flags and 2 != 0, parsed.finished)
        } }
        val error = ByteBuffer.allocate(12).apply { put(byteArrayOf(0x11, 0xf0.toByte(), 0x10, 0)); putInt(45000001); putInt(0) }.array().toByteString()
        assertEquals(45000001, VolcSpeechProtocol.parse(error).error)
        listOf(byteArrayOf(), byteArrayOf(0x11), byteArrayOf(0x11, 0x91.toByte(), 0x10, 0, 0, 0, 0, 1),
            byteArrayOf(0x11, 0x90.toByte(), 0x10, 0, 0x7f, -1, -1, -1)).forEach {
            assertTrue(runCatching { VolcSpeechProtocol.parse(it.toByteString()) }.isFailure)
        }
    }

    @Test fun repeatedFullHistoryDoesNotResurrectOldRowsAndResetSeparatesSessions() {
        val items = NativeSpeechItems()
        val first = items.accept(NativeSpeechResult(0, "draft", false))!!
        val final = items.accept(NativeSpeechResult(0, "done", true))!!
        assertEquals(first.segmentId, final.segmentId)
        repeat(300) { items.accept(NativeSpeechResult(it.toLong(), "s$it", true)) }
        repeat(300) { assertNull(items.accept(NativeSpeechResult(it.toLong(), "s$it", true))) }
        items.reset()
        assertTrue(items.accept(NativeSpeechResult(0, "new", true))!!.segmentId > final.segmentId)
    }
}
