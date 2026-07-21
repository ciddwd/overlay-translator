package com.gameocr.app.tts

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TtsProvider
import com.gameocr.app.data.VolcengineTtsResource
import com.gameocr.app.data.parseTtsProvider
import java.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolcengineTtsPolicyTest {

    @Test
    fun providerMigration_mapsLegacyAndCurrentValues() {
        data class Case(val raw: String, val expected: TtsProvider)

        listOf(
            Case("SYSTEM", TtsProvider.SYSTEM),
            Case("VOLCENGINE", TtsProvider.VOLCENGINE),
            Case("VOLCENGINE_GATEWAY", TtsProvider.VOLCENGINE),
            Case("ALIYUN_GATEWAY", TtsProvider.SYSTEM),
            Case("unknown", TtsProvider.SYSTEM),
        ).forEach { case ->
            assertEquals(case.raw, case.expected, parseTtsProvider(case.raw))
            assertEquals(
                "serialized ${case.raw}",
                case.expected,
                Json.decodeFromString<TtsProvider>("\"${case.raw}\""),
            )
        }
    }

    @Test
    fun explicitLanguage_mapsEveryDocumentedLanguageFamily() {
        data class Case(val input: String, val expected: String?)

        listOf(
            Case("zh-TW", "zh-cn"),
            Case("yue", "zh-cn"),
            Case("en-US", "en"),
            Case("ja-JP", "ja"),
            Case("es-ES", "es-mx"),
            Case("pt-BR", "pt-br"),
            Case("pt-PT", "pt"),
            Case("ko", "ko"),
            Case("fil-PH", "fil"),
            Case("tl", "fil"),
            Case("sv-SE", "sv"),
            Case("auto", null),
            Case("hi", null),
        ).forEach { case ->
            assertEquals(case.input, case.expected, volcengineExplicitLanguage(case.input))
        }
    }

    @Test
    fun requestHeaders_mapResourceAndRejectMissingKey() {
        data class Case(val resource: VolcengineTtsResource, val expectedId: String)

        listOf(
            Case(VolcengineTtsResource.PRESET_VOICE_2_0, "seed-tts-2.0"),
            Case(VolcengineTtsResource.VOICE_CLONE_2_0, "seed-icl-2.0"),
        ).forEach { case ->
            val headers = volcengineTtsHeaders(" api-key ", case.resource, "request-id")
            assertEquals(case.expectedId, headers["X-Api-Resource-Id"])
            assertEquals("api-key", headers["X-Api-Key"])
            assertEquals("request-id", headers["X-Api-Request-Id"])
            assertEquals("*", headers["X-Control-Require-Usage-Tokens-Return"])
        }

        val error = runCatching {
            volcengineTtsHeaders("  ", VolcengineTtsResource.PRESET_VOICE_2_0, "id")
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("API Key"))
    }

    @Test
    fun payload_mapsPresetAndCloneResourceRules() {
        data class Case(
            val name: String,
            val resource: VolcengineTtsResource,
            val language: String,
            val pitch: Int,
            val context: String,
            val toneFidelity: Boolean,
            val expectedLanguage: String?,
            val expectedPitch: Int?,
            val expectedContext: String?,
            val expectedModel: String?,
            val expectedToneFidelity: Boolean?,
        )

        listOf(
            Case(
                "preset Japanese",
                VolcengineTtsResource.PRESET_VOICE_2_0,
                "ja-JP",
                99,
                " calm and warm ",
                true,
                "ja",
                12,
                "calm and warm",
                null,
                null,
            ),
            Case(
                "clone English",
                VolcengineTtsResource.VOICE_CLONE_2_0,
                "en-US",
                -5,
                "must be ignored",
                true,
                "en",
                -5,
                null,
                "custom-model",
                true,
            ),
            Case(
                "unsupported language and neutral pitch",
                VolcengineTtsResource.PRESET_VOICE_2_0,
                "hi",
                0,
                "",
                false,
                null,
                null,
                null,
                null,
                null,
            ),
        ).forEach { case ->
            val settings = Settings(
                ttsProvider = TtsProvider.VOLCENGINE,
                targetLang = case.language,
                ttsVolcengineResource = case.resource,
                ttsVolcengineSpeaker = " speaker-id ",
                ttsVolcengineModel = " custom-model ",
                ttsVolcengineContext = case.context,
                ttsVolcenginePitch = case.pitch,
                ttsVolcengineToneFidelity = case.toneFidelity,
            )
            val request = Json.parseToJsonElement(
                buildVolcengineTtsPayload(" hello ", settings)
            ).jsonObject["req_params"]!!.jsonObject
            val audio = request["audio_params"]!!.jsonObject
            val additions = request["additions"]?.jsonPrimitive?.contentOrNull?.let {
                Json.parseToJsonElement(it).jsonObject
            }

            assertEquals(case.name, "hello", request["text"]?.jsonPrimitive?.content)
            assertEquals(case.name, "speaker-id", request["speaker"]?.jsonPrimitive?.content)
            assertEquals(case.name, "mp3", audio["format"]?.jsonPrimitive?.content)
            assertEquals(case.name, 24_000, audio["sample_rate"]?.jsonPrimitive?.intOrNull)
            assertEquals(
                case.name,
                case.expectedLanguage,
                additions?.get("explicit_language")?.jsonPrimitive?.contentOrNull,
            )
            assertEquals(
                case.name,
                case.expectedPitch,
                additions?.get("post_process")?.jsonObject?.get("pitch")?.jsonPrimitive?.intOrNull,
            )
            assertEquals(
                case.name,
                case.expectedContext,
                request["context_texts"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
            )
            assertEquals(case.name, case.expectedModel, request["model"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                case.name,
                case.expectedToneFidelity,
                request["tone_fidelity"]?.jsonPrimitive?.booleanOrNull,
            )
        }
    }

    @Test
    fun chunkedResponse_aggregatesAudioAndAcceptsOfficialEndCode() {
        data class Case(
            val name: String,
            val separator: String,
            val first: String,
            val second: String,
            val suffix: String,
            val expectedObjects: Int,
        )

        listOf(
            Case("newlines", "\r\n", "hello ", "world", "", 2),
            Case("adjacent chunks", "", "a", "b", "", 2),
            Case("whitespace", " \n\t ", "left", "right", "", 2),
            Case(
                "official terminal code",
                "\n",
                "audio ",
                "complete",
                "\n{\"code\":20000000,\"message\":\"OK\"}",
                3,
            ),
            Case(
                "terminal code ignores following service object",
                "\n",
                "kept ",
                "audio",
                "\n{\"code\":20000000}\n{\"code\":5501,\"message\":\"must be ignored\"}",
                4,
            ),
        ).forEach { case ->
            val first = Base64.getEncoder().encodeToString(case.first.toByteArray())
            val second = Base64.getEncoder().encodeToString(case.second.toByteArray())
            val raw = """{"code":0,"data":"$first","sentence":{"text":"}"}}${case.separator}{"code":0,"message":"OK","data":"$second"}${case.suffix}"""
            val payload = decodeVolcengineTtsPayload(raw)

            assertArrayEquals(case.name, "${case.first}${case.second}".toByteArray(), payload.bytes)
            assertEquals(case.name, "audio/mpeg", payload.mimeType)
            assertEquals(case.name, case.expectedObjects, splitTopLevelJsonObjects(raw).size)
        }
    }

    @Test
    fun chunkedResponse_reportsServiceAndFramingErrors() {
        data class Case(val name: String, val raw: String, val expected: String)

        listOf(
            Case("service error", """{"code":5501,"message":"invalid speaker"}""", "invalid speaker"),
            Case("terminal without audio", """{"code":20000000,"message":"OK"}""", "missing audio"),
            Case("missing audio", """{"code":0,"message":"OK"}""", "missing audio"),
            Case("invalid base64", """{"code":0,"data":"%%%"}""", "invalid base64"),
            Case("unexpected prefix", "data: {}", "invalid JSON chunks"),
            Case("incomplete object", """{"code":0""", "incomplete JSON chunks"),
        ).forEach { case ->
            val error = runCatching { decodeVolcengineTtsPayload(case.raw) }.exceptionOrNull()
            assertTrue(case.name, error?.message.orEmpty().contains(case.expected))
        }
    }
}
