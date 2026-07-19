package com.gameocr.app.tts

import com.gameocr.app.R
import com.gameocr.app.data.MimoTtsModel
import com.gameocr.app.data.MiniMaxTtsModel
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TtsProvider
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsEndpointPolicyTest {

    @Test
    fun ttsPresetSummaryLabelRes_coversDisabledAndEveryProvider() {
        data class Case(
            val name: String,
            val enabled: Boolean,
            val provider: TtsProvider,
            val expected: Int,
        )

        val cases = listOf(
            Case("disabled system", false, TtsProvider.SYSTEM, R.string.main_status_disabled),
            Case("disabled cloud", false, TtsProvider.MINIMAX, R.string.main_status_disabled),
            Case("system", true, TtsProvider.SYSTEM, R.string.settings_tts_provider_system),
            Case("generic HTTP", true, TtsProvider.GENERIC_HTTP, R.string.settings_tts_provider_generic_http),
            Case("Volcengine", true, TtsProvider.VOLCENGINE, R.string.settings_tts_provider_volc),
            Case("MiniMax", true, TtsProvider.MINIMAX, R.string.settings_tts_provider_minimax),
            Case("MiMo", true, TtsProvider.MIMO, R.string.settings_tts_provider_mimo),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                ttsPresetSummaryLabelRes(case.enabled, case.provider),
            )
        }
    }

    @Test
    fun ttsEndpointUrlOrNull_normalizesBaseUrls() {
        data class Case(
            val name: String,
            val raw: String,
            val expected: String?,
        )

        val cases = listOf(
            Case("blank", "", null),
            Case("invalid", "not a url", null),
            Case("base url appends api path", " http://192.168.0.2:2333 ", "http://192.168.0.2:2333/api/tts"),
            Case("full api path is kept", "http://pc.local:2333/api/tts", "http://pc.local:2333/api/tts"),
            Case("custom path appends api path", "https://tts.example.com/gateway", "https://tts.example.com/gateway/api/tts"),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, ttsEndpointUrlOrNull(case.raw))
        }
    }

    @Test
    fun ttsHttpHostOrNull_onlyReturnsHttpHosts() {
        data class Case(
            val name: String,
            val raw: String,
            val expected: String?,
        )

        val cases = listOf(
            Case("http host", "http://pc-name:2333/api/tts", "pc-name"),
            Case("http ip", "http://192.168.0.2:2333", "192.168.0.2"),
            Case("https does not need cleartext", "https://tts.example.com/api/tts", null),
            Case("invalid", "pc-name:2333", null),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, ttsHttpHostOrNull(case.raw))
        }
    }

    @Test
    fun providerEndpointPolicies_acceptBasesVersionsAndCompleteUrls() {
        data class Case(
            val name: String,
            val raw: String,
            val resolver: (String) -> String?,
            val expected: String?,
        )

        val cases = listOf(
            Case(
                "MiniMax domestic base",
                "https://api.minimaxi.com",
                ::miniMaxTtsEndpointUrlOrNull,
                "https://api.minimaxi.com/v1/t2a_v2",
            ),
            Case(
                "MiniMax version base",
                "https://proxy.example.com/v1/",
                ::miniMaxTtsEndpointUrlOrNull,
                "https://proxy.example.com/v1/t2a_v2",
            ),
            Case(
                "MiniMax complete endpoint",
                "https://api-uw.minimax.io/v1/t2a_v2",
                ::miniMaxTtsEndpointUrlOrNull,
                "https://api-uw.minimax.io/v1/t2a_v2",
            ),
            Case(
                "MiMo root base",
                "https://api.xiaomimimo.com",
                ::mimoTtsEndpointUrlOrNull,
                "https://api.xiaomimimo.com/v1/chat/completions",
            ),
            Case(
                "MiMo version base",
                "https://proxy.example.com/v1",
                ::mimoTtsEndpointUrlOrNull,
                "https://proxy.example.com/v1/chat/completions",
            ),
            Case(
                "MiMo complete endpoint",
                "https://proxy.example.com/custom/v1/chat/completions",
                ::mimoTtsEndpointUrlOrNull,
                "https://proxy.example.com/custom/v1/chat/completions",
            ),
            Case(
                "Volcengine root base",
                "https://openspeech.bytedance.com",
                ::volcengineTtsEndpointUrlOrNull,
                "https://openspeech.bytedance.com/api/v3/tts/unidirectional",
            ),
            Case(
                "Volcengine complete endpoint",
                "https://proxy.example.com/api/v3/tts/unidirectional",
                ::volcengineTtsEndpointUrlOrNull,
                "https://proxy.example.com/api/v3/tts/unidirectional",
            ),
            Case("blank URL", "  ", ::mimoTtsEndpointUrlOrNull, null),
            Case("invalid URL", "not a URL", ::miniMaxTtsEndpointUrlOrNull, null),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, case.resolver(case.raw))
        }
    }

    @Test
    fun isMimoTokenPlanBaseUrl_recognizesEveryOfficialCluster() {
        data class Case(val name: String, val raw: String, val expected: Boolean)

        listOf(
            Case("China", MIMO_TOKEN_PLAN_CN_BASE_URL, true),
            Case("Singapore", MIMO_TOKEN_PLAN_SGP_BASE_URL, true),
            Case("Europe", MIMO_TOKEN_PLAN_EU_BASE_URL, true),
            Case("trailing slash", "$MIMO_TOKEN_PLAN_CN_BASE_URL/", true),
            Case("case insensitive host", "HTTPS://TOKEN-PLAN-SGP.XIAOMIMIMO.COM/V1", true),
            Case("pay as you go", "https://api.xiaomimimo.com/v1", false),
            Case("custom proxy", "https://mimo.example.com/v1", false),
            Case("blank", "  ", false),
        ).forEach { case ->
            assertEquals(case.name, case.expected, isMimoTokenPlanBaseUrl(case.raw))
        }
    }

    @Test
    fun buildTtsHttpPayload_mapsProviderProfilesAndControls() {
        data class Case(
            val provider: TtsProvider,
            val expectedProfile: String,
        )

        val cases = listOf(Case(TtsProvider.GENERIC_HTTP, "generic"))

        cases.forEach { case ->
            val settings = Settings(
                sourceLang = "ja",
                targetLang = "zh-CN",
                ttsProvider = case.provider,
                ttsVoice = " longwan ",
                ttsEmotion = " happy ",
                ttsSpeed = 8.0f,
                ttsPitch = 0.01f,
            )

            val root = Json.parseToJsonElement(buildTtsHttpPayload("  hello  ", settings)).jsonObject

            assertEquals(case.expectedProfile, root["profile"]?.jsonPrimitive?.content)
            assertEquals("hello", root["text"]?.jsonPrimitive?.content)
            assertEquals("ja", root["sourceLang"]?.jsonPrimitive?.content)
            assertEquals("zh-CN", root["targetLang"]?.jsonPrimitive?.content)
            assertEquals("zh-CN", root["language"]?.jsonPrimitive?.content)
            assertEquals("longwan", root["voice"]?.jsonPrimitive?.content)
            assertEquals("happy", root["emotion"]?.jsonPrimitive?.content)
            assertEquals(4.0, root["speed"]?.jsonPrimitive?.doubleOrNull ?: -1.0, 0.0001)
            assertEquals(0.25, root["pitch"]?.jsonPrimitive?.doubleOrNull ?: -1.0, 0.0001)
        }
    }

    @Test
    fun buildMiniMaxTtsPayload_mapsEveryOfficialModelAndClampsControls() {
        MiniMaxTtsModel.entries.forEach { model ->
            val settings = Settings(
                ttsProvider = TtsProvider.MINIMAX,
                ttsMiniMaxModel = model,
                ttsMiniMaxVoice = " cloned-voice ",
                ttsMiniMaxEmotion = " happy ",
                ttsMiniMaxSpeed = 9.0f,
                ttsMiniMaxPitch = -99,
                targetLang = "ja-JP",
            )

            val root = Json.parseToJsonElement(buildMiniMaxTtsPayload(" hello ", settings)).jsonObject
            val voice = root["voice_setting"]!!.jsonObject
            val audio = root["audio_setting"]!!.jsonObject

            assertEquals(model.apiId, root["model"]?.jsonPrimitive?.content)
            assertEquals("hello", root["text"]?.jsonPrimitive?.content)
            assertEquals(false, root["stream"]?.jsonPrimitive?.booleanOrNull)
            assertEquals("cloned-voice", voice["voice_id"]?.jsonPrimitive?.content)
            assertEquals("happy", voice["emotion"]?.jsonPrimitive?.content)
            assertEquals(2.0, voice["speed"]?.jsonPrimitive?.doubleOrNull ?: -1.0, 0.0001)
            assertEquals(-12, voice["pitch"]?.jsonPrimitive?.intOrNull)
            assertEquals("mp3", audio["format"]?.jsonPrimitive?.content)
            assertEquals("Japanese", root["language_boost"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun ttsLanguagePolicies_mapBcp47TagsForDirectAndGatewayProviders() {
        data class Case(
            val name: String,
            val input: String,
            val expectedTag: String,
            val expectedMiniMax: String,
        )

        val cases = listOf(
            Case("Japanese", "ja", "ja", "Japanese"),
            Case("Japanese region", "ja-JP", "ja-JP", "Japanese"),
            Case("Simplified Chinese", "zh-CN", "zh-CN", "Chinese"),
            Case("Traditional Chinese underscore", "zh_TW", "zh-TW", "Chinese"),
            Case("Cantonese", "yue-Hant-HK", "yue-Hant-HK", "Chinese,Yue"),
            Case("English region", "en-US", "en-US", "English"),
            Case("Korean", "ko", "ko", "Korean"),
            Case("Portuguese region", "pt-BR", "pt-BR", "Portuguese"),
            Case("Norwegian Bokmal", "nb", "nb", "Norwegian"),
            Case("Norwegian Nynorsk", "nn", "nn", "Nynorsk"),
            Case("automatic", "auto", "auto", "auto"),
            Case("blank", "  ", "auto", "auto"),
            Case("unsupported", "zu", "zu", "auto"),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expectedTag, spokenTtsLanguageTag(case.input))
            assertEquals(case.name, case.expectedMiniMax, miniMaxLanguageBoost(case.input))
        }
    }

    @Test
    fun miniMaxLanguagePolicy_respectsLegacyModelLimitations() {
        data class Case(
            val name: String,
            val language: String,
            val model: MiniMaxTtsModel,
            val expected: String,
        )

        listOf(
            Case("01 Persian unsupported", "fa", MiniMaxTtsModel.SPEECH_01_HD, "auto"),
            Case("02 Filipino unsupported", "tl", MiniMaxTtsModel.SPEECH_02_TURBO, "auto"),
            Case("02 Tamil unsupported", "ta", MiniMaxTtsModel.SPEECH_02_HD, "auto"),
            Case("2.6 Persian supported", "fa", MiniMaxTtsModel.SPEECH_2_6_HD, "Persian"),
            Case("2.8 Filipino supported", "tl", MiniMaxTtsModel.SPEECH_2_8_TURBO, "Filipino"),
            Case("all models Japanese", "ja", MiniMaxTtsModel.SPEECH_01_TURBO, "Japanese"),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                miniMaxLanguageBoost(case.language, case.model),
            )
        }
    }

    @Test
    fun automaticTtsLanguagePolicy_detectsCommonScriptsAndHonorsExplicitLanguage() {
        data class Case(
            val name: String,
            val text: String,
            val configured: String,
            val expected: String,
        )

        listOf(
            Case("Japanese kana wins over Han", "今日はゲームです", "auto", "ja"),
            Case("Korean", "오늘은 맑습니다", "auto", "ko"),
            Case("Chinese Han", "今天是晴天", "auto", "zh-CN"),
            Case("English Latin", "Hello world", "auto", "en"),
            Case("Arabic", "مرحبا بالعالم", "auto", "ar"),
            Case("Cyrillic", "Привет мир", "auto", "ru"),
            Case("punctuation only", "123!?", "auto", "auto"),
            Case("explicit overrides script", "Hello world", "ja-JP", "ja-JP"),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                resolvedSpokenTtsLanguageTag(case.text, case.configured),
            )
        }
    }

    @Test
    fun buildMimoTtsPayload_mapsAllV25Modes() {
        data class Case(
            val name: String,
            val model: MimoTtsModel,
            val instruction: String,
            val sample: String?,
            val expectedVoice: String?,
            val expectedMessageCount: Int,
            val expectedInstruction: String?,
        )

        val cases = listOf(
            Case("preset", MimoTtsModel.PRESET, " cheerful ", null, "冰糖", 2, "cheerful"),
            Case(
                "voice design",
                MimoTtsModel.VOICE_DESIGN,
                " deep narrator ",
                null,
                null,
                2,
                "deep narrator",
            ),
            Case(
                "voice clone",
                MimoTtsModel.VOICE_CLONE,
                " softly ",
                "data:audio/mpeg;base64,aGk=",
                "data:audio/mpeg;base64,aGk=",
                2,
                "softly",
            ),
        )

        cases.forEach { case ->
            val settings = Settings(
                ttsProvider = TtsProvider.MIMO,
                ttsMimoModel = case.model,
                ttsMimoVoice = "冰糖",
                ttsMimoInstruction = case.instruction.takeIf {
                    case.model == MimoTtsModel.PRESET
                }.orEmpty(),
                ttsMimoVoiceDesignPrompt = case.instruction.takeIf {
                    case.model == MimoTtsModel.VOICE_DESIGN
                }.orEmpty(),
                ttsMimoVoiceCloneInstruction = case.instruction.takeIf {
                    case.model == MimoTtsModel.VOICE_CLONE
                }.orEmpty(),
            )
            val root = Json.parseToJsonElement(
                buildMimoTtsPayload(" 你好 ", settings, case.sample)
            ).jsonObject
            val messages = root["messages"]!!.jsonArray
            val audio = root["audio"]!!.jsonObject

            assertEquals(case.name, case.model.apiId, root["model"]?.jsonPrimitive?.content)
            assertEquals(case.name, case.expectedMessageCount, messages.size)
            assertEquals(
                case.name,
                case.expectedInstruction,
                messages.firstOrNull()
                    ?.jsonObject
                    ?.takeIf { messages.size > 1 }
                    ?.get("content")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            )
            assertEquals(case.name, "assistant", messages.last().jsonObject["role"]?.jsonPrimitive?.content)
            assertEquals(case.name, "你好", messages.last().jsonObject["content"]?.jsonPrimitive?.content)
            assertEquals(case.name, case.expectedVoice, audio["voice"]?.jsonPrimitive?.contentOrNull)
            assertEquals(case.name, "wav", audio["format"]?.jsonPrimitive?.content)
            assertEquals(case.name, false, root["stream"]?.jsonPrimitive?.booleanOrNull)
        }
    }

    @Test
    fun buildMimoTtsPayload_rejectsMissingModeInputs() {
        data class Case(
            val name: String,
            val settings: Settings,
            val sample: String?,
            val expected: String,
        )

        val cases = listOf(
            Case(
                "design needs description",
                Settings(ttsMimoModel = MimoTtsModel.VOICE_DESIGN),
                null,
                "description",
            ),
            Case(
                "clone needs sample",
                Settings(ttsMimoModel = MimoTtsModel.VOICE_CLONE),
                null,
                "sample",
            ),
        )

        cases.forEach { case ->
            val error = runCatching {
                buildMimoTtsPayload("hello", case.settings, case.sample)
            }.exceptionOrNull()
            assertTrue(case.name, error?.message.orEmpty().contains(case.expected))
        }
    }

    @Test
    fun decodeTtsJsonAudioPayload_acceptsCommonBase64Shapes() {
        data class Case(
            val name: String,
            val raw: String,
            val expectedBytes: ByteArray,
            val expectedExtension: String,
        )

        val hi = Base64.getEncoder().encodeToString("hi".toByteArray())
        val ok = Base64.getEncoder().encodeToString("ok".toByteArray())
        val cases = listOf(
            Case(
                name = "audioBase64 with mime type",
                raw = """{"audioBase64":"$hi","mimeType":"audio/wav"}""",
                expectedBytes = "hi".toByteArray(),
                expectedExtension = "wav",
            ),
            Case(
                name = "nested data object",
                raw = """{"data":{"audio":"$ok"},"format":"mp3"}""",
                expectedBytes = "ok".toByteArray(),
                expectedExtension = "mp3",
            ),
            Case(
                name = "data url is stripped",
                raw = """{"audio":"data:audio/mpeg;base64,$hi","contentType":"audio/mpeg"}""",
                expectedBytes = "hi".toByteArray(),
                expectedExtension = "mp3",
            ),
        )

        cases.forEach { case ->
            val parsed = decodeTtsJsonAudioPayload(case.raw)
            assertArrayEquals(case.name, case.expectedBytes, parsed.bytes)
            assertEquals(case.name, case.expectedExtension, ttsAudioExtension(parsed.mimeType))
        }
    }

    @Test
    fun decodeTtsJsonAudioPayload_reportsErrors() {
        data class Case(
            val name: String,
            val raw: String,
            val expectedMessage: String,
        )

        val cases = listOf(
            Case("service error", """{"error":"quota exceeded"}""", "quota exceeded"),
            Case("missing audio", """{"ok":true}""", "missing"),
            Case("invalid base64", """{"audioBase64":"not-base64"}""", "invalid base64"),
        )

        cases.forEach { case ->
            try {
                decodeTtsJsonAudioPayload(case.raw)
                throw AssertionError("Expected failure for ${case.name}")
            } catch (t: RuntimeException) {
                assertTrue(case.name, t.message.orEmpty().contains(case.expectedMessage))
            }
        }
    }

    @Test
    fun decodeMiniMaxTtsPayload_handlesHexAndServiceErrors() {
        data class Case(
            val name: String,
            val raw: String,
            val expected: ByteArray?,
            val errorContains: String?,
        )

        val cases = listOf(
            Case(
                "success",
                """{"data":{"audio":"6869","status":2},"extra_info":{"audio_format":"mp3"},"base_resp":{"status_code":0,"status_msg":"success"}}""",
                "hi".toByteArray(),
                null,
            ),
            Case(
                "service error",
                """{"base_resp":{"status_code":1004,"status_msg":"invalid api key"}}""",
                null,
                "invalid api key",
            ),
            Case(
                "invalid hex",
                """{"data":{"audio":"xyz"},"base_resp":{"status_code":0}}""",
                null,
                "invalid hex",
            ),
        )

        cases.forEach { case ->
            val result = runCatching { decodeMiniMaxTtsPayload(case.raw) }
            if (case.expected != null) {
                assertArrayEquals(case.name, case.expected, result.getOrThrow().bytes)
            } else {
                assertTrue(
                    case.name,
                    result.exceptionOrNull()?.message.orEmpty().contains(case.errorContains.orEmpty()),
                )
            }
        }
    }

    @Test
    fun decodeMimoTtsPayload_handlesNestedAudioAndErrors() {
        val audio = Base64.getEncoder().encodeToString("wav".toByteArray())
        data class Case(val name: String, val raw: String, val errorContains: String?)
        val cases = listOf(
            Case(
                "success",
                """{"choices":[{"message":{"audio":{"data":"$audio"}}}]}""",
                null,
            ),
            Case("api error", """{"error":{"message":"quota exceeded"}}""", "quota exceeded"),
            Case("missing audio", """{"choices":[]}""", "missing"),
        )

        cases.forEach { case ->
            val result = runCatching { decodeMimoTtsPayload(case.raw) }
            if (case.errorContains == null) {
                assertArrayEquals(case.name, "wav".toByteArray(), result.getOrThrow().bytes)
                assertEquals(case.name, "wav", ttsAudioExtension(result.getOrThrow().mimeType))
            } else {
                assertTrue(
                    case.name,
                    result.exceptionOrNull()?.message.orEmpty().contains(case.errorContains),
                )
            }
        }
    }

    @Test
    fun mimoVoiceSamplePolicy_acceptsOnlyDocumentedFormats() {
        data class Case(
            val name: String,
            val contentType: String?,
            val displayName: String?,
            val expected: String?,
        )

        val cases = listOf(
            Case("mpeg", "audio/mpeg", "voice.bin", "audio/mpeg"),
            Case("x wav", "audio/x-wav", "voice.bin", "audio/wav"),
            Case("mp3 extension", null, "voice.MP3", "audio/mpeg"),
            Case("wav extension", "application/octet-stream", "voice.wav", "audio/wav"),
            Case("m4a rejected", "audio/mp4", "voice.m4a", null),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                mimoVoiceSampleMimeType(case.contentType, case.displayName),
            )
        }
    }

    @Test
    fun mimoBuiltInVoiceReferencePolicy_recognizesOnlyBundledChoices() {
        data class Case(val value: String, val expected: Boolean)

        listOf(
            Case(MIMO_BUILTIN_VOICE_REFERENCE_1, true),
            Case(MIMO_BUILTIN_VOICE_REFERENCE_2, true),
            Case("content://documents/voice.wav", false),
            Case("", false),
        ).forEach { case ->
            assertEquals(case.value, case.expected, isMimoBuiltinVoiceReference(case.value))
        }
        assertEquals(MIMO_BUILTIN_VOICE_REFERENCE_1, Settings().ttsMimoVoiceSampleUri)
    }

    @Test
    fun systemVoiceAndBinaryContentTypePolicies_areDeterministic() {
        data class VoiceCase(val requested: String, val expected: String?)
        listOf(
            VoiceCase("", null),
            VoiceCase("missing", null),
            VoiceCase("zh-cn-voice", "ZH-CN-Voice"),
        ).forEach { case ->
            assertEquals(
                case.requested,
                case.expected,
                selectSystemTtsVoiceName(listOf("ZH-CN-Voice", "en-US-voice"), case.requested),
            )
        }

        val voices = listOf(
            SystemTtsVoiceOption("voice-ja", "ja-JP", false),
            SystemTtsVoiceOption("voice-zh", "zh-CN", false),
            SystemTtsVoiceOption("voice-en", "en-US", true),
        )
        data class RoutedVoiceCase(val requested: String, val language: String, val expected: String?)
        listOf(
            RoutedVoiceCase("voice-ja", "ja", "voice-ja"),
            RoutedVoiceCase("VOICE-JA", "ja-JP", "voice-ja"),
            RoutedVoiceCase("voice-ja", "zh-CN", null),
            RoutedVoiceCase("voice-en", "en-GB", "voice-en"),
            RoutedVoiceCase("", "ja", null),
        ).forEach { case ->
            assertEquals(
                "${case.requested}/${case.language}",
                case.expected,
                selectSystemTtsVoiceForLanguage(voices, case.requested, case.language),
            )
        }

        data class ContentCase(val value: String?, val expected: Boolean)
        listOf(
            ContentCase(null, true),
            ContentCase("audio/mpeg", true),
            ContentCase("application/octet-stream", true),
            ContentCase("text/html; charset=utf-8", false),
            ContentCase("application/json", false),
        ).forEach { case ->
            assertEquals(case.value, case.expected, isSupportedBinaryTtsContentType(case.value))
        }
    }

    @Test
    fun normalizedTtsTextOrNull_filtersPlaceholdersAndLimitsLength() {
        data class Case(
            val name: String,
            val raw: String,
            val maxChars: Int,
            val expected: String?,
        )

        val cases = listOf(
            Case("blank", "   \n  ", 800, null),
            Case("error placeholder", "[!] failed", 800, null),
            Case("trims lines", "  one \n\n two  ", 800, "one\ntwo"),
            Case("limits length", "abcdef", 3, "abc"),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, normalizedTtsTextOrNull(case.raw, case.maxChars))
        }
    }

}
