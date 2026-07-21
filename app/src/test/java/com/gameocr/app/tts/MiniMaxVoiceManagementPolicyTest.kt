package com.gameocr.app.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMaxVoiceManagementPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun endpointResolver_preservesSiteAndOptionalProxyPrefix() {
        data class Case(val name: String, val baseUrl: String, val endpoint: String, val expected: String?)

        listOf(
            Case(
                "China root",
                "https://api.minimaxi.com",
                "get_voice",
                "https://api.minimaxi.com/v1/get_voice",
            ),
            Case(
                "global version root",
                "https://api.minimax.io/v1",
                "voice_design",
                "https://api.minimax.io/v1/voice_design",
            ),
            Case(
                "China low-latency TTS endpoint uses documented management host",
                "https://api-bj.minimaxi.com/v1/t2a_v2",
                "files/upload",
                "https://api.minimaxi.com/v1/files/upload",
            ),
            Case(
                "global low-latency host uses documented management host",
                "https://api-uw.minimax.io",
                "get_voice",
                "https://api.minimax.io/v1/get_voice",
            ),
            Case(
                "custom proxy prefix",
                "https://example.com/minimax/v1/t2a_v2",
                "/voice_clone/",
                "https://example.com/minimax/v1/voice_clone",
            ),
            Case("invalid URL", "not a url", "get_voice", null),
            Case("blank endpoint", "https://api.minimaxi.com", " ", null),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                miniMaxVoiceManagementEndpointOrNull(case.baseUrl, case.endpoint),
            )
        }
    }

    @Test
    fun voiceIdValidation_coversDocumentedBoundaries() {
        data class Case(
            val value: String,
            val expected: MiniMaxVoiceIdValidationError?,
        )

        listOf(
            Case("Abc12345", null),
            Case("A1234567", null),
            Case("Voice-ID_2026", null),
            Case("A123456", MiniMaxVoiceIdValidationError.TOO_SHORT),
            Case("A".repeat(257), MiniMaxVoiceIdValidationError.TOO_LONG),
            Case("1VoiceId", MiniMaxVoiceIdValidationError.INVALID_FIRST_CHARACTER),
            Case("Voice.ID", MiniMaxVoiceIdValidationError.INVALID_CHARACTER),
            Case("VoiceId_", MiniMaxVoiceIdValidationError.INVALID_LAST_CHARACTER),
            Case("中文VoiceId", MiniMaxVoiceIdValidationError.INVALID_FIRST_CHARACTER),
        ).forEach { case ->
            assertEquals(case.value.take(24), case.expected, miniMaxVoiceIdValidationError(case.value))
        }
    }

    @Test
    fun clonePromptTranscript_requiresDocumentedEndingPunctuation() {
        data class Case(val text: String, val expected: Boolean)

        listOf(
            Case("", true),
            Case("This is the exact transcript.", true),
            Case("这是准确台词。", true),
            Case("真的吗？", true),
            Case("没有句末标点", false),
        ).forEach { case ->
            assertEquals(case.text, case.expected, isValidMiniMaxClonePromptText(case.text))
        }
    }

    @Test
    fun audioValidation_coversCloneAndPromptLimits() {
        data class Case(
            val name: String,
            val metadata: MiniMaxAudioMetadata,
            val purpose: MiniMaxAudioPurpose,
            val expected: MiniMaxAudioValidationError?,
        )

        val valid = MiniMaxAudioMetadata("sample.wav", "audio/wav", 1024, 10_000)
        listOf(
            Case("clone minimum inclusive", valid, MiniMaxAudioPurpose.VOICE_CLONE, null),
            Case(
                "clone below minimum",
                valid.copy(durationMs = 9_999),
                MiniMaxAudioPurpose.VOICE_CLONE,
                MiniMaxAudioValidationError.CLONE_TOO_SHORT,
            ),
            Case(
                "clone maximum inclusive",
                valid.copy(durationMs = 300_000),
                MiniMaxAudioPurpose.VOICE_CLONE,
                null,
            ),
            Case(
                "clone above maximum",
                valid.copy(durationMs = 300_001),
                MiniMaxAudioPurpose.VOICE_CLONE,
                MiniMaxAudioValidationError.CLONE_TOO_LONG,
            ),
            Case(
                "prompt below eight seconds",
                valid.copy(displayName = "prompt.m4a", durationMs = 7_999),
                MiniMaxAudioPurpose.PROMPT_AUDIO,
                null,
            ),
            Case(
                "prompt eight seconds rejected",
                valid.copy(durationMs = 8_000),
                MiniMaxAudioPurpose.PROMPT_AUDIO,
                MiniMaxAudioValidationError.PROMPT_TOO_LONG,
            ),
            Case(
                "exact size limit",
                valid.copy(sizeBytes = MINIMAX_AUDIO_MAX_BYTES),
                MiniMaxAudioPurpose.VOICE_CLONE,
                null,
            ),
            Case(
                "over size limit",
                valid.copy(sizeBytes = MINIMAX_AUDIO_MAX_BYTES + 1),
                MiniMaxAudioPurpose.VOICE_CLONE,
                MiniMaxAudioValidationError.FILE_TOO_LARGE,
            ),
            Case(
                "unsupported format",
                valid.copy(displayName = "sample.ogg", mimeType = "audio/ogg"),
                MiniMaxAudioPurpose.VOICE_CLONE,
                MiniMaxAudioValidationError.UNSUPPORTED_FORMAT,
            ),
            Case(
                "MIME fallback when name has no extension",
                valid.copy(displayName = "sample", mimeType = "audio/mpeg"),
                MiniMaxAudioPurpose.VOICE_CLONE,
                null,
            ),
            Case(
                "unknown duration lets server validate",
                valid.copy(durationMs = null),
                MiniMaxAudioPurpose.VOICE_CLONE,
                null,
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expected, miniMaxAudioValidationError(case.metadata, case.purpose))
        }
    }

    @Test
    fun managedVoiceSearch_matchesIdTypeAndDescription() {
        val voices = listOf(
            MiniMaxManagedVoice(
                voiceId = "CloneVoice01",
                type = MiniMaxManagedVoiceType.CLONING,
                description = listOf("calm narrator"),
            ),
            MiniMaxManagedVoice(
                voiceId = "DesignVoice02",
                type = MiniMaxManagedVoiceType.GENERATION,
                description = listOf("bright heroine"),
            ),
        )
        data class Case(val query: String, val expectedIds: List<String>)

        listOf(
            Case("", listOf("CloneVoice01", "DesignVoice02")),
            Case("clonevoice", listOf("CloneVoice01")),
            Case("voice_cloning calm", listOf("CloneVoice01")),
            Case("BRIGHT HEROINE", listOf("DesignVoice02")),
            Case("missing", emptyList()),
        ).forEach { case ->
            assertEquals(
                case.query,
                case.expectedIds,
                searchMiniMaxManagedVoices(case.query, voices).map { it.voiceId },
            )
        }
    }

    @Test
    fun managedVoiceAutoLoad_requiresNonBlankApiKey() {
        data class Case(val name: String, val apiKey: String, val expected: Boolean)

        listOf(
            Case("empty key", "", false),
            Case("whitespace key", "   ", false),
            Case("configured key", "secret", true),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldLoadMiniMaxManagedVoices(case.apiKey),
            )
        }
    }

    @Test
    fun managedVoiceMerge_preservesOnlyLocallyPendingCreationsMissingFromServer() {
        fun voice(
            id: String,
            type: MiniMaxManagedVoiceType = MiniMaxManagedVoiceType.GENERATION,
            description: String = "",
        ) = MiniMaxManagedVoice(
            voiceId = id,
            type = type,
            description = if (description.isBlank()) emptyList() else listOf(description),
        )

        data class Case(
            val name: String,
            val remote: List<MiniMaxManagedVoice>,
            val pending: List<MiniMaxManagedVoice>,
            val expected: List<MiniMaxManagedVoice>,
        )

        listOf(
            Case(
                "pending voice remains visible before first synthesis",
                remote = emptyList(),
                pending = listOf(voice("Pending01")),
                expected = listOf(voice("Pending01")),
            ),
            Case(
                "server voice replaces the matching pending copy",
                remote = listOf(voice("Ready01", description = "server")),
                pending = listOf(voice("Ready01", description = "local")),
                expected = listOf(voice("Ready01", description = "server")),
            ),
            Case(
                "pending voice is listed before unrelated server voices",
                remote = listOf(voice("Ready02")),
                pending = listOf(voice("Pending02")),
                expected = listOf(voice("Pending02"), voice("Ready02")),
            ),
            Case(
                "same ID with different voice types remains distinct",
                remote = listOf(voice("SharedId", MiniMaxManagedVoiceType.CLONING)),
                pending = listOf(voice("SharedId", MiniMaxManagedVoiceType.GENERATION)),
                expected = listOf(
                    voice("SharedId", MiniMaxManagedVoiceType.GENERATION),
                    voice("SharedId", MiniMaxManagedVoiceType.CLONING),
                ),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                mergeMiniMaxManagedVoices(case.remote, case.pending),
            )
        }
    }

    @Test
    fun designPayloadAndResponses_followOfficialFields() {
        val request = MiniMaxVoiceDesignRequest(
            baseUrl = "https://api.minimaxi.com",
            apiKey = "secret",
            prompt = " warm narrator ",
            previewText = "Hello",
            customVoiceId = "VoiceId01",
        )
        val payload = json.parseToJsonElement(buildMiniMaxVoiceDesignPayload(request)).jsonObject
        assertEquals("warm narrator", payload.getValue("prompt").jsonPrimitive.content)
        assertEquals("Hello", payload.getValue("preview_text").jsonPrimitive.content)
        assertEquals("VoiceId01", payload.getValue("voice_id").jsonPrimitive.content)

        val voices = decodeMiniMaxManagedVoices(
            """{
                "voice_cloning":[{"voice_id":"CloneVoice01","description":[],"created_time":"2026-07-01"}],
                "voice_generation":[{"voice_id":"DesignVoice02","description":["warm"],"created_time":"2026-07-02"}],
                "base_resp":{"status_code":0,"status_msg":"success"}
            }""",
            json,
        )
        assertEquals(listOf("CloneVoice01", "DesignVoice02"), voices.map { it.voiceId })
        assertEquals(MiniMaxManagedVoiceType.GENERATION, voices.last().type)
        assertEquals(listOf("warm"), voices.last().description)

        val design = decodeMiniMaxVoiceDesignResult(
            """{"voice_id":"DesignVoice03","trial_audio":"aabb","base_resp":{"status_code":0}}""",
            json,
            "warm narrator",
        )
        assertEquals("DesignVoice03", design.voice.voiceId)
        assertEquals("aabb", design.previewAudio)
    }

    @Test
    fun apiStatusErrors_areNotSilentlyAccepted() {
        val error = assertThrows(MiniMaxApiException::class.java) {
            decodeMiniMaxManagedVoices(
                """{"base_resp":{"status_code":1004,"status_msg":"invalid key"}}""",
                json,
            )
        }
        assertEquals(1004, error.statusCode)
        assertEquals("invalid key", error.statusMessage)
        assertTrue(error.message.orEmpty().contains("1004"))
        assertTrue(error.message.orEmpty().contains("invalid key"))
    }

    @Test
    fun apiErrorReason_coversEveryErrorCodeInOfficialErrorCodeReference() {
        data class Case(
            val code: Int,
            val expected: MiniMaxApiErrorReason,
        )

        listOf(
            Case(1000, MiniMaxApiErrorReason.RETRY_LATER),
            Case(1001, MiniMaxApiErrorReason.REQUEST_TIMEOUT),
            Case(1002, MiniMaxApiErrorReason.RATE_LIMIT),
            Case(1004, MiniMaxApiErrorReason.INVALID_API_KEY),
            Case(1008, MiniMaxApiErrorReason.INSUFFICIENT_BALANCE),
            Case(1024, MiniMaxApiErrorReason.RETRY_LATER),
            Case(1026, MiniMaxApiErrorReason.INPUT_SENSITIVE),
            Case(1027, MiniMaxApiErrorReason.OUTPUT_SENSITIVE),
            Case(1033, MiniMaxApiErrorReason.RETRY_LATER),
            Case(1039, MiniMaxApiErrorReason.TOKEN_LIMIT),
            Case(1041, MiniMaxApiErrorReason.CONNECTION_LIMIT),
            Case(1042, MiniMaxApiErrorReason.INVALID_CHARACTERS),
            Case(1043, MiniMaxApiErrorReason.ASR_SIMILARITY_MISMATCH),
            Case(1044, MiniMaxApiErrorReason.PROMPT_SIMILARITY_MISMATCH),
            Case(2013, MiniMaxApiErrorReason.INVALID_PARAMETERS),
            Case(20132, MiniMaxApiErrorReason.INVALID_CLONE_SAMPLE_OR_VOICE_ID),
            Case(2037, MiniMaxApiErrorReason.INVALID_VOICE_DURATION),
            Case(2038, MiniMaxApiErrorReason.VOICE_CLONING_DISABLED),
            Case(2039, MiniMaxApiErrorReason.DUPLICATE_VOICE_ID),
            Case(2042, MiniMaxApiErrorReason.VOICE_ID_ACCESS_DENIED),
            Case(2045, MiniMaxApiErrorReason.BURST_RATE_LIMIT),
            Case(2048, MiniMaxApiErrorReason.PROMPT_AUDIO_TOO_LONG),
            Case(2049, MiniMaxApiErrorReason.INVALID_API_KEY),
            Case(2056, MiniMaxApiErrorReason.PLAN_RESOURCE_LIMIT),
        ).forEach { case ->
            assertEquals(case.code.toString(), case.expected, miniMaxApiErrorReason(case.code))
        }
        assertNull(miniMaxApiErrorReason(99999))
    }

    @Test
    fun apiExceptionLookup_findsWrappedMiniMaxErrors() {
        val apiError = MiniMaxApiException(2038, "  clone\n disabled  ")
        val wrapped = IllegalStateException("request failed", RuntimeException("wrapper", apiError))

        assertEquals(apiError, wrapped.miniMaxApiExceptionOrNull())
        assertEquals("clone disabled", apiError.statusMessage)
        assertNull(IllegalArgumentException("other").miniMaxApiExceptionOrNull())
    }
}
