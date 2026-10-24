package com.gameocr.app.asr

import com.gameocr.app.data.AsrSegmentDurations
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig

/** sherpa-onnx v1.13.8 kotlin-api/Vad.kt and OnlineRecognizer.kt (verified HTTP 200). */
internal object AsrVadPolicy {
    fun config(durations: AsrSegmentDurations): VadModelConfig = durations.normalized().let {
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = AsrVadModel.ASSET_PATH,
                threshold = 0.5f,
                minSilenceDuration = it.pauseMs / 1000f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = it.maxSpeechMs / 1000f,
            ),
            sampleRate = 16000, numThreads = 1, provider = "cpu",
        )
    }

    fun streamingConfig(durations: AsrSegmentDurations): EndpointConfig = durations.normalized().let {
        EndpointConfig(
            // No-token silence remains a cleanup rule, independent of recognized-speech endpointing.
            rule1 = EndpointRule(false, 2.4f, 0f),
            rule2 = EndpointRule(true, it.pauseMs / 1000f, 0f),
            rule3 = EndpointRule(false, 0f, it.maxSpeechMs / 1000f),
        )
    }

    fun openBufferSamples(durations: AsrSegmentDurations): Int =
        (durations.normalized().maxSpeechMs + 1000) * 16
}
