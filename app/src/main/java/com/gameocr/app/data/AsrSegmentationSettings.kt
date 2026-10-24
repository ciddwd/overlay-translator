package com.gameocr.app.data

import kotlinx.serialization.Serializable

/** Client-side endpointing only; provider-managed turn detection has its own protocol. */
@Serializable
data class AsrSegmentDurations(
    val pauseMs: Int = 350,
    val maxSpeechMs: Int = 6000,
) {
    fun normalized() = copy(
        pauseMs = pauseMs.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS),
        maxSpeechMs = maxSpeechMs.coerceIn(MIN_SPEECH_MS, MAX_SPEECH_MS),
    )

    companion object {
        const val MIN_PAUSE_MS = 200
        const val MAX_PAUSE_MS = 2000
        const val MIN_SPEECH_MS = 2000
        const val MAX_SPEECH_MS = 15000
    }
}

enum class AsrSegmentationProfile { ON_DEVICE, ZIPFORMER, HTTP, REALTIME }

/** Keep settings independent across transports, including their existing default endpoints. */
@Serializable
data class AsrSegmentationSettings(
    val onDevice: AsrSegmentDurations = AsrSegmentDurations(),
    val zipformer: AsrSegmentDurations = AsrSegmentDurations(pauseMs = 600),
    val http: AsrSegmentDurations = AsrSegmentDurations(maxSpeechMs = 4000),
    val realtime: AsrSegmentDurations = AsrSegmentDurations(pauseMs = 400),
) {
    fun forProfile(profile: AsrSegmentationProfile): AsrSegmentDurations = when (profile) {
        AsrSegmentationProfile.ON_DEVICE -> onDevice
        AsrSegmentationProfile.ZIPFORMER -> zipformer
        AsrSegmentationProfile.HTTP -> http
        AsrSegmentationProfile.REALTIME -> realtime
    }.normalized()

    fun withProfile(profile: AsrSegmentationProfile, value: AsrSegmentDurations) = when (profile) {
        AsrSegmentationProfile.ON_DEVICE -> copy(onDevice = value.normalized())
        AsrSegmentationProfile.ZIPFORMER -> copy(zipformer = value.normalized())
        AsrSegmentationProfile.HTTP -> copy(http = value.normalized())
        AsrSegmentationProfile.REALTIME -> copy(realtime = value.normalized())
    }

    fun normalized() = copy(onDevice = onDevice.normalized(), zipformer = zipformer.normalized(),
        http = http.normalized(), realtime = realtime.normalized())
}

object AsrSegmentationPolicy {
    fun profile(settings: Settings): AsrSegmentationProfile? = when (settings.asrEngine) {
        AsrEngineKind.SENSE_VOICE, AsrEngineKind.WHISPER -> AsrSegmentationProfile.ON_DEVICE
        AsrEngineKind.ZIPFORMER -> AsrSegmentationProfile.ZIPFORMER
        AsrEngineKind.PARAFORMER_CLOUD -> if (settings.asrCloudProvider == AsrCloudProvider.OPENAI_COMPATIBLE) {
            when (settings.asrCloudConnections.openAiTransport) {
                OpenAiAsrTransport.HTTP_TRANSCRIPTION -> AsrSegmentationProfile.HTTP
                OpenAiAsrTransport.REALTIME_TRANSCRIPTION -> AsrSegmentationProfile.REALTIME
            }
        } else null
    }

    fun durations(settings: Settings): AsrSegmentDurations? =
        profile(settings)?.let(settings.asrSegmentation::forProfile)
}
