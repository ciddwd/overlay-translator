package com.gameocr.app.asr

import android.content.res.AssetManager
import com.gameocr.app.data.AsrLanguage
import com.gameocr.app.data.AsrSegmentDurations
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.Vad
import android.os.SystemClock
import java.io.File

/** One recognition result. Partial text for a segment is replaced; final text is translated. */
internal data class SpeechEvent(
    val segmentId: Long,
    val text: String,
    val isFinal: Boolean,
    /** Non-null for S2TT snapshots, including source-only updates with an empty translation. */
    val providerTranslation: String? = null,
    val providerTranslationFailed: Boolean = false,
)

/**
 * All calls happen on the session's single inference thread. Results go to the sink given at
 * creation: local engines call it from [accept], the cloud engine from its network thread.
 */
internal interface SpeechEngine : AutoCloseable {
    /**
     * [samples] are 16 kHz mono floats in -1..1. [backlogSamples] is the audio still queued behind
     * this chunk, so engines can skip optional work while recognition is behind playback.
     */
    fun accept(samples: FloatArray, backlogSamples: Int)

    /**
     * Drops buffered audio and stream state (pause, dropped audio, this app's own TTS). No result
     * for audio accepted before the reset is delivered afterwards.
     */
    fun reset()
}

internal object SpeechEngineFactory {
    /** ASR shares big cores with OCR and local LLM translation, so it starts with two threads. */
    private const val NUM_THREADS = 2

    fun create(
        model: AsrModelId,
        dir: File,
        language: AsrLanguage,
        assets: AssetManager,
        sink: (SpeechEvent) -> Unit,
        metrics: SpeechMetrics?,
        durations: AsrSegmentDurations,
    ): SpeechEngine {
        fun path(name: String) = File(dir, name).absolutePath
        val feature = FeatureConfig(sampleRate = PlaybackAudioCapture.TARGET_RATE, featureDim = 80)
        return when (model) {
            AsrModelId.SENSE_VOICE -> VadOfflineEngine(
                recognizer = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = feature,
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = path("model.int8.onnx"),
                                language = language.code,
                                // Punctuation and numbers noticeably improve translation input.
                                useInverseTextNormalization = true,
                            ),
                            tokens = path("tokens.txt"),
                            numThreads = NUM_THREADS,
                            provider = "cpu",
                        ),
                    ),
                ),
                vad = Vad(assetManager = assets, config = AsrVadPolicy.config(durations)),
                maxOpenSamples = AsrVadPolicy.openBufferSamples(durations),
                partials = PartialDecodePolicy(PARTIAL_INTERVAL_SAMPLES, PARTIAL_MAX_BACKLOG_SAMPLES),
                filterHallucination = false,
                sink = sink,
                metrics = metrics,
            )
            AsrModelId.WHISPER_TINY, AsrModelId.WHISPER_BASE -> {
                val prefix = if (model == AsrModelId.WHISPER_TINY) "tiny" else "base"
                VadOfflineEngine(
                    recognizer = OfflineRecognizer(
                        config = OfflineRecognizerConfig(
                            featConfig = feature,
                            modelConfig = OfflineModelConfig(
                                whisper = OfflineWhisperModelConfig(
                                    encoder = path("$prefix-encoder.int8.onnx"),
                                    decoder = path("$prefix-decoder.int8.onnx"),
                                    language = if (language == AsrLanguage.AUTO) "" else language.code,
                                    // Translation always goes through the app's translator.
                                    task = "transcribe",
                                ),
                                tokens = path("$prefix-tokens.txt"),
                                numThreads = NUM_THREADS,
                                provider = "cpu",
                            ),
                        ),
                    ),
                    vad = Vad(assetManager = assets, config = AsrVadPolicy.config(durations)),
                    maxOpenSamples = AsrVadPolicy.openBufferSamples(durations),
                    // Each Whisper pass costs a full 30 s window; decode only completed segments.
                    partials = null,
                    filterHallucination = true,
                    sink = sink,
                    metrics = metrics,
                )
            }
            AsrModelId.ZIPFORMER -> StreamingEngine(
                OnlineRecognizer(
                    config = OnlineRecognizerConfig(
                        featConfig = feature,
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = path("encoder-epoch-99-avg-1.int8.onnx"),
                                decoder = path("decoder-epoch-99-avg-1.onnx"),
                                joiner = path("joiner-epoch-99-avg-1.int8.onnx"),
                            ),
                            tokens = path("tokens.txt"),
                            numThreads = NUM_THREADS,
                            provider = "cpu",
                            modelType = "zipformer",
                        ),
                        endpointConfig = AsrVadPolicy.streamingConfig(durations),
                        enableEndpoint = true,
                        decodingMethod = "greedy_search",
                    ),
                ),
                sink = sink,
                metrics = metrics,
            )
        }
    }

    private const val PARTIAL_INTERVAL_SAMPLES = PlaybackAudioCapture.TARGET_RATE / 2
    /** Over half a second of queued audio means recognition is behind; finals come first. */
    private const val PARTIAL_MAX_BACKLOG_SAMPLES = PlaybackAudioCapture.TARGET_RATE / 2
}

/**
 * VAD-segmented offline recognition. With [partials] the open segment is re-decoded periodically
 * ("simulated streaming") so text appears while someone is speaking; see [PartialDecodePolicy]
 * for why those passes are throttled.
 */
internal class VadOfflineEngine(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad,
    private val partials: PartialDecodePolicy?,
    private val filterHallucination: Boolean,
    private val sink: (SpeechEvent) -> Unit,
    private val metrics: SpeechMetrics?,
    maxOpenSamples: Int,
) : SpeechEngine {
    private val open = SampleBuffer(maxSamples = maxOpenSamples)
    private val preRoll = SampleBuffer(maxSamples = PRE_ROLL_SAMPLES)
    private var segmentId = 0L
    private var samplesSincePartial = 0
    private var lastPartial = ""

    override fun accept(samples: FloatArray, backlogSamples: Int) {
        if (samples.isEmpty()) return
        vad.acceptWaveform(samples)
        val speaking = vad.isSpeechDetected()
        if (speaking) {
            if (open.size == 0) open.append(preRoll.toArray())
            open.append(samples)
            samplesSincePartial += samples.size
        } else {
            preRoll.append(samples)
        }
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val text = decode(segment.samples, final = true)
            if (text.isNotEmpty()) sink(SpeechEvent(segmentId, text, isFinal = true))
            segmentId++
            open.clear()
            samplesSincePartial = 0
            lastPartial = ""
            partials?.reset()
        }
        val policy = partials ?: return
        if (!speaking || samplesSincePartial < policy.intervalSamples) return
        if (!policy.shouldDecode(samplesSincePartial, backlogSamples)) {
            metrics?.onPartialSkipped()
            return
        }
        samplesSincePartial = 0
        val text = decode(open.toArray(), final = false)
        if (text.isNotEmpty() && text != lastPartial) {
            lastPartial = text
            sink(SpeechEvent(segmentId, text, isFinal = false))
        }
    }

    private fun decode(samples: FloatArray, final: Boolean): String {
        if (samples.isEmpty()) return ""
        val startedAt = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, PlaybackAudioCapture.TARGET_RATE)
            recognizer.decode(stream)
            val text = SpeechTextPolicy.clean(recognizer.getResult(stream).text)
            val seconds = samples.size.toFloat() / PlaybackAudioCapture.TARGET_RATE
            if (filterHallucination && SpeechTextPolicy.isLikelyHallucination(text, seconds)) "" else text
        } finally {
            stream.release()
            val cost = SystemClock.elapsedRealtime() - startedAt
            if (!final) partials?.recordCost(cost)
            metrics?.onDecode(samples.size, cost, final)
        }
    }

    override fun reset() {
        vad.reset()
        open.clear()
        preRoll.clear()
        samplesSincePartial = 0
        lastPartial = ""
        partials?.reset()
        segmentId++
    }

    override fun close() {
        vad.release()
        recognizer.release()
    }

    private companion object {
        const val PRE_ROLL_SAMPLES = PlaybackAudioCapture.TARGET_RATE / 5
    }
}

/** Stateful streaming transducer recognition with endpoint detection. */
internal class StreamingEngine(
    private val recognizer: OnlineRecognizer,
    private val sink: (SpeechEvent) -> Unit,
    private val metrics: SpeechMetrics?,
) : SpeechEngine {
    private var stream: OnlineStream = recognizer.createStream("")
    private var segmentId = 0L
    private var lastPartial = ""

    override fun accept(samples: FloatArray, backlogSamples: Int) {
        if (samples.isEmpty()) return
        val startedAt = SystemClock.elapsedRealtime()
        stream.acceptWaveform(samples, PlaybackAudioCapture.TARGET_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = SpeechTextPolicy.clean(recognizer.getResult(stream).text)
        val endpoint = recognizer.isEndpoint(stream)
        // Streaming decode is incremental, so each chunk's cost counts as final work.
        metrics?.onDecode(samples.size, SystemClock.elapsedRealtime() - startedAt, final = true)
        if (endpoint) {
            if (text.isNotEmpty()) sink(SpeechEvent(segmentId, text, isFinal = true))
            recognizer.reset(stream)
            segmentId++
            lastPartial = ""
        } else if (text.isNotEmpty() && text != lastPartial) {
            lastPartial = text
            sink(SpeechEvent(segmentId, text, isFinal = false))
        }
    }

    override fun reset() {
        stream.release()
        stream = recognizer.createStream("")
        lastPartial = ""
        segmentId++
    }

    override fun close() {
        stream.release()
        recognizer.release()
    }
}

/** Bounded float accumulator; when full, the oldest samples are dropped. */
internal class SampleBuffer(private val maxSamples: Int) {
    private var data = FloatArray(0)
    val size: Int get() = data.size

    fun append(samples: FloatArray) {
        if (samples.isEmpty()) return
        val combined = data + samples
        data = if (combined.size > maxSamples) combined.copyOfRange(combined.size - maxSamples, combined.size) else combined
    }

    fun toArray(): FloatArray = data.copyOf()

    fun clear() {
        data = FloatArray(0)
    }
}
