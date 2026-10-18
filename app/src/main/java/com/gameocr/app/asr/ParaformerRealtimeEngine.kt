package com.gameocr.app.asr

import android.os.SystemClock
import com.gameocr.app.data.AsrLanguage
import com.gameocr.app.data.AsrCloudModel
import com.gameocr.app.translate.ChineseScriptNormalizer
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import timber.log.Timber

/** Why the cloud engine gave up; the session stops and shows the matching notice. */
internal enum class CloudAsrFailure(val retryable: Boolean = false) {
    AUTH, CONNECTION(true), QUOTA, NOT_ACTIVATED, OVERDUE, RATE_LIMIT,
    CONFIGURATION, AUDIO, SERVICE(true), REGION, UNKNOWN,
}

/**
 * DashScope paraformer-realtime-v2 over its duplex WebSocket protocol (verified 2026-10-17):
 * https://www.alibabacloud.com/help/en/model-studio/websocket-for-paraformer-real-time-service
 * https://www.alibabacloud.com/help/en/model-studio/paraformer-client-events
 * https://www.alibabacloud.com/help/en/model-studio/paraformer-server-events
 *
 * One WebSocket carries one recognition task. The connection opens lazily on the first audio, and
 * audio is held (bounded) until `task-started`. [reset] ends the task and bumps [epoch]; callbacks
 * from an older socket are ignored, so no result for audio before a reset or pause is delivered.
 * Each `sentence_end` result is final; earlier results for the same sentence are partials.
 */
internal class ParaformerRealtimeEngine(
    client: OkHttpClient,
    private val apiKey: String,
    private val language: AsrLanguage,
    private val sink: (SpeechEvent) -> Unit,
    private val onFailure: (CloudAsrFailure) -> Unit,
    private val metrics: SpeechMetrics?,
    private val cloudModel: AsrCloudModel = AsrCloudModel.PARAFORMER,
    private val targetLanguage: String = "zh-CN",
    private val onReady: () -> Unit = {},
) : SpeechEngine {
    private val client = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
        .build()
    private val lock = Any()
    private var socket: WebSocket? = null
    private var taskId: String? = null
    private var epoch = 0
    private var started = false
    private val pending = ArrayDeque<ByteString>()
    private var pendingBytes = 0
    private var segmentId = 0L
    private var lastPartial = ""
    private val sentenceTimings = CloudSentenceTimings()
    private var consecutiveFailures = 0
    private var retryAt = 0L
    private var failed = false
    private var closed = false
    private val items = CloudSpeechItems()
    private val translations = LiveTranslateItems()
    private val finishedTranslations = linkedSetOf<Long>()

    override fun accept(samples: FloatArray, backlogSamples: Int) {
        if (samples.isEmpty()) return
        val frame = pcm16(samples)
        synchronized(lock) {
            if (failed || closed) return
            val active = socket ?: run {
                if (SystemClock.elapsedRealtime() < retryAt) return
                connectLocked()
            }
            if (started) {
                if (!sendAudio(active, frame)) failLocked(epoch, "audio send rejected", false)
            } else {
                // Before task-started the server rejects audio; keep only the most recent audio.
                pending.addLast(frame)
                pendingBytes += frame.size
                while (pendingBytes > MAX_PENDING_BYTES && pending.isNotEmpty()) {
                    pendingBytes -= pending.removeFirst().size
                }
            }
        }
    }

    override fun reset() {
        synchronized(lock) {
            endTaskLocked()
            segmentId++
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            endTaskLocked()
        }
    }

    private fun connectLocked(): WebSocket {
        val myEpoch = ++epoch
        val id = UUID.randomUUID().toString()
        taskId = id
        started = false
        val request = Request.Builder()
            .url(if (cloudModel.usesRealtimeProtocol)
                "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=${cloudModel.modelId}" else ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .build()
        val created = client.newWebSocket(request, Listener(myEpoch, id))
        socket = created
        Timber.i("[asr-cloud] connecting model=%s epoch=%d", cloudModel.modelId, myEpoch)
        return created
    }

    /** Finishes the task politely and forgets the socket; its later callbacks are stale. */
    private fun endTaskLocked() {
        epoch++
        val active = socket
        val id = taskId
        if (active != null) {
            if (started && id != null) runCatching {
                active.send(if (cloudModel.usesRealtimeProtocol) QwenRealtimeProtocol.finish() else finishTask(id))
            }
            runCatching { active.close(NORMAL_CLOSURE, null) }
        }
        socket = null
        taskId = null
        started = false
        pending.clear()
        pendingBytes = 0
        lastPartial = ""
        items.reset()
        translations.reset()
        finishedTranslations.clear()
        sentenceTimings.reset()
    }

    private fun failLocked(myEpoch: Int, reason: String, auth: Boolean, failure: CloudAsrFailure = if (auth) CloudAsrFailure.AUTH else CloudAsrFailure.CONNECTION) {
        if (myEpoch != epoch || closed) return
        epoch++ // Invalidate all later callbacks before cancelling the old socket.
        socket?.cancel()
        socket = null
        started = false
        pending.clear()
        pendingBytes = 0
        lastPartial = ""
        items.reset()
        translations.reset()
        finishedTranslations.clear()
        sentenceTimings.reset()
        segmentId++
        consecutiveFailures++
        Timber.w("[asr-cloud] task failed reason=%s auth=%s failures=%d", reason, auth, consecutiveFailures)
        when {
            !failure.retryable || consecutiveFailures >= MAX_CONSECUTIVE_FAILURES -> giveUpLocked(failure)
            else -> retryAt = SystemClock.elapsedRealtime() + RETRY_BASE_MS * consecutiveFailures
        }
    }

    private fun giveUpLocked(failure: CloudAsrFailure) {
        failed = true
        epoch++
        onFailure(failure)
    }

    private inner class Listener(private val myEpoch: Int, private val id: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (myEpoch != epoch) return
                val message = when {
                    cloudModel.translatesSpeech -> LiveTranslateProtocol.session(cloudModel, language, targetLanguage)
                    cloudModel.usesRealtimeProtocol -> QwenRealtimeProtocol.session(language)
                    else -> runTask(id, language, cloudModel)
                }
                if (!webSocket.send(message)) failLocked(myEpoch, "configuration send rejected", false)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = if (cloudModel.usesRealtimeProtocol) QwenRealtimeProtocol.parse(text, includeTranscription = !cloudModel.translatesSpeech)
                else ParaformerEvent.parse(text)
            synchronized(lock) {
                if (myEpoch != epoch) return
                if (cloudModel.translatesSpeech) {
                    translations.accept(text).forEach { result ->
                        consecutiveFailures = 0
                        val now = SystemClock.elapsedRealtime()
                        if (result.isFinal && finishedTranslations.add(result.segmentId)) {
                            val windowMs = sentenceTimings.finish(result.segmentId, now)
                            metrics?.onCloudSentence(windowMs)
                            Timber.i("[asr-s2tt] final segment=%d sourceChars=%d translationChars=%d failed=%s partialToFinalMs=%d",
                                result.segmentId, result.text.length, result.providerTranslation.orEmpty().length,
                                result.providerTranslationFailed, windowMs)
                            while (finishedTranslations.size > 256) finishedTranslations.remove(finishedTranslations.first())
                        } else if (!result.isFinal && result.segmentId !in finishedTranslations) {
                            sentenceTimings.start(result.segmentId, now)
                        }
                        sink(result.copy(providerTranslation = ChineseScriptNormalizer.normalizeForTarget(result.providerTranslation.orEmpty(), targetLanguage)))
                    }
                }
                if (event == null) return
                when (event) {
                    ParaformerEvent.Started -> {
                        started = true
                        while (pending.isNotEmpty()) {
                            if (!sendAudio(webSocket, pending.removeFirst())) {
                                failLocked(myEpoch, "buffer send rejected", false)
                                return
                            }
                        }
                        pendingBytes = 0
                        onReady()
                        Timber.i("[asr-cloud] task started epoch=%d", myEpoch)
                    }
                    is ParaformerEvent.Result -> onResult(event)
                    ParaformerEvent.Finished -> failLocked(myEpoch, "server finished", false)
                    is ParaformerEvent.Failed -> {
                        val detail = CloudSpeechErrorDiagnostic.describe(event.code, event.message, apiKey)
                        Timber.w("[asr-cloud] server error model=%s detail=%s", cloudModel.modelId,
                            detail)
                        failLocked(myEpoch, event.code, auth = false, failure = CloudSpeechFailurePolicy.aliyun(event.code))
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            synchronized(lock) {
                failLocked(myEpoch, code?.toString() ?: t.javaClass.simpleName, auth = code == 401 || code == 403, failure = CloudSpeechFailurePolicy.http(code))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            onClosed(webSocket, code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (myEpoch != epoch) return
                failLocked(myEpoch, "server closed $code", false)
            }
        }
    }

    private fun onResult(result: ParaformerEvent.Result) {
        if (result.heartbeat) return
        val text = SpeechTextPolicy.clean(result.text)
        consecutiveFailures = 0 // A real result, rather than a handshake, proves recovery.
        val now = SystemClock.elapsedRealtime()
        if (result.itemId != null) {
            items.accept(result.itemId, text, result.sentenceEnd)?.let { event ->
                if (event.isFinal) metrics?.onCloudSentence(sentenceTimings.finish(event.segmentId, now))
                else sentenceTimings.start(event.segmentId, now)
                sink(event)
            }
            return
        }
        if (result.sentenceEnd) {
            val windowMs = sentenceTimings.finish(segmentId, now)
            if (text.isNotEmpty()) {
                sink(SpeechEvent(segmentId, text, isFinal = true))
                metrics?.onCloudSentence(windowMs)
            }
            segmentId++
            lastPartial = ""
        } else if (text.isNotEmpty() && text != lastPartial) {
            sentenceTimings.start(segmentId, now)
            lastPartial = text
            sink(SpeechEvent(segmentId, text, isFinal = false))
        }
    }

    private fun sendAudio(active: WebSocket, frame: ByteString): Boolean =
        if (cloudModel.usesRealtimeProtocol) active.send(QwenRealtimeProtocol.audio(frame)) else active.send(frame)

    companion object {
        /** Legacy domain; still documented as working for China (Beijing) keys. */
        const val ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        const val MODEL = "paraformer-realtime-v2"
        private const val NORMAL_CLOSURE = 1000
        private const val PING_SECONDS = 20L
        /** About two seconds of 16 kHz PCM16 while the task is starting. */
        private const val MAX_PENDING_BYTES = PlaybackAudioCapture.TARGET_RATE * 2 * 2
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val RETRY_BASE_MS = 1_000L

        internal fun runTask(taskId: String, language: AsrLanguage, model: AsrCloudModel = AsrCloudModel.PARAFORMER): String = buildJsonObject {
            require(!model.usesRealtimeProtocol)
            putJsonObject("header") {
                put("action", "run-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            }
            putJsonObject("payload") {
                put("task_group", "audio")
                put("task", "asr")
                put("function", "recognition")
                put("model", model.modelId)
                putJsonObject("input") {}
                putJsonObject("parameters") {
                    put("format", "pcm")
                    put("sample_rate", PlaybackAudioCapture.TARGET_RATE)
                    // Audio is streamed continuously, so silence must not close the connection.
                    put("heartbeat", true)
                    if (model == AsrCloudModel.PARAFORMER) {
                        // Keep the official 1300 ms default: 400 ms splits ordinary mid-sentence pauses.
                        put("semantic_punctuation_enabled", false)
                        put("max_sentence_silence", 1300)
                    }
                    if (language != AsrLanguage.AUTO) putJsonArray("language_hints") { add(language.code) }
                }
            }
        }.toString()

        internal fun finishTask(taskId: String): String = buildJsonObject {
            putJsonObject("header") {
                put("action", "finish-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            }
            putJsonObject("payload") { putJsonObject("input") {} }
        }.toString()

        internal fun pcm16(samples: FloatArray): ByteString {
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val value = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                bytes[2 * i] = (value and 0xff).toByte()
                bytes[2 * i + 1] = (value shr 8 and 0xff).toByte()
            }
            return bytes.toByteString()
        }
    }
}

/** Server events the engine acts on; anything else is ignored. */
internal sealed interface ParaformerEvent {
    data object Started : ParaformerEvent
    data object Finished : ParaformerEvent
    data class Failed(val code: String, val message: String) : ParaformerEvent
    data class Result(val text: String, val sentenceEnd: Boolean, val heartbeat: Boolean, val itemId: String? = null) : ParaformerEvent

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ParaformerEvent? = runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val header = root["header"]?.jsonObject ?: return null
            when (header.string("event")) {
                "task-started" -> Started
                "task-finished" -> Finished
                "task-failed" -> Failed(header.string("error_code").orEmpty(), header.string("error_message").orEmpty())
                "result-generated" -> {
                    val sentence = root["payload"]?.jsonObject?.get("output")?.jsonObject
                        ?.get("sentence")?.jsonObject ?: return null
                    Result(
                        text = sentence.string("text").orEmpty(),
                        sentenceEnd = sentence["sentence_end"]?.jsonPrimitive?.booleanOrNull == true,
                        heartbeat = sentence["heartbeat"]?.jsonPrimitive?.booleanOrNull == true,
                    )
                }
                else -> null
            }
        }.onFailure { Timber.w("[asr-cloud] unreadable server event") }.getOrNull()

        private fun JsonObject.string(key: String): String? =
            (get(key) as? JsonPrimitive)?.contentOrNull
    }
}
