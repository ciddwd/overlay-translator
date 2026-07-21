package com.gameocr.app.tts

import com.gameocr.app.data.TtsProvider
import com.gameocr.app.data.MAX_TTS_PLAYBACK_GAIN_DB
import com.gameocr.app.data.MIN_TTS_PLAYBACK_GAIN_DB

internal fun normalizedTtsPlaybackGainDb(value: Int): Int =
    value.coerceIn(MIN_TTS_PLAYBACK_GAIN_DB, MAX_TTS_PLAYBACK_GAIN_DB)

internal fun ttsPlaybackGainMillibels(value: Int): Int =
    normalizedTtsPlaybackGainDb(value) * 100

internal fun supportsTtsPlaybackGain(provider: TtsProvider): Boolean =
    provider != TtsProvider.SYSTEM
