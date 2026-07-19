package com.gameocr.app.tts

import com.gameocr.app.data.MimoTtsModel
import com.gameocr.app.data.MiniMaxTtsModel
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TtsProvider
import com.gameocr.app.data.VolcengineTtsResource
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val DEFAULT_TTS_PATH = "/api/tts"
private const val DEFAULT_TTS_MAX_CHARS = 800
private const val VOLCENGINE_TTS_PATH = "/api/v3/tts/unidirectional"
private const val VOLCENGINE_TTS_STREAM_END_CODE = 20_000_000
private const val DEFAULT_MINIMAX_VOICE = "male-qn-qingse"
private const val DEFAULT_MIMO_VOICE = "mimo_default"

internal const val MINIMAX_CN_LOW_LATENCY_BASE_URL = "https://api-bj.minimaxi.com"
internal const val MINIMAX_GLOBAL_BASE_URL = "https://api.minimax.io"
internal const val MINIMAX_GLOBAL_LOW_LATENCY_BASE_URL = "https://api-uw.minimax.io"
internal const val MIMO_TOKEN_PLAN_CN_BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1"
internal const val MIMO_TOKEN_PLAN_SGP_BASE_URL = "https://token-plan-sgp.xiaomimimo.com/v1"
internal const val MIMO_TOKEN_PLAN_EU_BASE_URL = "https://token-plan-ams.xiaomimimo.com/v1"
internal const val MIMO_VOICE_SAMPLE_MAX_BASE64_BYTES = 10 * 1024 * 1024
internal const val MIMO_BUILTIN_VOICE_REFERENCE_1 = "builtin:mimo_voice_reference_1"
internal const val MIMO_BUILTIN_VOICE_REFERENCE_2 = "builtin:mimo_voice_reference_2"

private val MIMO_TOKEN_PLAN_BASE_URLS = setOf(
    MIMO_TOKEN_PLAN_CN_BASE_URL,
    MIMO_TOKEN_PLAN_SGP_BASE_URL,
    MIMO_TOKEN_PLAN_EU_BASE_URL,
)

internal data class TtsAudioPayload(
    val bytes: ByteArray,
    val mimeType: String?,
)

internal fun ttsEndpointUrlOrNull(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isBlank()) return null
    val endpoint = if (trimmed.endsWith(DEFAULT_TTS_PATH)) trimmed else "$trimmed$DEFAULT_TTS_PATH"
    return endpoint.takeIf { it.toHttpUrlOrNull() != null }
}

internal fun ttsHttpHostOrNull(raw: String): String? {
    val url = ttsEndpointUrlOrNull(raw)?.toHttpUrlOrNull() ?: return null
    return url.host.takeIf { url.scheme.equals("http", ignoreCase = true) }
}

internal fun miniMaxTtsEndpointUrlOrNull(raw: String): String? =
    versionedTtsEndpointUrlOrNull(
        raw = raw,
        completePath = "/v1/t2a_v2",
        versionPath = "/v1",
        endpointName = "t2a_v2",
    )

internal fun volcengineTtsEndpointUrlOrNull(raw: String): String? =
    fixedTtsEndpointUrlOrNull(raw, VOLCENGINE_TTS_PATH)

internal fun mimoTtsEndpointUrlOrNull(raw: String): String? =
    versionedTtsEndpointUrlOrNull(
        raw = raw,
        completePath = "/v1/chat/completions",
        versionPath = "/v1",
        endpointName = "chat/completions",
    )

internal fun isMimoTokenPlanBaseUrl(raw: String): Boolean {
    val normalized = raw.trim().trimEnd('/')
    return MIMO_TOKEN_PLAN_BASE_URLS.any { it.equals(normalized, ignoreCase = true) }
}

internal fun ttsApiHttpHostOrNull(raw: String): String? {
    val url = raw.trim().toHttpUrlOrNull() ?: return null
    return url.host.takeIf { url.scheme.equals("http", ignoreCase = true) }
}

private fun versionedTtsEndpointUrlOrNull(
    raw: String,
    completePath: String,
    versionPath: String,
    endpointName: String,
): String? {
    val url = raw.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
    val path = url.encodedPath.trimEnd('/').ifBlank { "/" }
    val endpointPath = when {
        path.endsWith(completePath, ignoreCase = true) -> path
        path.endsWith(versionPath, ignoreCase = true) -> "$path/$endpointName"
        path == "/" -> completePath
        else -> "$path$completePath"
    }
    return url.newBuilder().encodedPath(endpointPath).build().toString()
}

private fun fixedTtsEndpointUrlOrNull(raw: String, completePath: String): String? {
    val url = raw.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
    val path = url.encodedPath.trimEnd('/').ifBlank { "/" }
    val endpointPath = when {
        path.endsWith(completePath, ignoreCase = true) -> path
        path == "/" -> completePath
        else -> "$path$completePath"
    }
    return url.newBuilder().encodedPath(endpointPath).build().toString()
}

internal fun normalizedTtsTextOrNull(raw: String, maxChars: Int = DEFAULT_TTS_MAX_CHARS): String? {
    val normalized = raw
        .replace('\u00A0', ' ')
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .trim()
    if (normalized.isBlank() || normalized.startsWith("[!]")) return null
    return normalized.take(maxChars.coerceAtLeast(1))
}

internal fun ttsSpeechRate(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.25f, 4.0f) else 1.0f

internal fun ttsPitch(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.25f, 4.0f) else 1.0f

internal fun spokenTtsLanguageTag(raw: String): String {
    val normalized = raw.trim().replace('_', '-')
    return normalized.takeUnless { it.isBlank() || it.equals("auto", ignoreCase = true) }
        ?: "auto"
}

internal fun resolvedSpokenTtsLanguageTag(text: String, configured: String): String {
    val explicit = spokenTtsLanguageTag(configured)
    if (explicit != "auto") return explicit
    var hasHan = false
    var hasLatin = false
    text.forEach { char ->
        when (Character.UnicodeBlock.of(char)) {
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS -> return "ja"
            Character.UnicodeBlock.HANGUL_SYLLABLES,
            Character.UnicodeBlock.HANGUL_JAMO,
            Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> return "ko"
            Character.UnicodeBlock.ARABIC,
            Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A,
            Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B -> return "ar"
            Character.UnicodeBlock.CYRILLIC,
            Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY -> return "ru"
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS -> hasHan = true
            Character.UnicodeBlock.BASIC_LATIN,
            Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
            Character.UnicodeBlock.LATIN_EXTENDED_A,
            Character.UnicodeBlock.LATIN_EXTENDED_B -> if (char.isLetter()) hasLatin = true
        }
    }
    return when {
        hasHan -> "zh-CN"
        hasLatin -> "en"
        else -> "auto"
    }
}

internal fun ttsTestLanguageTag(text: String, fallback: String): String {
    val detected = resolvedSpokenTtsLanguageTag(text, "auto")
    return detected.takeUnless { it == "auto" } ?: spokenTtsLanguageTag(fallback)
}

internal fun settingsForTtsTest(text: String, settings: Settings): Settings = settings.copy(
    ttsEnabled = true,
    targetLang = ttsTestLanguageTag(text, settings.targetLang),
)

internal fun shouldResetTtsTestFeedback(current: TtsProvider, next: TtsProvider): Boolean =
    current != next

internal fun miniMaxLanguageBoost(raw: String, model: MiniMaxTtsModel? = null): String {
    val boost = when (
    spokenTtsLanguageTag(raw).substringBefore('-').lowercase()
) {
    "zh" -> "Chinese"
    "yue" -> "Chinese,Yue"
    "en" -> "English"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "ar" -> "Arabic"
    "ru" -> "Russian"
    "es" -> "Spanish"
    "fr" -> "French"
    "pt" -> "Portuguese"
    "de" -> "German"
    "tr" -> "Turkish"
    "nl" -> "Dutch"
    "uk" -> "Ukrainian"
    "vi" -> "Vietnamese"
    "id" -> "Indonesian"
    "it" -> "Italian"
    "th" -> "Thai"
    "pl" -> "Polish"
    "ro" -> "Romanian"
    "el" -> "Greek"
    "cs" -> "Czech"
    "fi" -> "Finnish"
    "hi" -> "Hindi"
    "bg" -> "Bulgarian"
    "da" -> "Danish"
    "he" -> "Hebrew"
    "ms" -> "Malay"
    "fa" -> "Persian"
    "sk" -> "Slovak"
    "sv" -> "Swedish"
    "hr" -> "Croatian"
    "tl" -> "Filipino"
    "hu" -> "Hungarian"
    "nb" -> "Norwegian"
    "nn" -> "Nynorsk"
    "sl" -> "Slovenian"
    "ca" -> "Catalan"
    "ta" -> "Tamil"
    "af" -> "Afrikaans"
    else -> "auto"
}
    val legacyUnsupported = setOf("Persian", "Filipino", "Tamil")
    val legacyModel = model?.apiId?.let { it.startsWith("speech-01") || it.startsWith("speech-02") }
        ?: false
    return if (legacyModel && boost in legacyUnsupported) "auto" else boost
}

internal fun isMimoBuiltinVoiceReference(raw: String): Boolean = raw == MIMO_BUILTIN_VOICE_REFERENCE_1 ||
    raw == MIMO_BUILTIN_VOICE_REFERENCE_2

internal fun miniMaxSpeechRate(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.5f, 2.0f) else 1.0f

internal fun miniMaxPitch(value: Int): Int = value.coerceIn(-12, 12)

internal fun ttsHttpProfile(provider: TtsProvider): String = when (provider) {
    TtsProvider.SYSTEM,
    TtsProvider.GENERIC_HTTP -> "generic"
    TtsProvider.VOLCENGINE -> "volcengine"
    TtsProvider.MINIMAX -> "minimax"
    TtsProvider.MIMO -> "mimo"
}

internal fun buildTtsHttpPayload(text: String, settings: Settings): String {
    val normalizedText = normalizedTtsTextOrNull(text).orEmpty()
    return buildJsonObject {
        put("profile", ttsHttpProfile(settings.ttsProvider))
        put("text", normalizedText)
        put("sourceLang", settings.sourceLang)
        put("targetLang", settings.targetLang)
        put("language", spokenTtsLanguageTag(settings.targetLang))
        put("format", "mp3")
        settings.ttsVoice.trim().takeIf(String::isNotEmpty)?.let { put("voice", it) }
        settings.ttsEmotion.trim().takeIf(String::isNotEmpty)?.let { put("emotion", it) }
        put("speed", ttsSpeechRate(settings.ttsSpeed))
        put("pitch", ttsPitch(settings.ttsPitch))
    }.toString()
}

internal fun volcengineExplicitLanguage(raw: String): String? {
    val normalized = spokenTtsLanguageTag(raw).lowercase()
    return when (normalized.substringBefore('-')) {
        "zh", "yue" -> "zh-cn"
        "en" -> "en"
        "ja" -> "ja"
        "es" -> "es-mx"
        "id" -> "id"
        "pt" -> if (normalized == "pt-br") "pt-br" else "pt"
        "ko" -> "ko"
        "it" -> "it"
        "de" -> "de"
        "fr" -> "fr"
        "th" -> "th"
        "vi" -> "vi"
        "ru" -> "ru"
        "fil", "tl" -> "fil"
        "ms" -> "ms"
        "ar" -> "ar"
        "pl" -> "pl"
        "tr" -> "tr"
        "sv" -> "sv"
        else -> null
    }
}

internal fun buildVolcengineTtsPayload(text: String, settings: Settings): String {
    val normalizedText = normalizedTtsTextOrNull(text).orEmpty()
    val resource = settings.ttsVolcengineResource
    val additions = buildJsonObject {
        volcengineExplicitLanguage(settings.targetLang)?.let { put("explicit_language", it) }
        val pitch = settings.ttsVolcenginePitch.coerceIn(-12, 12)
        if (pitch != 0) {
            put("post_process", buildJsonObject { put("pitch", pitch) })
        }
    }
    return buildJsonObject {
        put("req_params", buildJsonObject {
            put("text", normalizedText)
            put(
                "speaker",
                settings.ttsVolcengineSpeaker.trim().ifBlank { "zh_female_vv_uranus_bigtts" },
            )
            put("audio_params", buildJsonObject {
                put("format", "mp3")
                put("sample_rate", 24_000)
            })
            if (resource == VolcengineTtsResource.VOICE_CLONE_2_0) {
                put(
                    "model",
                    settings.ttsVolcengineModel.trim().ifBlank { "seed-tts-2.0-standard" },
                )
                if (settings.ttsVolcengineToneFidelity) put("tone_fidelity", true)
            } else {
                settings.ttsVolcengineContext.trim().takeIf(String::isNotEmpty)?.let { context ->
                    put(
                        "context_texts",
                        buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(context)) },
                    )
                }
            }
            if (additions.isNotEmpty()) put("additions", additions.toString())
        })
    }.toString()
}

internal fun volcengineTtsHeaders(
    apiKey: String,
    resource: VolcengineTtsResource,
    requestId: String,
): Map<String, String> = mapOf(
    "X-Api-Key" to apiKey.trim().ifBlank {
        throw IllegalStateException("Volcengine API Key is required")
    },
    "X-Api-Resource-Id" to resource.apiId,
    "X-Api-Request-Id" to requestId,
    "X-Control-Require-Usage-Tokens-Return" to "*",
)

internal fun buildMiniMaxTtsPayload(text: String, settings: Settings): String {
    val normalizedText = normalizedTtsTextOrNull(text).orEmpty()
    return buildJsonObject {
        put("model", settings.ttsMiniMaxModel.apiId)
        put("text", normalizedText)
        put("stream", false)
        put("voice_setting", buildJsonObject {
            put("voice_id", settings.ttsMiniMaxVoice.trim().ifBlank { DEFAULT_MINIMAX_VOICE })
            put("speed", miniMaxSpeechRate(settings.ttsMiniMaxSpeed))
            put("vol", 1.0f)
            put("pitch", miniMaxPitch(settings.ttsMiniMaxPitch))
            settings.ttsMiniMaxEmotion.trim().takeIf(String::isNotEmpty)?.let {
                put("emotion", it)
            }
        })
        put("audio_setting", buildJsonObject {
            put("sample_rate", 32_000)
            put("bitrate", 128_000)
            put("format", "mp3")
            put("channel", 1)
        })
        put("language_boost", miniMaxLanguageBoost(settings.targetLang, settings.ttsMiniMaxModel))
        put("subtitle_enable", false)
    }.toString()
}

internal fun buildMimoTtsPayload(
    text: String,
    settings: Settings,
    voiceSampleDataUrl: String? = null,
): String {
    val normalizedText = normalizedTtsTextOrNull(text).orEmpty()
    val instruction = when (settings.ttsMimoModel) {
        MimoTtsModel.PRESET -> settings.ttsMimoInstruction
        MimoTtsModel.VOICE_DESIGN -> settings.ttsMimoVoiceDesignPrompt
        MimoTtsModel.VOICE_CLONE -> settings.ttsMimoVoiceCloneInstruction
    }.trim()
    if (settings.ttsMimoModel == MimoTtsModel.VOICE_DESIGN && instruction.isBlank()) {
        throw IllegalStateException("MiMo voice design requires a voice description")
    }
    if (settings.ttsMimoModel == MimoTtsModel.VOICE_CLONE && voiceSampleDataUrl.isNullOrBlank()) {
        throw IllegalStateException("MiMo voice clone requires an MP3 or WAV sample")
    }
    return buildJsonObject {
        put("model", settings.ttsMimoModel.apiId)
        put("messages", buildJsonArray {
            if (instruction.isNotBlank() || settings.ttsMimoModel == MimoTtsModel.VOICE_DESIGN) {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", instruction)
                })
            }
            add(buildJsonObject {
                put("role", "assistant")
                put("content", normalizedText)
            })
        })
        put("audio", buildJsonObject {
            put("format", "wav")
            when (settings.ttsMimoModel) {
                MimoTtsModel.PRESET -> put(
                    "voice",
                    settings.ttsMimoVoice.trim().ifBlank { DEFAULT_MIMO_VOICE },
                )
                MimoTtsModel.VOICE_DESIGN -> Unit
                MimoTtsModel.VOICE_CLONE -> put("voice", voiceSampleDataUrl.orEmpty())
            }
        })
        put("stream", false)
    }.toString()
}

internal fun decodeTtsJsonAudioPayload(
    raw: String,
    json: Json = Json { ignoreUnknownKeys = true },
): TtsAudioPayload {
    val root = parseTtsJsonObject(raw, json)
    root["error"]?.asStringOrNull()?.takeIf { it.isNotBlank() }?.let { error ->
        throw RuntimeException("TTS error: ${error.take(200)}")
    }
    val audio = firstNonBlankAudioField(root)
        ?: throw RuntimeException("TTS JSON response missing audioBase64/audio/data")
    val bytes = decodeBase64Audio(audio, "TTS JSON response contains invalid base64 audio")
    return TtsAudioPayload(
        bytes = bytes,
        mimeType = root["mimeType"].asStringOrNull()
            ?: root["mime"].asStringOrNull()
            ?: root["contentType"].asStringOrNull()
            ?: root["format"].asStringOrNull()?.let(::mimeTypeForFormat),
    )
}

internal fun decodeVolcengineTtsPayload(
    raw: String,
    json: Json = Json { ignoreUnknownKeys = true },
): TtsAudioPayload {
    val output = ByteArrayOutputStream()
    val chunks = splitTopLevelJsonObjects(raw)
    if (chunks.isEmpty()) throw RuntimeException("Volcengine TTS response is empty")
    for (chunk in chunks) {
        val root = parseTtsJsonObject(chunk, json)
        val code = root["code"]?.asIntOrNull() ?: 0
        if (code == VOLCENGINE_TTS_STREAM_END_CODE) break
        if (code != 0) {
            val message = root["message"].asStringOrNull().orEmpty()
            throw RuntimeException("Volcengine TTS error $code: ${message.take(200)}")
        }
        root["data"].asStringOrNull()?.takeIf(String::isNotBlank)?.let { audio ->
            val bytes = decodeBase64Audio(
                audio,
                "Volcengine TTS response contains invalid base64 audio",
            )
            output.write(bytes)
        }
    }
    if (output.size() == 0) throw RuntimeException("Volcengine TTS response missing audio data")
    return TtsAudioPayload(output.toByteArray(), "audio/mpeg")
}

internal fun splitTopLevelJsonObjects(raw: String): List<String> {
    val objects = mutableListOf<String>()
    var start = -1
    var depth = 0
    var inString = false
    var escaped = false
    raw.forEachIndexed { index, char ->
        if (start < 0) {
            if (char.isWhitespace()) return@forEachIndexed
            if (char != '{') throw RuntimeException("Volcengine TTS response contains invalid JSON chunks")
            start = index
            depth = 1
            return@forEachIndexed
        }
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            return@forEachIndexed
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    objects += raw.substring(start, index + 1)
                    start = -1
                } else if (depth < 0) {
                    throw RuntimeException("Volcengine TTS response contains invalid JSON chunks")
                }
            }
        }
    }
    if (start >= 0 || inString || depth != 0) {
        throw RuntimeException("Volcengine TTS response contains incomplete JSON chunks")
    }
    return objects
}

internal fun decodeMiniMaxTtsPayload(
    raw: String,
    json: Json = Json { ignoreUnknownKeys = true },
): TtsAudioPayload {
    val root = parseTtsJsonObject(raw, json)
    val baseResponse = root["base_resp"].asObjectOrNull()
    val statusCode = baseResponse?.get("status_code")?.asIntOrNull()
    if (statusCode != null && statusCode != 0) {
        val message = baseResponse["status_msg"].asStringOrNull().orEmpty()
        throw RuntimeException("MiniMax TTS error $statusCode: ${message.take(200)}")
    }
    val audioHex = root["data"].asObjectOrNull()
        ?.get("audio")
        .asStringOrNull()
        ?.takeIf(String::isNotBlank)
        ?: throw RuntimeException("MiniMax TTS response missing data.audio")
    val bytes = decodeHexAudio(audioHex)
    val format = root["extra_info"].asObjectOrNull()
        ?.get("audio_format")
        .asStringOrNull()
    return TtsAudioPayload(bytes, format?.let(::mimeTypeForFormat) ?: "audio/mpeg")
}

internal fun decodeMimoTtsPayload(
    raw: String,
    json: Json = Json { ignoreUnknownKeys = true },
): TtsAudioPayload {
    val root = parseTtsJsonObject(raw, json)
    root["error"]?.let { error ->
        val message = error.asObjectOrNull()?.get("message").asStringOrNull()
            ?: error.asStringOrNull()
        if (!message.isNullOrBlank()) throw RuntimeException("MiMo TTS error: ${message.take(200)}")
    }
    val audio = runCatching {
        root["choices"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("audio")
            ?.jsonObject
            ?.get("data")
            .asStringOrNull()
    }.getOrNull()?.takeIf(String::isNotBlank)
        ?: throw RuntimeException("MiMo TTS response missing choices[0].message.audio.data")
    return TtsAudioPayload(
        bytes = decodeBase64Audio(audio, "MiMo TTS response contains invalid base64 audio"),
        mimeType = "audio/wav",
    )
}

internal fun decodeHexAudio(raw: String): ByteArray {
    val normalized = raw.filterNot(Char::isWhitespace)
    if (normalized.isEmpty() || normalized.length % 2 != 0) {
        throw RuntimeException("MiniMax TTS response contains invalid hex audio")
    }
    return ByteArray(normalized.length / 2) { index ->
        val offset = index * 2
        normalized.substring(offset, offset + 2).toIntOrNull(16)?.toByte()
            ?: throw RuntimeException("MiniMax TTS response contains invalid hex audio")
    }
}

internal fun mimoVoiceSampleMimeType(contentType: String?, displayName: String?): String? {
    val normalized = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (normalized in setOf("audio/mpeg", "audio/mp3")) return "audio/mpeg"
    if (normalized in setOf("audio/wav", "audio/x-wav", "audio/wave")) return "audio/wav"
    return when (displayName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        else -> null
    }
}

internal fun mimoVoiceSampleDataUrl(bytes: ByteArray, mimeType: String): String {
    val encoded = Base64.getEncoder().encodeToString(bytes)
    if (encoded.toByteArray(Charsets.US_ASCII).size > MIMO_VOICE_SAMPLE_MAX_BASE64_BYTES) {
        throw IllegalStateException("MiMo voice sample exceeds the 10 MB base64 limit")
    }
    return "data:$mimeType;base64,$encoded"
}

internal fun ttsAudioExtension(mimeType: String?): String = when (
    mimeType?.substringBefore(';')?.lowercase()
) {
    "audio/wav",
    "audio/x-wav" -> "wav"
    "audio/aac",
    "audio/x-aac" -> "aac"
    "audio/ogg" -> "ogg"
    "audio/flac" -> "flac"
    "audio/mp4",
    "audio/m4a" -> "m4a"
    else -> "mp3"
}

internal fun isSupportedBinaryTtsContentType(contentType: String?): Boolean {
    val normalized = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
    return normalized.startsWith("audio/") || normalized == "application/octet-stream"
}

internal fun selectSystemTtsVoiceName(availableNames: Collection<String>, requested: String): String? {
    val candidate = requested.trim()
    if (candidate.isEmpty()) return null
    return availableNames.firstOrNull { it.equals(candidate, ignoreCase = true) }
}

private fun parseTtsJsonObject(raw: String, json: Json): JsonObject =
    runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
        throw RuntimeException("TTS JSON parse failed: ${raw.take(200)}")
    }

private fun firstNonBlankAudioField(root: JsonObject): String? {
    val direct = listOf(root["audioBase64"], root["audio"], root["data"])
        .firstNotNullOfOrNull { it.asStringOrNull()?.takeIf(String::isNotBlank) }
    if (direct != null) return direct
    val data = root["data"].asObjectOrNull()
    return data?.let {
        listOf(it["audioBase64"], it["audio"], it["base64"])
            .firstNotNullOfOrNull { element -> element.asStringOrNull()?.takeIf(String::isNotBlank) }
    }
}

private fun decodeBase64Audio(raw: String, errorMessage: String): ByteArray {
    val bytes = runCatching { Base64.getDecoder().decode(cleanBase64(raw)) }.getOrElse {
        throw RuntimeException(errorMessage)
    }
    if (bytes.isEmpty()) throw RuntimeException(errorMessage.replace("invalid", "empty"))
    return bytes
}

private fun cleanBase64(raw: String): String {
    val stripped = raw.trim().let { value ->
        if (value.startsWith("data:", ignoreCase = true)) value.substringAfter(',') else value
    }
    return stripped.filterNot(Char::isWhitespace)
}

private fun mimeTypeForFormat(format: String): String? = when (format.lowercase()) {
    "mp3", "mpeg" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "aac" -> "audio/aac"
    "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    "m4a", "mp4" -> "audio/mp4"
    else -> null
}

private fun JsonElement?.asStringOrNull(): String? =
    runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()

private fun JsonElement?.asIntOrNull(): Int? =
    runCatching { this?.jsonPrimitive?.intOrNull }.getOrNull()

private fun JsonElement?.asObjectOrNull(): JsonObject? =
    runCatching { this?.jsonObject }.getOrNull()
