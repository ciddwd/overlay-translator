package com.gameocr.app.tts

import android.content.Context
import android.database.Cursor
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.gameocr.app.R
import com.gameocr.app.data.MimoTtsModel
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TtsHttpResponseMode
import com.gameocr.app.data.TtsProvider
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import timber.log.Timber

private const val MAX_TTS_AUDIO_BYTES = 20L * 1024L * 1024L
private const val MAX_TTS_JSON_BYTES = 48L * 1024L * 1024L
private const val MAX_MIMO_SAMPLE_RAW_BYTES = (MIMO_VOICE_SAMPLE_MAX_BASE64_BYTES / 4) * 3

@Singleton
class HttpTtsEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val playbackCoordinator: TtsPlaybackCoordinator,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playerMutex = Mutex()
    private val requestGeneration = AtomicLong(0L)
    private val activeCall = AtomicReference<Call?>()
    private val activeToken = AtomicReference<Long?>()
    private var player: MediaPlayer? = null
    private var playerLoudnessEnhancer: LoudnessEnhancer? = null
    private var playerFile: File? = null
    private var playerToken: Long? = null

    suspend fun speak(text: String, settings: Settings, token: Long) {
        val normalized = normalizedTtsTextOrNull(text) ?: return
        activeToken.getAndSet(token)?.let(playbackCoordinator::finish)
        val generation = requestGeneration.incrementAndGet()
        try {
            withContext(Dispatchers.Main.immediate) {
                if (generation == requestGeneration.get()) stopPlayerOnMain()
            }
            val requestSpec = withContext(Dispatchers.IO) { requestSpec(normalized, settings) }
            if (generation != requestGeneration.get()) return
            val requestBody = requestSpec.body
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val requestBuilder = Request.Builder()
                .url(requestSpec.endpoint)
                .post(requestBody)
                .header("Accept", requestSpec.accept)
            requestSpec.bearerToken.takeIf(String::isNotBlank)?.let { bearerToken ->
                requestBuilder.header("Authorization", "Bearer $bearerToken")
            }
            requestSpec.headers.forEach { (name, value) -> requestBuilder.header(name, value) }

            val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
            val call = timedClient.newCall(requestBuilder.build())
            activeCall.getAndSet(call)?.cancel()
            val responsePayload = withContext(Dispatchers.IO) {
                try {
                    runCatching { execute(call, requestSpec) }.fold(
                        onSuccess = { it },
                        onFailure = { error ->
                            if (generation != requestGeneration.get()) null else throw error
                        },
                    )
                } finally {
                    activeCall.compareAndSet(call, null)
                }
            } ?: return
            if (generation != requestGeneration.get()) return
            play(responsePayload, generation, token, settings.ttsGainDb)
        } catch (error: Throwable) {
            activeToken.compareAndSet(token, null)
            playbackCoordinator.finish(token)
            throw error
        }
    }

    fun pause() {
        val token = activeToken.get() ?: return
        val state = playbackCoordinator.state.value
        if (
            state.token != token ||
            state.phase !in setOf(TtsPlaybackPhase.LOADING, TtsPlaybackPhase.PLAYING)
        ) {
            return
        }
        if (!playbackCoordinator.transition(token, TtsPlaybackPhase.PAUSED)) return
        runOnMain {
            if (activeToken.get() != token || playerToken != token) return@runOnMain
            runCatching { if (player?.isPlaying == true) player?.pause() }
        }
    }

    fun resume() {
        val token = activeToken.get() ?: return
        val state = playbackCoordinator.state.value
        if (state.token != token || state.phase != TtsPlaybackPhase.PAUSED) return
        runOnMain {
            if (activeToken.get() != token) return@runOnMain
            val currentPlayer = player.takeIf { playerToken == token }
            if (currentPlayer == null) {
                playbackCoordinator.transition(token, TtsPlaybackPhase.LOADING)
                return@runOnMain
            }
            runCatching { currentPlayer.start() }
                .onSuccess {
                    playbackCoordinator.transition(token, TtsPlaybackPhase.PLAYING)
                }
                .onFailure {
                    activeToken.compareAndSet(token, null)
                    stopPlayerOnMain()
                    playbackCoordinator.finish(token)
                }
        }
    }

    fun stop() {
        val stopGeneration = requestGeneration.incrementAndGet()
        activeCall.getAndSet(null)?.cancel()
        activeToken.getAndSet(null)?.let(playbackCoordinator::finish)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopPlayerOnMain()
        } else {
            mainHandler.post {
                if (stopGeneration == requestGeneration.get()) stopPlayerOnMain()
            }
        }
    }

    private fun execute(call: Call, spec: TtsRequestSpec): TtsAudioPayload =
        call.execute().use { response ->
            val body = response.body ?: throw RuntimeException("TTS HTTP ${response.code}: empty body")
            if (!response.isSuccessful) {
                val errorBody = readBodyBytes(body, 16_384L).toString(Charsets.UTF_8)
                throw RuntimeException("TTS HTTP ${response.code}: ${errorBody.take(200)}")
            }
            val payload = when (spec.responseKind) {
                TtsResponseKind.BINARY -> {
                    val contentType = response.header("Content-Type")
                    if (!isSupportedBinaryTtsContentType(contentType)) {
                        throw RuntimeException("TTS binary response has unsupported Content-Type: $contentType")
                    }
                    TtsAudioPayload(
                        bytes = readBodyBytes(body, MAX_TTS_AUDIO_BYTES),
                        mimeType = contentType,
                    )
                }
                TtsResponseKind.GENERIC_JSON_BASE64 -> decodeTtsJsonAudioPayload(
                    readBodyBytes(body, MAX_TTS_JSON_BYTES).toString(Charsets.UTF_8),
                    json,
                )
                TtsResponseKind.VOLCENGINE_JSON_CHUNKS -> decodeVolcengineTtsPayload(
                    readBodyBytes(body, MAX_TTS_JSON_BYTES).toString(Charsets.UTF_8),
                    json,
                )
                TtsResponseKind.MINIMAX_JSON_HEX -> decodeMiniMaxTtsPayload(
                    readBodyBytes(body, MAX_TTS_JSON_BYTES).toString(Charsets.UTF_8),
                    json,
                )
                TtsResponseKind.MIMO_JSON_BASE64 -> decodeMimoTtsPayload(
                    readBodyBytes(body, MAX_TTS_JSON_BYTES).toString(Charsets.UTF_8),
                    json,
                )
            }
            if (payload.bytes.isEmpty()) throw RuntimeException("TTS audio response is empty")
            if (payload.bytes.size > MAX_TTS_AUDIO_BYTES) {
                throw RuntimeException("TTS audio response exceeds 20 MB")
            }
            payload
        }

    private fun requestSpec(text: String, settings: Settings): TtsRequestSpec = when (settings.ttsProvider) {
        TtsProvider.GENERIC_HTTP -> TtsRequestSpec(
            endpoint = ttsEndpointUrlOrNull(settings.ttsHttpBaseUrl)
                ?: throw IllegalStateException("TTS HTTP URL is required"),
            bearerToken = settings.ttsHttpBearerToken.trim(),
            body = buildTtsHttpPayload(text, settings),
            accept = if (settings.ttsHttpResponseMode == TtsHttpResponseMode.JSON_BASE64) {
                "application/json"
            } else {
                "audio/*"
            },
            responseKind = if (settings.ttsHttpResponseMode == TtsHttpResponseMode.JSON_BASE64) {
                TtsResponseKind.GENERIC_JSON_BASE64
            } else {
                TtsResponseKind.BINARY
            },
        )
        TtsProvider.VOLCENGINE -> TtsRequestSpec(
            endpoint = volcengineTtsEndpointUrlOrNull(settings.ttsVolcengineBaseUrl)
                ?: throw IllegalStateException("Volcengine Base URL is invalid"),
            bearerToken = "",
            headers = volcengineTtsHeaders(
                apiKey = settings.ttsVolcengineApiKey,
                resource = settings.ttsVolcengineResource,
                requestId = UUID.randomUUID().toString(),
            ),
            body = buildVolcengineTtsPayload(text, settings),
            accept = "application/json",
            responseKind = TtsResponseKind.VOLCENGINE_JSON_CHUNKS,
        )
        TtsProvider.MINIMAX -> TtsRequestSpec(
            endpoint = miniMaxTtsEndpointUrlOrNull(settings.ttsMiniMaxBaseUrl)
                ?: throw IllegalStateException("MiniMax Base URL is invalid"),
            bearerToken = settings.ttsMiniMaxApiKey.trim().ifBlank {
                throw IllegalStateException("MiniMax API Key is required")
            },
            body = buildMiniMaxTtsPayload(text, settings),
            accept = "application/json",
            responseKind = TtsResponseKind.MINIMAX_JSON_HEX,
        )
        TtsProvider.MIMO -> TtsRequestSpec(
            endpoint = mimoTtsEndpointUrlOrNull(settings.ttsMimoBaseUrl)
                ?: throw IllegalStateException("MiMo Base URL is invalid"),
            bearerToken = settings.ttsMimoApiKey.trim().ifBlank {
                throw IllegalStateException("MiMo API Key is required")
            },
            body = buildMimoTtsPayload(
                text = text,
                settings = settings,
                voiceSampleDataUrl = if (settings.ttsMimoModel == MimoTtsModel.VOICE_CLONE) {
                    readMimoVoiceSample(settings.ttsMimoVoiceSampleUri)
                } else {
                    null
                },
            ),
            accept = "application/json",
            responseKind = TtsResponseKind.MIMO_JSON_BASE64,
        )
        TtsProvider.SYSTEM -> throw IllegalArgumentException("System TTS is not an HTTP provider")
    }

    private fun readMimoVoiceSample(rawUri: String): String {
        val normalized = rawUri.trim().ifBlank { MIMO_BUILTIN_VOICE_REFERENCE_1 }
        val builtInResource = when (normalized) {
            MIMO_BUILTIN_VOICE_REFERENCE_1 -> R.raw.mimo_voice_reference_1
            MIMO_BUILTIN_VOICE_REFERENCE_2 -> R.raw.mimo_voice_reference_2
            else -> null
        }
        if (builtInResource != null) {
            val bytes = appContext.resources.openRawResource(builtInResource).use { input ->
                readInputAtMost(input, MAX_MIMO_SAMPLE_RAW_BYTES + 1)
            }
            if (bytes.size > MAX_MIMO_SAMPLE_RAW_BYTES) {
                throw IllegalStateException("MiMo voice sample exceeds the 10 MB base64 limit")
            }
            return mimoVoiceSampleDataUrl(bytes, "audio/wav")
        }
        val uri = normalized.takeIf(String::isNotEmpty)?.let(Uri::parse)
            ?: throw IllegalStateException("MiMo voice clone requires an MP3 or WAV sample")
        val resolver = appContext.contentResolver
        val displayName = resolver.queryDisplayName(uri) ?: uri.lastPathSegment
        val mimeType = mimoVoiceSampleMimeType(resolver.getType(uri), displayName)
            ?: throw IllegalStateException("MiMo voice sample must be MP3 or WAV")
        val bytes = resolver.openInputStream(uri)?.use { input ->
            readInputAtMost(input, MAX_MIMO_SAMPLE_RAW_BYTES + 1)
        } ?: throw IllegalStateException("Unable to open MiMo voice sample")
        if (bytes.size > MAX_MIMO_SAMPLE_RAW_BYTES) {
            throw IllegalStateException("MiMo voice sample exceeds the 10 MB base64 limit")
        }
        return mimoVoiceSampleDataUrl(bytes, mimeType)
    }

    private suspend fun play(
        payload: TtsAudioPayload,
        generation: Long,
        token: Long,
        gainDb: Int,
    ) {
        val extension = ttsAudioExtension(payload.mimeType)
        val file = withContext(Dispatchers.IO) {
            val dir = File(appContext.cacheDir, "tts").apply { mkdirs() }
            File.createTempFile("tts-", ".$extension", dir).also { it.writeBytes(payload.bytes) }
        }
        if (generation != requestGeneration.get()) {
            file.delete()
            return
        }
        playerMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                if (generation != requestGeneration.get()) {
                    file.delete()
                    return@withContext
                }
                if (activeToken.get() != token || playbackCoordinator.state.value.token != token) {
                    file.delete()
                    return@withContext
                }
                stopPlayerOnMain()
                val nextPlayer = MediaPlayer()
                try {
                    nextPlayer.setDataSource(file.absolutePath)
                    nextPlayer.setOnCompletionListener { completed ->
                        if (player === completed) {
                            runCatching { playerLoudnessEnhancer?.release() }
                            playerLoudnessEnhancer = null
                            player = null
                        }
                        completed.release()
                        if (playerFile == file) playerFile = null
                        if (playerToken == token) playerToken = null
                        activeToken.compareAndSet(token, null)
                        playbackCoordinator.finish(token)
                        file.delete()
                    }
                    nextPlayer.setOnErrorListener { failed, _, _ ->
                        if (player === failed) {
                            runCatching { playerLoudnessEnhancer?.release() }
                            playerLoudnessEnhancer = null
                            player = null
                        }
                        failed.release()
                        if (playerFile == file) playerFile = null
                        if (playerToken == token) playerToken = null
                        activeToken.compareAndSet(token, null)
                        playbackCoordinator.finish(token)
                        file.delete()
                        true
                    }
                    nextPlayer.prepare()
                    val nextEnhancer = createPlaybackGain(nextPlayer, gainDb)
                    player = nextPlayer
                    playerLoudnessEnhancer = nextEnhancer
                    playerFile = file
                    playerToken = token
                    if (playbackCoordinator.state.value.phase != TtsPlaybackPhase.PAUSED) {
                        nextPlayer.start()
                        playbackCoordinator.transition(token, TtsPlaybackPhase.PLAYING)
                    }
                } catch (error: Throwable) {
                    if (player === nextPlayer) {
                        stopPlayerOnMain()
                    } else {
                        runCatching { nextPlayer.release() }
                    }
                    file.delete()
                    throw error
                }
            }
        }
    }

    private fun stopPlayerOnMain() {
        val current = player
        val currentEnhancer = playerLoudnessEnhancer
        val currentFile = playerFile
        val currentToken = playerToken
        player = null
        playerLoudnessEnhancer = null
        playerFile = null
        playerToken = null
        runCatching { currentEnhancer?.release() }
        runCatching { current?.stop() }
        runCatching { current?.release() }
        currentFile?.delete()
        currentToken?.let(playbackCoordinator::finish)
    }

    private fun createPlaybackGain(player: MediaPlayer, gainDb: Int): LoudnessEnhancer? {
        val normalizedGainDb = normalizedTtsPlaybackGainDb(gainDb)
        if (normalizedGainDb == 0) {
            Timber.i("TTS HTTP playback gain=0dB effect=disabled")
            return null
        }
        val enhancer = LoudnessEnhancer(player.audioSessionId)
        return try {
            enhancer.setTargetGain(ttsPlaybackGainMillibels(normalizedGainDb))
            enhancer.enabled = true
            Timber.i(
                "TTS HTTP playback gain=%ddB sessionId=%d",
                normalizedGainDb,
                player.audioSessionId,
            )
            enhancer
        } catch (error: Throwable) {
            runCatching { enhancer.release() }
            throw error
        }
    }

    internal suspend fun playAudio(
        payload: TtsAudioPayload,
        playbackId: String,
        gainDb: Int,
    ) {
        require(payload.bytes.isNotEmpty()) { "TTS preview audio is empty" }
        require(payload.bytes.size <= MAX_TTS_AUDIO_BYTES) { "TTS preview audio exceeds 20 MB" }
        val generation = requestGeneration.incrementAndGet()
        activeCall.getAndSet(null)?.cancel()
        activeToken.getAndSet(null)?.let(playbackCoordinator::finish)
        withContext(Dispatchers.Main.immediate) { stopPlayerOnMain() }
        if (generation != requestGeneration.get()) return
        val token = playbackCoordinator.begin(playbackId, TtsPlaybackBackend.HTTP)
        activeToken.set(token)
        try {
            play(payload, generation, token, gainDb)
        } catch (error: Throwable) {
            activeToken.compareAndSet(token, null)
            playbackCoordinator.finish(token)
            throw error
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}

private data class TtsRequestSpec(
    val endpoint: String,
    val bearerToken: String,
    val body: String,
    val accept: String,
    val responseKind: TtsResponseKind,
    val headers: Map<String, String> = emptyMap(),
)

private enum class TtsResponseKind {
    BINARY,
    GENERIC_JSON_BASE64,
    VOLCENGINE_JSON_CHUNKS,
    MINIMAX_JSON_HEX,
    MIMO_JSON_BASE64,
}

private fun readBodyBytes(body: ResponseBody, maxBytes: Long): ByteArray {
    val contentLength = body.contentLength()
    if (contentLength > maxBytes) throw RuntimeException("TTS response is too large")
    val source = body.source()
    source.request(maxBytes + 1)
    if (source.buffer.size > maxBytes) throw RuntimeException("TTS response is too large")
    return source.readByteArray()
}

private fun readInputAtMost(input: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    while (output.size() < maxBytes) {
        val remaining = maxBytes - output.size()
        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun android.content.ContentResolver.queryDisplayName(uri: Uri): String? {
    var cursor: Cursor? = null
    return try {
        cursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor?.moveToFirst() == true) cursor.getString(0) else null
    } finally {
        cursor?.close()
    }
}
