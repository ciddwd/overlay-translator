package com.gameocr.app.asr

import android.os.SystemClock
import com.gameocr.app.data.AsrCloudConnections
import com.gameocr.app.data.AsrCloudProvider
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import timber.log.Timber

/** Paced 200 ms PCM upload shared by the Tencent and Volcengine native protocols. */
internal class NativeCloudSpeechEngine(
    client: OkHttpClient,
    private val provider: AsrCloudProvider,
    private val config: AsrCloudConnections,
    private val sink: (SpeechEvent) -> Unit,
    private val onFailure: (CloudAsrFailure) -> Unit,
    private val metrics: SpeechMetrics?,
    private val onReady: () -> Unit = {},
    private val tencentSecretId: String = "",
    private val tencentSecretKey: String = "",
    private val language: com.gameocr.app.data.AsrLanguage = com.gameocr.app.data.AsrLanguage.ZH,
    private val targetLanguage: String = "zh",
) : SpeechEngine {
    private val client = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).build()
    private val lock = Any()
    private val pending = ArrayDeque<ByteString>()
    private var pendingBytes = 0
    private var socket: WebSocket? = null
    private var ready = false
    private var readinessAnnounced = false
    private var closed = false
    private var failed = false
    private var epoch = 0L
    private var connectedAt = 0L
    private var retryAt = 0L
    private var failures = 0
    private var sequence = 1
    private var uploadedFrames = 0L
    private var nonSilentFrames = 0L
    private var receivedPackets = 0L
    private var lastUploadAt = 0L
    private val items = NativeSpeechItems()
    private val translationItems = TencentSpeechTranslationItems()
    private val timings = CloudSentenceTimings()
    private val writer = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "asr-upload").apply { isDaemon = true }
    }

    init {
        require(provider == AsrCloudProvider.TENCENT || provider == AsrCloudProvider.VOLCENGINE)
        writer.scheduleAtFixedRate({ synchronized(lock) {
            if (closed || failed) return@synchronized
            try { pumpLocked() } catch (error: Exception) {
                Timber.w("[asr-native] upload exception provider=%s type=%s", provider, error.javaClass.simpleName)
                failLocked(epoch, null, auth = false)
            }
        } }, 200, 200, TimeUnit.MILLISECONDS)
    }

    override fun accept(samples: FloatArray, backlogSamples: Int) = synchronized(lock) {
        if (closed || failed || samples.isEmpty()) return@synchronized
        val pcm = ParaformerRealtimeEngine.pcm16(samples)
        if (pendingBytes + pcm.size > MAX_PENDING_BYTES) {
            // A saturated upload must stop, rather than splice audio after silently dropping it.
            giveUpLocked(CloudAsrFailure.CONNECTION)
            return@synchronized
        }
        pending += pcm
        pendingBytes += pcm.size
    }

    private fun pumpLocked() {
        if (pending.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val active = socket ?: run {
            if (now < retryAt) return
            connectLocked()
            return
        }
        if (!ready) {
            if (now - connectedAt > 15_000) failLocked(epoch, null, auth = false)
            return
        }
        if (pendingBytes < FRAME_BYTES) return
        val pcm = ByteArrayOutputStream(FRAME_BYTES)
        while (pcm.size() < FRAME_BYTES) {
            val first = pending.removeFirst()
            val take = minOf(first.size, FRAME_BYTES - pcm.size())
            pcm.write(first.substring(0, take).toByteArray())
            if (take < first.size) pending.addFirst(first.substring(take))
            pendingBytes -= take
        }
        val frame = pcm.toByteArray().toByteString()
        val sent = active.send(if (provider == AsrCloudProvider.VOLCENGINE) VolcSpeechProtocol.audio(frame, sequence = sequence++) else frame)
        if (!sent) failLocked(epoch, null, auth = false)
        else {
            uploadedFrames++
            if (frame.toByteArray().any { it.toInt() != 0 }) nonSilentFrames++
            lastUploadAt = now
            if (uploadedFrames == 1L || uploadedFrames % 25 == 0L) {
                Timber.i("[asr-native] upload provider=%s frames=%d nonSilent=%d received=%d pendingBytes=%d socketQueueBytes=%d",
                    provider, uploadedFrames, nonSilentFrames, receivedPackets, pendingBytes, active.queueSize())
            }
        }
    }

    private fun connectLocked() {
        val ownEpoch = ++epoch
        val id = UUID.randomUUID().toString()
        val request = if (provider == AsrCloudProvider.TENCENT) {
            val now = System.currentTimeMillis() / 1000
            val nonce = Random.nextInt(1, Int.MAX_VALUE)
            if (config.tencentModel.translatesSpeech) TencentSpeechTranslationProtocol.request(config, id, now, nonce,
                tencentSecretId, tencentSecretKey, language, targetLanguage)
            else TencentSpeechProtocol.request(config, id, now, nonce, tencentSecretId, tencentSecretKey, language)
        } else VolcSpeechProtocol.request(config.volcApiKey, id, config.volcResource.resourceId)
        connectedAt = SystemClock.elapsedRealtime()
        readinessAnnounced = false
        ready = false
        sequence = 1
        uploadedFrames = 0; nonSilentFrames = 0; receivedPackets = 0; lastUploadAt = 0
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
                if (ownEpoch != epoch || closed) return@synchronized
                if (provider == AsrCloudProvider.VOLCENGINE) {
                    if (!webSocket.send(VolcSpeechProtocol.configuration(sequence++))) failLocked(ownEpoch, null, false)
                    else ready = true
                }
                Timber.i("[asr-native] connected provider=%s epoch=%d", provider, ownEpoch)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
                if (ownEpoch != epoch || closed) return@synchronized
                if (provider != AsrCloudProvider.TENCENT) return@synchronized
                runCatching { if (config.tencentModel.translatesSpeech) TencentSpeechTranslationProtocol.parse(text) else TencentSpeechProtocol.parse(text) }
                    .onSuccess { consumeLocked(ownEpoch, it) }
                    .onFailure { error ->
                        Timber.w("[asr-native] response parse failed provider=%s type=%s", provider, error.javaClass.simpleName)
                        failLocked(ownEpoch, null, false, CloudAsrFailure.UNKNOWN)
                    }
                Unit
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(lock) {
                if (ownEpoch != epoch || closed) return@synchronized
                if (provider != AsrCloudProvider.VOLCENGINE) return@synchronized
                runCatching { VolcSpeechProtocol.parse(bytes) }
                    .onSuccess { consumeLocked(ownEpoch, it) }
                    .onFailure { error ->
                        Timber.w("[asr-native] response parse failed provider=%s type=%s", provider, error.javaClass.simpleName)
                        failLocked(ownEpoch, null, false, CloudAsrFailure.UNKNOWN)
                    }
                Unit
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
                failLocked(ownEpoch, response?.code, response?.code in listOf(401, 403), CloudSpeechFailurePolicy.http(response?.code))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
                failLocked(ownEpoch, code, false)
            }
        })
    }

    private fun consumeLocked(ownEpoch: Long, packet: NativeSpeechPacket) {
        receivedPackets++
        packet.error?.let {
            val detail = CloudSpeechErrorDiagnostic.describe(it.toString(), packet.errorMessage.orEmpty(), config.volcApiKey,
                tencentSecretId, tencentSecretKey)
            Timber.w("[asr-native] server error provider=%s detail=%s", provider, detail)
            failLocked(ownEpoch, it, false, CloudSpeechFailurePolicy.native(provider, it, provider == AsrCloudProvider.TENCENT && config.tencentModel.translatesSpeech)); return
        }
        ready = true
        if (!packet.finished && !readinessAnnounced) {
            readinessAnnounced = true
            onReady()
        }
        packet.results.forEach { result -> items.accept(result)?.let { event ->
            failures = 0
            val now = SystemClock.elapsedRealtime()
            if (event.isFinal) metrics?.onCloudSentence(timings.finish(event.segmentId, now))
            else timings.start(event.segmentId, now)
            sink(event)
        } }
        packet.translations.forEach { result -> translationItems.accept(result)?.let { event ->
            failures = 0
            val now = SystemClock.elapsedRealtime()
            if (event.isFinal) metrics?.onCloudSentence(timings.finish(event.segmentId, now))
            else timings.start(event.segmentId, now)
            sink(event.copy(providerTranslation = com.gameocr.app.translate.ChineseScriptNormalizer.normalizeForTarget(
                event.providerTranslation.orEmpty(), targetLanguage)))
        } }
        if (packet.finished) failLocked(ownEpoch, null, false)
    }

    private fun failLocked(ownEpoch: Long, code: Int?, auth: Boolean, failure: CloudAsrFailure = if (auth) CloudAsrFailure.AUTH else CloudAsrFailure.CONNECTION) {
        if (closed || failed || ownEpoch != epoch) return
        failures++
        Timber.w("[asr-native] failed provider=%s code=%s category=%s attempts=%d uploaded=%d nonSilent=%d received=%d lastUploadAgoMs=%d socketQueueBytes=%d",
            provider, code, failure, failures, uploadedFrames, nonSilentFrames, receivedPackets,
            if (lastUploadAt == 0L) -1L else SystemClock.elapsedRealtime() - lastUploadAt, socket?.queueSize() ?: 0L)
        endLocked(polite = false)
        if (!failure.retryable || failures >= 3) giveUpLocked(failure)
        else retryAt = SystemClock.elapsedRealtime() + failures * 1000L
    }

    private fun giveUpLocked(reason: CloudAsrFailure) {
        if (failed || closed) return
        failed = true
        endLocked(polite = false)
        onFailure(reason)
    }

    private fun endLocked(polite: Boolean) {
        epoch++
        socket?.let { active ->
            if (polite && ready) {
                if (provider == AsrCloudProvider.TENCENT) active.send("{\"type\":\"end\"}")
                else active.send(VolcSpeechProtocol.audio(ByteString.EMPTY, last = true, sequence = sequence++))
                active.close(1000, null)
            } else active.cancel()
        }
        socket = null; ready = false
        pending.clear(); pendingBytes = 0
        items.reset(); translationItems.reset(); timings.reset()
    }

    override fun reset() = synchronized(lock) { endLocked(polite = true) }
    override fun close() {
        synchronized(lock) { closed = true; endLocked(polite = true) }
        writer.shutdownNow()
    }

    private companion object {
        const val FRAME_BYTES = 16000 * 2 / 5
        const val MAX_PENDING_BYTES = 16000 * 2 * 4
    }
}
