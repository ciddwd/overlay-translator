package com.gameocr.app.asr

import android.content.res.AssetManager
import android.os.SystemClock
import com.gameocr.app.data.AsrCloudConnections
import com.gameocr.app.data.AsrLanguage
import com.gameocr.app.data.AsrSegmentDurations
import com.gameocr.app.data.AsrSegmentationSettings
import com.k2fsa.sherpa.onnx.Vad
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import timber.log.Timber

internal interface HttpAsrSegmenter : AutoCloseable {
    fun accept(samples: FloatArray): List<ByteArray>
    fun reset()
}

internal object HttpAsrPolicy {
    const val CONCURRENT_REQUESTS = 2
    const val MAX_PENDING_SEGMENTS = 6

    fun vadConfig(durations: AsrSegmentDurations = AsrSegmentationSettings().http) =
        AsrVadPolicy.config(durations)
}

private class HttpSileroSegmenter(assets: AssetManager, durations: AsrSegmentDurations) : HttpAsrSegmenter {
    private val vad = Vad(assetManager = assets, config = HttpAsrPolicy.vadConfig(durations))
    override fun accept(samples: FloatArray): List<ByteArray> {
        vad.acceptWaveform(samples)
        return buildList {
            while (!vad.empty()) {
                add(ParaformerRealtimeEngine.pcm16(vad.front().samples).toByteArray())
                vad.pop()
            }
        }
    }
    override fun reset() = vad.reset()
    override fun close() = vad.release()
}

/** Short VAD files overlap their network requests; results publish strictly in audio order. */
internal class HttpTranscriptionEngine(
    client: OkHttpClient,
    assets: AssetManager?,
    private val config: AsrCloudConnections,
    private val language: AsrLanguage,
    private val sink: (SpeechEvent) -> Unit,
    private val onFailure: (CloudAsrFailure) -> Unit,
    private val metrics: SpeechMetrics?,
    segmenter: HttpAsrSegmenter? = null,
    callFactory: Call.Factory? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    durations: AsrSegmentDurations = AsrSegmentationSettings().http,
    private val hintSource: AsrHintSource = AsrHintSource(),
) : SpeechEngine {
    private data class Segment(val epoch: Long, val id: Long, val pcm: ByteArray)
    private val client = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
    private val segmenter = segmenter ?: HttpSileroSegmenter(checkNotNull(assets), durations)
    private val callFactory = callFactory ?: this.client
    private val lock = Any()
    private var epoch = 0L
    private var nextId = 0L
    private var nextResultId = 0L
    private val calls = mutableSetOf<Call>()
    private val results = mutableMapOf<Long, String>()
    private var closed = false
    private var failed = false
    private val queue = Channel<Segment>(HttpAsrPolicy.MAX_PENDING_SEGMENTS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        repeat(HttpAsrPolicy.CONCURRENT_REQUESTS) {
            scope.launch { for (segment in queue) transcribe(segment) }
        }
    }

    override fun accept(samples: FloatArray, backlogSamples: Int) {
        synchronized(lock) {
            if (closed || failed || samples.isEmpty()) return
            for (pcm in segmenter.accept(samples)) {
                // Count completed-but-waiting results too, so a slow first request cannot
                // turn the reorder buffer into an unbounded backlog of stale subtitles.
                if (nextId - nextResultId >= HttpAsrPolicy.MAX_PENDING_SEGMENTS ||
                    !queue.trySend(Segment(epoch, nextId++, pcm)).isSuccess) {
                    // Stop visibly instead of silently dropping or overwriting older utterances.
                    failLocked(CloudAsrFailure.CONNECTION)
                    return
                }
            }
        }
    }

    private fun transcribe(segment: Segment) {
        synchronized(lock) { if (closed || failed || segment.epoch != epoch) return }
        val active = runCatching {
            val request = synchronized(lock) {
                if (closed || failed || segment.epoch != epoch) return
                OpenAiAsrProtocol.request(config, language, segment.pcm, hintSource.current)
            }
            callFactory.newCall(request)
        }.getOrElse {
            synchronized(lock) {
                if (!closed && !failed && segment.epoch == epoch) failLocked(CloudAsrFailure.CONNECTION)
            }
            return
        }
        synchronized(lock) {
            if (closed || failed || segment.epoch != epoch) { active.cancel(); return }
            calls.add(active)
        }
        val startedAt = clock()
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
                    metrics?.onCloudSentence(clock() - startedAt)
                    results[segment.id] = text
                    while (results.containsKey(nextResultId)) {
                        val id = nextResultId++
                        val ordered = results.remove(id)!!
                        if (ordered.isNotEmpty()) sink(SpeechEvent(id, ordered, true))
                    }
                }
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (!closed && !failed && segment.epoch == epoch) {
                    Timber.w("[asr-http] request failed type=%s", error.javaClass.simpleName)
                    failLocked(CloudAsrFailure.CONNECTION)
                }
            }
        } finally { synchronized(lock) { calls.remove(active) } }
    }

    private fun failLocked(reason: CloudAsrFailure) {
        if (closed || failed) return
        failed = true
        resetLocked()
        onFailure(reason)
    }

    private fun resetLocked() {
        epoch++
        calls.forEach { it.cancel() }
        calls.clear()
        while (queue.tryReceive().isSuccess) { }
        results.clear()
        nextResultId = nextId
        segmenter.reset()
    }

    override fun reset() = synchronized(lock) {
        if (!closed) resetLocked()
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            resetLocked()
            queue.close()
            scope.cancel()
            segmenter.close()
        }
    }
}
