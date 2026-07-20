package com.gameocr.app.tts

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source

private const val MINIMAX_MANAGEMENT_MAX_RESPONSE_BYTES = 48L * 1024L * 1024L

@Singleton
class MiniMaxVoiceManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {
    internal suspend fun loadVoices(baseUrl: String, apiKey: String): List<MiniMaxManagedVoice> =
        withContext(Dispatchers.IO) {
            val raw = postJson(
                baseUrl = baseUrl,
                apiKey = apiKey,
                endpoint = "get_voice",
                payload = buildMiniMaxGetVoicesPayload(),
            )
            decodeMiniMaxManagedVoices(raw, json)
        }

    internal suspend fun cloneVoice(request: MiniMaxVoiceCloneRequest): MiniMaxVoiceCreationResult =
        withContext(Dispatchers.IO) {
            miniMaxVoiceIdValidationError(request.voiceId)?.let { reason ->
                throw MiniMaxVoiceIdValidationException(reason)
            }
            val hasPromptAudio = request.promptAudioUri != null
            val hasPromptText = request.promptText.isNotBlank()
            require(hasPromptAudio == hasPromptText) {
                "MiniMax prompt audio and its transcript must be provided together"
            }
            require(isValidMiniMaxClonePromptText(request.promptText)) {
                "MiniMax prompt audio transcript must end with punctuation"
            }
            require(request.textValidation.length <= 200) {
                "MiniMax validation transcript must not exceed 200 characters"
            }

            val fileId = uploadAudio(
                baseUrl = request.baseUrl,
                apiKey = request.apiKey,
                uri = request.sourceAudioUri,
                purpose = MiniMaxAudioPurpose.VOICE_CLONE,
            )
            val promptFileId = request.promptAudioUri?.let { uri ->
                uploadAudio(
                    baseUrl = request.baseUrl,
                    apiKey = request.apiKey,
                    uri = uri,
                    purpose = MiniMaxAudioPurpose.PROMPT_AUDIO,
                )
            }
            val raw = postJson(
                baseUrl = request.baseUrl,
                apiKey = request.apiKey,
                endpoint = "voice_clone",
                payload = buildMiniMaxVoiceClonePayload(fileId, request, promptFileId),
            )
            decodeMiniMaxVoiceCloneResult(raw, json, request.voiceId.trim())
        }

    internal suspend fun designVoice(request: MiniMaxVoiceDesignRequest): MiniMaxVoiceCreationResult =
        withContext(Dispatchers.IO) {
            require(request.prompt.isNotBlank()) { "MiniMax voice description is required" }
            require(request.previewText.isNotBlank()) { "MiniMax preview text is required" }
            require(request.previewText.length <= 500) {
                "MiniMax preview text must not exceed 500 characters"
            }
            val raw = postJson(
                baseUrl = request.baseUrl,
                apiKey = request.apiKey,
                endpoint = "voice_design",
                payload = buildMiniMaxVoiceDesignPayload(request),
            )
            decodeMiniMaxVoiceDesignResult(raw, json, request.prompt)
        }

    internal suspend fun deleteVoice(
        baseUrl: String,
        apiKey: String,
        voice: MiniMaxManagedVoice,
    ) = withContext(Dispatchers.IO) {
        val raw = postJson(
            baseUrl = baseUrl,
            apiKey = apiKey,
            endpoint = "delete_voice",
            payload = buildMiniMaxDeleteVoicePayload(voice),
        )
        requireMiniMaxDeleteSucceeded(raw, json, voice.voiceId)
    }

    private fun uploadAudio(
        baseUrl: String,
        apiKey: String,
        uri: Uri,
        purpose: MiniMaxAudioPurpose,
    ): Long {
        val resolver = appContext.contentResolver
        val metadata = appContext.miniMaxAudioMetadata(uri)
        miniMaxAudioValidationError(metadata, purpose)?.let { reason ->
            throw MiniMaxAudioValidationException(reason)
        }
        val endpointUrl = requireEndpoint(baseUrl, "files/upload")
        val mediaType = miniMaxAudioMediaType(metadata.displayName, metadata.mimeType)
        val fileBody = ContentUriRequestBody(
            resolver = resolver,
            uri = uri,
            contentType = mediaType,
            contentLength = metadata.sizeBytes,
        )
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", purpose.apiId)
            .addFormDataPart(
                "file",
                metadata.displayName?.takeIf(String::isNotBlank) ?: "voice-sample",
                fileBody,
            )
            .build()
        val request = authorizedRequestBuilder(endpointUrl, apiKey)
            .post(body)
            .build()
        return decodeMiniMaxUploadedFileId(execute(request), json)
    }

    private fun postJson(
        baseUrl: String,
        apiKey: String,
        endpoint: String,
        payload: String,
    ): String {
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = authorizedRequestBuilder(requireEndpoint(baseUrl, endpoint), apiKey)
            .post(body)
            .build()
        return execute(request)
    }

    private fun authorizedRequestBuilder(endpointUrl: String, rawApiKey: String): Request.Builder {
        val apiKey = rawApiKey.trim().ifBlank {
            throw IllegalArgumentException("MiniMax API Key is required")
        }
        return Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
    }

    private fun requireEndpoint(baseUrl: String, endpoint: String): String =
        miniMaxVoiceManagementEndpointOrNull(baseUrl, endpoint)
            ?: throw IllegalArgumentException("MiniMax Base URL is invalid")

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body ?: throw IOException("MiniMax HTTP ${response.code}: empty body")
        val bytes = readResponseAtMost(body.contentLength()) { maxBytes ->
            val source = body.source()
            source.request(maxBytes + 1)
            if (source.buffer.size > maxBytes) {
                throw IOException("MiniMax response exceeds 48 MB")
            }
            source.readByteArray()
        }
        val raw = bytes.toString(Charsets.UTF_8)
        if (!response.isSuccessful) {
            throw IOException("MiniMax HTTP ${response.code}: ${raw.take(300)}")
        }
        raw
    }
}

private class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val contentType: okhttp3.MediaType?,
    private val contentLength: Long?,
) : RequestBody() {
    override fun contentType(): okhttp3.MediaType? = contentType

    override fun contentLength(): Long = contentLength ?: -1L

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Unable to open the selected MiniMax audio file")
        input.source().use { source -> sink.writeAll(source) }
    }
}

private fun Context.miniMaxAudioMetadata(uri: Uri): MiniMaxAudioMetadata {
    val resolver = contentResolver
    var displayName: String? = null
    var sizeBytes: Long? = null
    var cursor: Cursor? = null
    try {
        cursor = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
        val current = cursor
        if (current?.moveToFirst() == true) {
            val nameIndex = current.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = current.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0 && !current.isNull(nameIndex)) displayName = current.getString(nameIndex)
            if (sizeIndex >= 0 && !current.isNull(sizeIndex)) sizeBytes = current.getLong(sizeIndex)
        }
    } finally {
        cursor?.close()
    }
    val durationMs = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()
    return MiniMaxAudioMetadata(
        displayName = displayName ?: uri.lastPathSegment,
        mimeType = resolver.getType(uri),
        sizeBytes = sizeBytes,
        durationMs = durationMs,
    )
}

private fun miniMaxAudioMediaType(displayName: String?, mimeType: String?): okhttp3.MediaType? {
    val normalized = mimeType.orEmpty().substringBefore(';').trim()
    if (normalized.startsWith("audio/")) return normalized.toMediaTypeOrNull()
    return when (displayName.orEmpty().substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg".toMediaTypeOrNull()
        "m4a" -> "audio/mp4".toMediaTypeOrNull()
        "wav" -> "audio/wav".toMediaTypeOrNull()
        else -> "application/octet-stream".toMediaTypeOrNull()
    }
}

private inline fun readResponseAtMost(
    contentLength: Long,
    read: (Long) -> ByteArray,
): ByteArray {
    if (contentLength > MINIMAX_MANAGEMENT_MAX_RESPONSE_BYTES) {
        throw IOException("MiniMax response exceeds 48 MB")
    }
    return read(MINIMAX_MANAGEMENT_MAX_RESPONSE_BYTES)
}
