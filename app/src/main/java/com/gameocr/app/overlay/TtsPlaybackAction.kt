package com.gameocr.app.overlay

import com.gameocr.app.tts.TtsPlaybackState
import kotlinx.coroutines.flow.StateFlow

data class TtsPlaybackAction(
    val playbackId: String,
    val playbackState: StateFlow<TtsPlaybackState>,
    val onToggle: (String) -> Unit,
    val onStart: (String) -> Unit,
)
