package com.gameocr.app.asr

import android.content.res.AssetManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.gameocr.app.R
import com.gameocr.app.data.AsrLanguagePolicy
import com.gameocr.app.data.AsrCloudProvider
import com.gameocr.app.data.Settings
import com.gameocr.app.data.LogRepository
import com.gameocr.app.overlay.SpeechBallState
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Owns one speech session: engine, playback capture and two worker threads. The ball mirrors
 * [state]; it never keeps its own copy. Commands are serialized, and every stop, pause or resume
 * increments [generation]; each result carries the generation of the capture run that produced
 * it, so consumers reject results from audio before a pause.
 *
 * Capture and recognition are decoupled: the capture thread only reads, counts and enqueues into
 * a bounded [AudioChunkQueue]; the inference thread creates, uses and releases the engine.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class SpeechSessionController(
    private val scope: CoroutineScope,
    private val assets: AssetManager,
    private val installer: AsrModelInstaller,
    private val httpClient: OkHttpClient,
    private val settings: suspend () -> Settings,
    private val isOwnAudioPlaying: () -> Boolean,
    private val listener: Listener,
    private val logRepository: LogRepository,
    private val userMessage: (Int) -> String,
) {
    interface Listener {
        /** Called on any thread; implementations hop to the main thread. */
        fun onState(state: SpeechBallState)
        fun onEvent(generation: Long, event: SpeechEvent)
        /** A pause, resume, start or stop boundary: work from before it must be dropped. */
        fun onGenerationChanged()
        fun onNotice(@StringRes message: Int)
    }

    private val inferenceThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "asr-infer").apply { priority = Thread.NORM_PRIORITY + 1 }
    }.asCoroutineDispatcher()
    private val captureThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "asr-capture").apply { priority = Thread.MAX_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    private val commands = Mutex()
    private val readiness = SpeechReadiness()
    private var needsRemoteReady = false

    @Volatile var state: SpeechBallState = SpeechBallState.IDLE
        private set
    @Volatile var generation: Long = 0L
        private set
    /** Metrics of the current session, shared with the subtitle coordinator. */
    @Volatile var metrics: SpeechMetrics? = null
        private set
    /** Generation stamped on engine results; set when a capture run starts. */
    @Volatile private var emitGeneration = -1L

    private var engine: SpeechEngine? = null
    private var engineKey: String? = null
    private var engineModel: AsrModelId? = null
    /**
     * Bumped for every engine created. Async cloud failures name the instance that failed, so a
     * late error from a replaced engine can never stop a newer session, while pause and resume
     * (which change the generation but keep the engine) still accept them.
     */
    private var engineInstance = 0L
    private var capture: PlaybackAudioCapture? = null
    private var queue: AudioChunkQueue? = null
    private var captureJob: Job? = null
    private var inferenceJob: Job? = null
    @Volatile private var loopStopping = false

    private val modelReleaser: suspend (AsrModelId) -> Unit = { model -> releaseModel(model) }

    init {
        installer.registerModelReleaser(modelReleaser)
    }

    fun start(projection: MediaProjection) = command("start") {
        if (state != SpeechBallState.IDLE) return@command
        setState(SpeechBallState.PREPARING)
        nextGeneration()
        val current = settings()
        val language = AsrLanguagePolicy.effective(current.asrEngine, current.asrLanguage, current.asrCloudModel, current.asrCloudProvider, current.asrCloudConnections)
        val model = AsrModelId.forSettings(current.asrEngine, current.asrWhisperVariant)
        needsRemoteReady = model == null && current.asrCloudProvider != AsrCloudProvider.OPENAI_COMPATIBLE
        readiness.begin(generation, needsRemoteReady)
        SpeechStartPolicy.blockReason(current, model?.let { installer.checkInstalled(it) != null })?.let { block ->
            failToIdle(block.message)
            return@command
        }
        val sessionMetrics = SpeechMetrics().also { metrics = it }
        val sink: (SpeechEvent) -> Unit = { event -> listener.onEvent(emitGeneration, event) }
        val loaded = runCatching {
            withContext(inferenceThread) {
                val key = SpeechStartPolicy.engineKey(current)
                if (engineKey != key || model == null) {
                    // Cloud engines are cheap and hold a key; always rebuild them.
                    releaseEngineOnInferenceThread()
                    val startedAt = SystemClock.elapsedRealtime()
                    engine = if (model == null) {
                        val instance = engineInstance + 1
                        val failed: (CloudAsrFailure) -> Unit = { failure -> scope.launch { failFromCloud(instance, failure) } }
                        val ready: () -> Unit = {
                            val resultGeneration = emitGeneration
                            scope.launch { commands.withLock {
                                if (instance == engineInstance) announceReady(readiness.engineConfirmed(resultGeneration))
                            } }
                        }
                        when (current.asrCloudProvider) {
                            AsrCloudProvider.OPENAI_COMPATIBLE -> HttpTranscriptionEngine(httpClient, assets,
                                current.asrCloudConnections, language, sink, failed, sessionMetrics)
                            AsrCloudProvider.TENCENT, AsrCloudProvider.VOLCENGINE -> NativeCloudSpeechEngine(httpClient,
                                current.asrCloudProvider, current.asrCloudConnections, sink, failed, sessionMetrics,
                                onReady = ready, tencentSecretId = current.tencentSecretId, tencentSecretKey = current.tencentSecretKey,
                                language = language, targetLanguage = current.targetLang)
                            AsrCloudProvider.ALIYUN -> ParaformerRealtimeEngine(
                                client = httpClient,
                                apiKey = current.asrDashScopeApiKey.trim(),
                                language = language,
                                cloudModel = current.asrCloudModel,
                                targetLanguage = current.targetLang,
                                sink = sink,
                                onFailure = failed,
                                metrics = sessionMetrics,
                                onReady = ready,
                            )
                        }
                    } else {
                        val dir = installer.checkInstalled(model) ?: error("model disappeared")
                        SpeechEngineFactory.create(model, dir, language, assets, sink, sessionMetrics)
                    }
                    engineKey = key
                    engineModel = model
                    engineInstance++
                    Timber.i("[asr] engine ready model=%s ms=%d", model?.name ?: current.asrEngine.name, SystemClock.elapsedRealtime() - startedAt)
                }
            }
        }
        if (loaded.isFailure) {
            Timber.e(loaded.exceptionOrNull(), "[asr] engine load failed model=%s", model?.name)
            releaseEngine()
            failToIdle(R.string.asr_error_model_load_failed)
            return@command
        }
        val created = PlaybackAudioCapture.create(projection, Process.myUid())
        if (created == null) {
            Timber.w("[asr] playback capture could not be initialized")
            releaseEngine()
            failToIdle(R.string.asr_error_capture_stopped)
            return@command
        }
        capture = created
        startLoop(created)
    }

    /**
     * Pause keeps the engine loaded but drops queued audio and the open utterance. The video keeps
     * playing, so resume must start from live audio, never from anything captured before.
     */
    fun pause() = command("pause") {
        if (state != SpeechBallState.RECOGNIZING) return@command
        nextGeneration()
        stopLoop()
        withContext(inferenceThread) { engine?.reset() }
        logMetrics("pause")
        setState(SpeechBallState.PAUSED)
    }

    fun resume() = command("resume") {
        if (state != SpeechBallState.PAUSED) return@command
        val active = capture ?: return@command
        nextGeneration()
        readiness.begin(generation, needsRemoteReady)
        setState(SpeechBallState.PREPARING)
        listener.onNotice(R.string.asr_notice_preparing)
        withContext(inferenceThread) { engine?.reset() }
        startLoop(active)
    }

    fun stop() = command("stop") { stopNow() }

    /**
     * Releases everything, including the worker threads; the controller must not be used
     * afterwards. [scope] must be owned by this controller, because shutdown cancels it when done.
     */
    fun shutdown(): Job = scope.launch {
        installer.unregisterModelReleaser(modelReleaser)
        commands.withLock { stopNow() }
        inferenceThread.close()
        captureThread.close()
        scope.cancel()
    }

    /** Stops the session if it uses [model], so the files can be deleted or replaced. */
    private suspend fun releaseModel(model: AsrModelId) {
        command("release ${model.name}") {
            if (engineModel != model) return@command
            Timber.i("[asr] releasing %s for model management", model.name)
            stopNow()
        }.join()
    }

    private suspend fun stopNow() {
        if (state == SpeechBallState.IDLE && capture == null && engine == null) return
        nextGeneration()
        stopLoop()
        capture?.release()
        capture = null
        releaseEngine()
        logMetrics("stop")
        setState(SpeechBallState.IDLE)
    }

    private fun startLoop(active: PlaybackAudioCapture) {
        loopStopping = false
        val loopGeneration = generation
        val chunks = AudioChunkQueue(MAX_QUEUED_SAMPLES).also { queue = it }
        val sessionMetrics = metrics
        emitGeneration = loopGeneration
        captureJob = scope.launch(captureThread) { guarded("capture", loopGeneration) {
            // startRecording throws IllegalStateException when the recorder cannot start.
            active.start()
            scope.launch { commands.withLock { announceReady(readiness.captureStarted(loopGeneration)) } }
            var heardAudio = false
            var silentMs = 0
            var noticeShown = false
            var ownAudio = false
            var lastReadAt = SystemClock.elapsedRealtime()
            while (!loopStopping) {
                val samples = active.read()
                if (samples == null) {
                    if (!loopStopping) scope.launch { failFromLoop(loopGeneration) }
                    break
                }
                val now = SystemClock.elapsedRealtime()
                // The recorder buffer overwrites audio this thread did not read in time.
                val gap = now - lastReadAt - PlaybackAudioCapture.READ_MS * 2
                lastReadAt = now
                if (gap > CAPTURE_GAP_RESET_MS) chunks.markGap()
                if (samples.isEmpty()) continue
                if (isOwnAudioPlaying()) {
                    // Never transcribe this app's own read-aloud output.
                    if (!ownAudio) chunks.markGap()
                    ownAudio = true
                    continue
                }
                ownAudio = false
                if (rms(samples) > SILENCE_RMS) {
                    heardAudio = true
                    silentMs = 0
                } else {
                    silentMs += PlaybackAudioCapture.READ_MS
                }
                if (!heardAudio && !noticeShown && silentMs >= NO_AUDIO_NOTICE_MS) {
                    noticeShown = true
                    listener.onNotice(R.string.asr_notice_no_audio)
                }
                val dropped = chunks.offer(samples, now)
                sessionMetrics?.onCaptured(samples.size, dropped, gap)
                if (dropped > 0) Timber.w("[asr] recognition behind playback; dropped %d ms", dropped * 1000L / PlaybackAudioCapture.TARGET_RATE)
            }
        } }
        inferenceJob = scope.launch(inferenceThread) { guarded("inference", loopGeneration) {
            var lastLogAt = SystemClock.elapsedRealtime()
            while (!loopStopping) {
                val chunk = chunks.take(TAKE_TIMEOUT_MS) ?: continue
                val backlog = chunks.backlogSamples
                val now = SystemClock.elapsedRealtime()
                sessionMetrics?.onDequeued(now - chunk.capturedAtMs, backlog)
                try {
                    val active = engine ?: break
                    if (chunk.discontinuity) active.reset()
                    active.accept(chunk.samples, backlog)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.e(error, "[asr] recognition failed")
                    runCatching { engine?.reset() }
                }
                if (now - lastLogAt >= METRICS_LOG_INTERVAL_MS) {
                    lastLogAt = now
                    logMetrics("periodic")
                }
            }
        } }
    }

    /** A failed worker loop ends the run through the same cleanup as a capture failure. */
    private inline fun guarded(name: String, loopGeneration: Long, body: () -> Unit) =
        runWorkerLoop(onFailure = { error ->
            Timber.e(error, "[asr] %s loop failed", name)
            if (!loopStopping) scope.launch { failFromLoop(loopGeneration) }
        }, body = body)

    private suspend fun stopLoop() {
        loopStopping = true
        // Stopping the recorder unblocks the pending read; closing the queue wakes inference.
        capture?.stop()
        queue?.close()
        captureJob?.join()
        inferenceJob?.join()
        captureJob = null
        inferenceJob = null
        queue = null
    }

    private suspend fun failFromLoop(loopGeneration: Long) = commands.withLock {
        if (loopGeneration != generation) return@withLock
        Timber.w("[asr] capture stopped unexpectedly")
        stopNow()
        listener.onNotice(R.string.asr_error_capture_stopped)
    }

    private suspend fun failFromCloud(instance: Long, failure: CloudAsrFailure) = commands.withLock {
        if (!SpeechFailurePolicy.acceptsCloudFailure(state, engine != null, instance, engineInstance)) return@withLock
        val message = CloudSpeechFailurePolicy.message(failure)
        logRepository.error(LogRepository.Category.SPEECH, userMessage(message))
        stopNow()
        listener.onNotice(message)
    }

    private fun failToIdle(@StringRes message: Int) {
        logRepository.warn(LogRepository.Category.SPEECH, userMessage(message))
        setState(SpeechBallState.IDLE)
        listener.onNotice(message)
    }

    private suspend fun releaseEngine() = withContext(inferenceThread) { releaseEngineOnInferenceThread() }

    private fun releaseEngineOnInferenceThread() {
        runCatching { engine?.close() }.onFailure { Timber.w(it, "[asr] engine release failed") }
        engine = null
        engineKey = null
        engineModel = null
    }

    private fun logMetrics(reason: String) {
        val snapshot = metrics?.snapshot() ?: return
        if (snapshot.capturedSeconds <= 0f && snapshot.translations == 0L) return
        Timber.i("[asr-metrics] %s %s", reason, SpeechMetrics.format(snapshot))
    }

    private fun nextGeneration() {
        readiness.invalidate()
        generation++
        listener.onGenerationChanged()
    }

    private fun setState(next: SpeechBallState) {
        state = next
        listener.onState(next)
    }

    private fun announceReady(ready: Boolean) {
        if (!ready || state != SpeechBallState.PREPARING) return
        setState(SpeechBallState.RECOGNIZING)
        listener.onNotice(R.string.asr_notice_ready)
    }

    private fun command(name: String, block: suspend () -> Unit): Job =
        scope.launch {
            commands.withLock {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.e(error, "[asr] command failed: %s", name)
                    logRepository.error(LogRepository.Category.SPEECH, userMessage(R.string.asr_log_recognition_failed))
                    stopNow()
                }
            }
        }

    private companion object {
        const val SILENCE_RMS = 1e-4f
        const val NO_AUDIO_NOTICE_MS = 8_000
        /** Absorbs model or translation stalls; beyond it, live audio wins over late audio. */
        const val MAX_QUEUED_SAMPLES = PlaybackAudioCapture.TARGET_RATE * 8
        const val TAKE_TIMEOUT_MS = 200L
        const val CAPTURE_GAP_RESET_MS = 500L
        const val METRICS_LOG_INTERVAL_MS = 30_000L

        fun rms(samples: FloatArray): Float {
            var sum = 0.0
            for (sample in samples) sum += sample * sample
            return sqrt(sum / samples.size).toFloat()
        }
    }
}
