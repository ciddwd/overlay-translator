package com.gameocr.app.asr

import android.content.res.AssetManager
import android.os.SystemClock
import com.gameocr.app.data.AsrCloudConnections
import com.gameocr.app.data.AsrLanguage
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import timber.log.Timber

/** VAD produces complete audio files; bounded FIFO work preserves the ordering of subtitles. */
internal class HttpTranscriptionEngine(
    client: OkHttpClient,
    assets: AssetManager,
    private val config: AsrCloudConnections,
    private val language: AsrLanguage,
    private val sink: (SpeechEvent) -> Unit,
    private val onFailure: (CloudAsrFailure) -> Unit,
    private val metrics: SpeechMetrics?,
) : SpeechEngine {
    private data class Segment(val epoch: Long, val id: Long, val pcm: ByteArray)
    private val client = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
    private val vad = Vad(assetManager = assets, config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = AsrVadModel.ASSET_PATH,
            minSilenceDuration = 1.3f, minSpeechDuration = 0.25f, maxSpeechDuration = 12f),
        sampleRate = 16000, numThreads = 1, provider = "cpu",
    ))
    private val lock = Any()
    private var epoch = 0L
    private var nextId = 0L
    private var call: Call? = null
    private var closed = false
    private var failed = false
    private val queue = Channel<Segment>(4)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { scope.launch { for (segment in queue) transcribe(segment) } }

    override fun accept(samples: FloatArray, backlogSamples: Int) {
        synchronized(lock) { if (closed || failed) return }
        vad.acceptWaveform(samples)
        while (!vad.empty()) {
            val segment = vad.front()
            val pcm = ParaformerRealtimeEngine.pcm16(segment.samples).toByteArray()
            vad.pop()
            synchronized(lock) {
                if (!queue.trySend(Segment(epoch, nextId++, pcm)).isSuccess) {
                    // Stop visibly instead of silently dropping or overwriting older utterances.
                    failLocked(CloudAsrFailure.CONNECTION)
                    return
                }
            }
        }
    }

    private fun transcribe(segment: Segment) {
        val request = runCatching { OpenAiAsrProtocol.request(config, language, segment.pcm) }.getOrElse {
                synchronized(lock) {
                    if (!closed && !failed && segment.epoch == epoch) failLocked(CloudAsrFailure.CONNECTION)
                }
                return
            }
        val active = client.newCall(request)
        synchronized(lock) {
            if (closed || failed || segment.epoch != epoch) return
            call = active
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            active.execute().use { response ->
                synchronized(lock) {
                    if (closed || failed || segment.epoch != epoch) return
                    if (!response.isSuccessful) {
                        Timber.w("[asr-http] response code=%d", response.code)
                        failLocked(CloudSpeechFailurePolicy.http(response.code))
                        return
                    }
                }
                val text = OpenAiAsrProtocol.transcript(response.body?.string().orEmpty())
                synchronized(lock) {
                    if (closed || failed || segment.epoch != epoch) return
                    metrics?.onCloudSentence(SystemClock.elapsedRealtime() - startedAt)
                    if (text.isNotEmpty()) sink(SpeechEvent(segment.id, text, true))
                }
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (!closed && !failed && segment.epoch == epoch) {
                    Timber.w("[asr-http] request failed type=%s", error.javaClass.simpleName)
                    failLocked(CloudAsrFailure.CONNECTION)
                }
            }
        } finally { synchronized(lock) { if (call === active) call = null } }
    }

    private fun failLocked(reason: CloudAsrFailure) {
        if (closed || failed) return
        failed = true
        epoch++
        call?.cancel()
        while (queue.tryReceive().isSuccess) { /* Drop abandoned work on a failure boundary. */ }
        onFailure(reason)
    }

    override fun reset() {
        synchronized(lock) {
            epoch++
            call?.cancel(); call = null
            while (queue.tryReceive().isSuccess) { }
        }
        vad.reset()
    }

    override fun close() {
        synchronized(lock) { closed = true }
        reset()
        queue.close(); scope.cancel(); vad.release()
    }
}
