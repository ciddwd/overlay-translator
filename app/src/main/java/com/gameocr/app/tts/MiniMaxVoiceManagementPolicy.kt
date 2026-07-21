package com.gameocr.app.tts

import android.net.Uri
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val MINIMAX_AUDIO_MAX_BYTES = 20L * 1024L * 1024L
internal const val MINIMAX_CLONE_AUDIO_MIN_DURATION_MS = 10_000L
internal const val MINIMAX_CLONE_AUDIO_MAX_DURATION_MS = 5L * 60L * 1_000L
internal const val MINIMAX_PROMPT_AUDIO_MAX_DURATION_MS = 8_000L

internal enum class MiniMaxManagedVoiceType(val apiId: String) {
    CLONING("voice_cloning"),
    GENERATION("voice_generation"),
}

internal data class MiniMaxManagedVoice(
    val voiceId: String,
    val type: MiniMaxManagedVoiceType,
    val description: List<String> = emptyList(),
    val createdTime: String = "",
) {
    val searchableText: String = buildString {
        append(voiceId)
        append('\n')
        append(type.apiId)
        description.forEach { value -> append('\n').append(value) }
    }.lowercase(Locale.ROOT)
}

internal data class MiniMaxVoiceCloneRequest(
    val baseUrl: String,
    val apiKey: String,
    val sourceAudioUri: Uri,
    val voiceId: String,
    val promptAudioUri: Uri? = null,
    val promptText: String = "",
    val textValidation: String = "",
    val needNoiseReduction: Boolean = false,
    val needVolumeNormalization: Boolean = false,
)

internal data class MiniMaxVoiceDesignRequest(
    val baseUrl: String,
    val apiKey: String,
    val prompt: String,
    val previewText: String,
    val customVoiceId: String = "",
)

internal data class MiniMaxVoiceCreationResult(
    val voice: MiniMaxManagedVoice,
    val previewAudio: String? = null,
)

internal data class MiniMaxAudioMetadata(
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
)

internal enum class MiniMaxAudioPurpose(val apiId: String) {
    VOICE_CLONE("voice_clone"),
    PROMPT_AUDIO("prompt_audio"),
}

internal enum class MiniMaxAudioValidationError {
    UNSUPPORTED_FORMAT,
    FILE_TOO_LARGE,
    CLONE_TOO_SHORT,
    CLONE_TOO_LONG,
    PROMPT_TOO_LONG,
}

internal enum class MiniMaxVoiceIdValidationError {
    TOO_SHORT,
    TOO_LONG,
    INVALID_FIRST_CHARACTER,
    INVALID_CHARACTER,
    INVALID_LAST_CHARACTER,
}

internal class MiniMaxAudioValidationException(
    val reason: MiniMaxAudioValidationError,
) : IllegalArgumentException(reason.name)

internal class MiniMaxVoiceIdValidationException(
    val reason: MiniMaxVoiceIdValidationError,
) : IllegalArgumentException(reason.name)

internal fun miniMaxVoiceIdValidationError(raw: String): MiniMaxVoiceIdValidationError? {
    val value = raw.trim()
    return when {
        value.length < 8 -> MiniMaxVoiceIdValidationError.TOO_SHORT
        value.length > 256 -> MiniMaxVoiceIdValidationError.TOO_LONG
        !value.first().isAsciiLetter() -> MiniMaxVoiceIdValidationError.INVALID_FIRST_CHARACTER
        value.any { !it.isAsciiLetterOrDigit() && it != '-' && it != '_' } ->
            MiniMaxVoiceIdValidationError.INVALID_CHARACTER
        value.last() == '-' || value.last() == '_' -> MiniMaxVoiceIdValidationError.INVALID_LAST_CHARACTER
        else -> null
    }
}

internal fun isValidMiniMaxClonePromptText(raw: String): Boolean {
    val text = raw.trim()
    if (text.isBlank()) return true
    return text.last() in setOf('.', '!', '?', '。', '！', '？', '…')
}

internal fun searchMiniMaxManagedVoices(
    query: String,
    voices: List<MiniMaxManagedVoice>,
): List<MiniMaxManagedVoice> {
    val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
    if (terms.isEmpty()) return voices
    return voices.filter { voice -> terms.all(voice.searchableText::contains) }
}

internal fun shouldLoadMiniMaxManagedVoices(apiKey: String): Boolean = apiKey.isNotBlank()

internal fun mergeMiniMaxManagedVoices(
    remoteVoices: List<MiniMaxManagedVoice>,
    pendingCreatedVoices: List<MiniMaxManagedVoice>,
): List<MiniMaxManagedVoice> {
    val remoteKeys = remoteVoices.mapTo(mutableSetOf()) { it.type to it.voiceId }
    return pendingCreatedVoices.filterNot { (it.type to it.voiceId) in remoteKeys } + remoteVoices
}

internal fun miniMaxAudioValidationError(
    metadata: MiniMaxAudioMetadata,
    purpose: MiniMaxAudioPurpose,
): MiniMaxAudioValidationError? {
    if (!isSupportedMiniMaxAudio(metadata.displayName, metadata.mimeType)) {
        return MiniMaxAudioValidationError.UNSUPPORTED_FORMAT
    }
    if (metadata.sizeBytes != null && metadata.sizeBytes > MINIMAX_AUDIO_MAX_BYTES) {
        return MiniMaxAudioValidationError.FILE_TOO_LARGE
    }
    val duration = metadata.durationMs ?: return null
    return when (purpose) {
        MiniMaxAudioPurpose.VOICE_CLONE -> when {
            duration < MINIMAX_CLONE_AUDIO_MIN_DURATION_MS ->
                MiniMaxAudioValidationError.CLONE_TOO_SHORT
            duration > MINIMAX_CLONE_AUDIO_MAX_DURATION_MS ->
                MiniMaxAudioValidationError.CLONE_TOO_LONG
            else -> null
        }
        MiniMaxAudioPurpose.PROMPT_AUDIO -> when {
            duration >= MINIMAX_PROMPT_AUDIO_MAX_DURATION_MS ->
                MiniMaxAudioValidationError.PROMPT_TOO_LONG
            else -> null
        }
    }
}

internal fun miniMaxVoiceManagementEndpointOrNull(rawBaseUrl: String, endpoint: String): String? {
    val url = rawBaseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
    val currentPath = url.encodedPath.trimEnd('/').ifBlank { "" }
    val apiRoot = when {
        currentPath.endsWith("/v1/t2a_v2", ignoreCase = true) ->
            currentPath.dropLast("/v1/t2a_v2".length)
        currentPath.endsWith("/v1", ignoreCase = true) ->
            currentPath.dropLast("/v1".length)
        else -> currentPath
    }
    val normalizedEndpoint = endpoint.trim().trim('/')
    if (normalizedEndpoint.isBlank()) return null
    val documentedManagementHost = when (url.host.lowercase(Locale.ROOT)) {
        "api-bj.minimaxi.com" -> "api.minimaxi.com"
        "api-uw.minimax.io" -> "api.minimax.io"
        else -> url.host
    }
    return url.newBuilder()
        .host(documentedManagementHost)
        .encodedPath("$apiRoot/v1/$normalizedEndpoint")
        .build()
        .toString()
}

internal fun buildMiniMaxGetVoicesPayload(): String =
    buildJsonObject { put("voice_type", "all") }.toString()

internal fun buildMiniMaxDeleteVoicePayload(
    voice: MiniMaxManagedVoice,
): String = buildJsonObject {
    put("voice_type", voice.type.apiId)
    put("voice_id", voice.voiceId)
}.toString()

internal fun buildMiniMaxVoiceClonePayload(
    fileId: Long,
    request: MiniMaxVoiceCloneRequest,
    promptFileId: Long? = null,
): String = buildJsonObject {
    put("file_id", fileId)
    put("voice_id", request.voiceId.trim())
    if (promptFileId != null) {
        put("clone_prompt", buildJsonObject {
            put("prompt_audio", promptFileId)
            put("prompt_text", request.promptText.trim())
        })
    }
    request.textValidation.trim().takeIf(String::isNotEmpty)?.let { validation ->
        put("text_validation", validation.take(200))
        put("accuracy", 0.7)
    }
    put("need_noise_reduction", request.needNoiseReduction)
    put("need_volume_normalization", request.needVolumeNormalization)
    put("aigc_watermark", false)
}.toString()

internal fun buildMiniMaxVoiceDesignPayload(
    request: MiniMaxVoiceDesignRequest,
): String = buildJsonObject {
    put("prompt", request.prompt.trim())
    put("preview_text", request.previewText.trim().take(500))
    request.customVoiceId.trim().takeIf(String::isNotEmpty)?.let { put("voice_id", it) }
    put("aigc_watermark", false)
}.toString()

internal fun decodeMiniMaxUploadedFileId(raw: String, json: Json): Long {
    val root = parseMiniMaxManagementResponse(raw, json)
    return root["file"]?.jsonObject?.get("file_id")?.jsonPrimitive?.longOrNull
        ?: throw IllegalStateException("MiniMax upload response is missing file_id")
}

internal fun decodeMiniMaxManagedVoices(raw: String, json: Json): List<MiniMaxManagedVoice> {
    val root = parseMiniMaxManagementResponse(raw, json)
    return buildList {
        addMiniMaxManagedVoices(root, "voice_cloning", MiniMaxManagedVoiceType.CLONING)
        addMiniMaxManagedVoices(root, "voice_generation", MiniMaxManagedVoiceType.GENERATION)
    }
}

internal fun decodeMiniMaxVoiceDesignResult(
    raw: String,
    json: Json,
    prompt: String,
): MiniMaxVoiceCreationResult {
    val root = parseMiniMaxManagementResponse(raw, json)
    val voiceId = root["voice_id"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    if (voiceId.isBlank()) throw IllegalStateException("MiniMax voice design response is missing voice_id")
    return MiniMaxVoiceCreationResult(
        voice = MiniMaxManagedVoice(
            voiceId = voiceId,
            type = MiniMaxManagedVoiceType.GENERATION,
            description = listOf(prompt.trim()).filter(String::isNotBlank),
        ),
        previewAudio = root["trial_audio"]?.jsonPrimitive?.contentOrNull,
    )
}

internal fun decodeMiniMaxVoiceCloneResult(
    raw: String,
    json: Json,
    voiceId: String,
): MiniMaxVoiceCreationResult {
    val root = parseMiniMaxManagementResponse(raw, json)
    return MiniMaxVoiceCreationResult(
        voice = MiniMaxManagedVoice(
            voiceId = voiceId,
            type = MiniMaxManagedVoiceType.CLONING,
        ),
        previewAudio = root["demo_audio"]?.jsonPrimitive?.contentOrNull,
    )
}

internal fun requireMiniMaxDeleteSucceeded(raw: String, json: Json, expectedVoiceId: String) {
    val root = parseMiniMaxManagementResponse(raw, json)
    val deletedId = root["voice_id"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    if (deletedId.isNotBlank() && deletedId != expectedVoiceId) {
        throw IllegalStateException("MiniMax deleted an unexpected voice ID")
    }
}

private fun MutableList<MiniMaxManagedVoice>.addMiniMaxManagedVoices(
    root: JsonObject,
    field: String,
    type: MiniMaxManagedVoiceType,
) {
    root[field]?.jsonArray?.forEach { element ->
        val item = element.jsonObject
        val voiceId = item["voice_id"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (voiceId.isBlank()) return@forEach
        add(
            MiniMaxManagedVoice(
                voiceId = voiceId,
                type = type,
                description = item["description"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                    .orEmpty(),
                createdTime = item["created_time"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        )
    }
}

private fun parseMiniMaxManagementResponse(raw: String, json: Json): JsonObject {
    val root = json.parseToJsonElement(raw).jsonObject
    val baseResponse = root["base_resp"]?.jsonObject
    val code = baseResponse?.get("status_code")?.jsonPrimitive?.intOrNull ?: 0
    if (code != 0) {
        val message = baseResponse?.get("status_msg")?.jsonPrimitive?.contentOrNull.orEmpty()
        throw MiniMaxApiException(code, message)
    }
    return root
}

private fun isSupportedMiniMaxAudio(displayName: String?, mimeType: String?): Boolean {
    val extension = displayName.orEmpty().substringAfterLast('.', "").lowercase()
    if (extension in setOf("mp3", "m4a", "wav")) return true
    val normalizedMime = mimeType.orEmpty().substringBefore(';').trim().lowercase()
    return normalizedMime in setOf(
        "audio/mpeg",
        "audio/mp3",
        "audio/mp4",
        "audio/m4a",
        "audio/x-m4a",
        "audio/wav",
        "audio/x-wav",
    )
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'
