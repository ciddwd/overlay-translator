package com.gameocr.app.asr

import android.content.res.AssetManager
import android.os.SystemClock
import com.gameocr.app.data.AsrCloudConnections
import com.gameocr.app.data.AsrLanguage
import com.gameocr.app.data.AsrSegmentDurations
import com.gameocr.app.data.AsrSegmentationSettings
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import timber.log.Timber

internal interface RealtimeTurnDetector : AutoCloseable {
    val speaking: Boolean
    fun accept(samples: FloatArray): Int
    fun reset()
}

private class SileroRealtimeTurnDetector(assets: AssetManager, durations: AsrSegmentDurations) : RealtimeTurnDetector {
    private val vad = Vad(assetManager = assets, config = AsrVadPolicy.config(durations))
    override val speaking get() = vad.isSpeechDetected()
    override fun accept(samples: FloatArray): Int {
        vad.acceptWaveform(samples)
        var turns = 0
        while (!vad.empty()) { vad.pop(); turns++ }
        return turns
    }
    override fun reset() = vad.reset()
    override fun close() = vad.release()
}

/** Continuous audio input with client VAD turn commits; no file upload or HTTP fallback. */
internal class OpenAiRealtimeAsrEngine(
    client: OkHttpClient,
    assets: AssetManager?,
    private val config: AsrCloudConnections,
    private val language: AsrLanguage,
    private val sink: (SpeechEvent) -> Unit,
    private val onFailure: (CloudAsrFailure) -> Unit,
    private val metrics: SpeechMetrics?,
    private val onReady: () -> Unit,
    turnDetector: RealtimeTurnDetector? = null,
    socketFactory: WebSocket.Factory? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    durations: AsrSegmentDurations = AsrSegmentationSettings().realtime,
    private val hintSource: AsrHintSource = AsrHintSource(),
) : SpeechEngine {
    private val client = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val factory = socketFactory ?: this.client
    private val vad = turnDetector ?: SileroRealtimeTurnDetector(checkNotNull(assets), durations)
    /* Native VAD stays behind the same reset/close boundary as the socket. */
    private val resampler = RealtimePcmResampler()
    private val items = OpenAiRealtimeAsrItems()
    private val timings = CloudSentenceTimings()
    private val pending = ArrayDeque<String>()
    private val commits = ArrayDeque<Long>()
    private var pendingBytes = 0
    private var epoch = 0L
    private var socket: WebSocket? = null
    private var ready = false
    private var closed = false
    private var failed = false
    private var failureCount = 0
    private var retryAt = 0L
    private var deadline: Job? = null
    private var inputSamples = 0
    private var speechSeen = false

    override fun accept(samples: FloatArray, backlogSamples: Int) = synchronized(lock) {
        if (closed || failed || samples.isEmpty()) return@synchronized
        if (socket == null) {
            if (clock() < retryAt) return@synchronized
            connectLocked()
        }
        val turns = vad.accept(samples)
        speechSeen = speechSeen || vad.speaking
        inputSamples += samples.size
        val pcm = resampler.convert(samples)
        if (!sendLocked(OpenAiRealtimeAsrProtocol.audio(pcm))) return@synchronized
        repeat(turns) {
            // OpenAI requires at least 100 ms of input before commit.
            if (inputSamples >= 1600) {
                if (!sendLocked(OpenAiRealtimeAsrProtocol.commit())) return@synchronized
                commits.addLast(clock())
            }
            inputSamples = 0
            speechSeen = false
        }
        // Bound the remote buffer during long silence, without clearing an active utterance.
        if (!speechSeen && inputSamples >= 16000 * 8) {
            sendLocked("""{"type":"input_audio_buffer.clear"}""")
            inputSamples = 0
        }
    }

    private fun sendLocked(message: String): Boolean {
        val active = socket ?: return false
        if (!ready) {
            if (pendingBytes + message.length > MAX_PENDING_BYTES) {
                failLocked(epoch, CloudAsrFailure.CONNECTION)
                return false
            }
            pending.addLast(message)
            pendingBytes += message.length
            return true
        }
        if (active.queueSize() > MAX_PENDING_BYTES || !active.send(message)) {
            failLocked(epoch, CloudAsrFailure.CONNECTION)
            return false
        }
        return true
    }

    private fun connectLocked() {
        val myEpoch = ++epoch
        val request = Request.Builder()
            .url(checkNotNull(OpenAiRealtimeAsrProtocol.endpoint(config.openAiBaseUrl)))
            .header("Authorization", "Bearer ${config.openAiApiKey.trim()}").build()
        socket = factory.newWebSocket(request, Listener(myEpoch))
        deadline?.cancel()
        val connectingAt = clock()
        deadline = scope.launch {
            while (isActive) {
                delay(1000)
                synchronized(lock) {
                    val commitStalled = commits.firstOrNull()?.let { clock() - it >= 30000 } ?: false
                    if ((!ready && clock() - connectingAt >= 10000) || commitStalled || items.timedOut(clock())) {
                        failLocked(myEpoch, CloudAsrFailure.CONNECTION)
                    }
                }
            }
        }
        Timber.i("[asr-openai-realtime] connecting epoch=%d", myEpoch)
    }

    private inner class Listener(private val myEpoch: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (closed || myEpoch != epoch) return@synchronized
            if (!webSocket.send(OpenAiRealtimeAsrProtocol.session(config.openAiModel, language, hintSource.current))) {
                failLocked(myEpoch, CloudAsrFailure.CONNECTION)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (closed || myEpoch != epoch) return@synchronized
            if (text.length > 128 * 1024) {
                failLocked(myEpoch, CloudAsrFailure.SERVICE)
                return@synchronized
            }
            val root = OpenAiRealtimeAsrProtocol.parse(text) ?: return@synchronized
            when (root.str("type")) {
                "session.updated" -> {
                    if (ready) return@synchronized
                    ready = true
                    while (pending.isNotEmpty()) {
                        if (!sendLocked(pending.removeFirst())) return@synchronized
                    }
                    pendingBytes = 0
                    Timber.i("[asr-openai-realtime] session confirmed epoch=%d", myEpoch)
                    onReady()
                }
                "error", "conversation.item.input_audio_transcription.failed" -> {
                    val error = root["error"] as? JsonObject
                    val code = error?.str("code")?.takeIf(String::isNotBlank) ?: error?.str("type").orEmpty()
                    Timber.w("[asr-openai-realtime] server error %s", CloudSpeechErrorDiagnostic.describe(
                        code, error?.str("message").orEmpty(), config.openAiApiKey))
                    failLocked(myEpoch, failureForCode(code))
                }
                else -> {
                    val events = runCatching { items.accept(root, clock()) }.getOrElse {
                        failLocked(myEpoch, CloudAsrFailure.SERVICE)
                        return@synchronized
                    }
                    for (event in events) {
                        failureCount = 0 // A real transcript proves recovery, not a handshake.
                        if (event.isFinal) {
                            if (commits.isNotEmpty()) commits.removeFirst()
                            metrics?.onCloudSentence(timings.finish(event.segmentId, clock()))
                        }
                        else timings.start(event.segmentId, clock())
                        sink(event)
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            response?.close()
            failLocked(myEpoch, CloudSpeechFailurePolicy.http(response?.code))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            synchronized(lock) { failLocked(myEpoch, CloudAsrFailure.CONNECTION) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            failLocked(myEpoch, CloudAsrFailure.CONNECTION)
        }
    }

    private fun failLocked(myEpoch: Long, failure: CloudAsrFailure) {
        if (closed || failed || epoch != myEpoch) return
        failureCount++
        resetLocked()
        Timber.w("[asr-openai-realtime] failure=%s attempt=%d", failure.name, failureCount)
        if (!failure.retryable || failureCount >= 3) {
            failed = true
            onFailure(failure)
        } else retryAt = clock() + 500L * failureCount
    }

    private fun resetLocked() {
        epoch++
        deadline?.cancel()
        socket?.cancel()
        socket = null
        ready = false
        pending.clear()
        commits.clear()
        pendingBytes = 0
        items.reset()
        timings.reset()
        resampler.reset()
        vad.reset()
        inputSamples = 0
        speechSeen = false
    }

    override fun reset() = synchronized(lock) { resetLocked(); retryAt = 0 }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            resetLocked()
            scope.cancel()
            vad.close()
        }
    }

    companion object {
        private const val MAX_PENDING_BYTES = 512 * 1024L
        internal fun failureForCode(code: String): CloudAsrFailure = when (code.lowercase()) {
            "invalid_api_key", "authentication_error" -> CloudAsrFailure.AUTH
            "insufficient_quota" -> CloudAsrFailure.QUOTA
            "rate_limit_exceeded" -> CloudAsrFailure.RATE_LIMIT
            "invalid_request_error", "invalid_value", "unknown_parameter", "model_not_found", "unsupported_model" -> CloudAsrFailure.CONFIGURATION
            else -> CloudAsrFailure.SERVICE
        }
    }
}
