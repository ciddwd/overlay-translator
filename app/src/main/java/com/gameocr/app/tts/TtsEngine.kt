package com.gameocr.app.tts

import com.gameocr.app.data.Settings
import kotlinx.coroutines.flow.StateFlow

interface TtsEngine {
    val playbackState: StateFlow<TtsPlaybackState>

    suspend fun speak(text: String, settings: Settings, playbackId: String = text)

    suspend fun speakSource(text: String, settings: Settings, playbackId: String, evidence: List<TtsLanguageEvidence> = emptyList())

    suspend fun toggleSource(text: String, settings: Settings, playbackId: String, evidence: List<TtsLanguageEvidence> = emptyList()) {
        when (ttsPlaybackCommand(playbackState.value, playbackId)) {
            TtsPlaybackCommand.START -> speakSource(text, settings, playbackId, evidence)
            TtsPlaybackCommand.PAUSE -> pause()
            TtsPlaybackCommand.RESUME -> resume()
        }
    }

    suspend fun toggle(text: String, settings: Settings, playbackId: String) {
        when (ttsPlaybackCommand(playbackState.value, playbackId)) {
            TtsPlaybackCommand.START -> speak(text, settings, playbackId)
            TtsPlaybackCommand.PAUSE -> pause()
            TtsPlaybackCommand.RESUME -> resume()
        }
    }

    fun pause()
    fun resume()
    fun stop()
}
