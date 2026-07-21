package com.gameocr.app.tts

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TtsPlaybackPhase {
    IDLE,
    LOADING,
    PLAYING,
    PAUSED,
}

enum class TtsPlaybackBackend {
    SYSTEM,
    HTTP,
}

data class TtsPlaybackState(
    val phase: TtsPlaybackPhase = TtsPlaybackPhase.IDLE,
    val playbackId: String? = null,
    val backend: TtsPlaybackBackend? = null,
    val token: Long = 0L,
)

enum class TtsPlaybackCommand {
    START,
    PAUSE,
    RESUME,
}

enum class TtsPlaybackButtonMode {
    SPEAK,
    PAUSE,
    RESUME,
}

internal fun ttsPlaybackCommand(
    state: TtsPlaybackState,
    requestedPlaybackId: String,
): TtsPlaybackCommand = when {
    state.playbackId != requestedPlaybackId -> TtsPlaybackCommand.START
    state.phase == TtsPlaybackPhase.LOADING -> TtsPlaybackCommand.PAUSE
    state.phase == TtsPlaybackPhase.PLAYING -> TtsPlaybackCommand.PAUSE
    state.phase == TtsPlaybackPhase.PAUSED -> TtsPlaybackCommand.RESUME
    else -> TtsPlaybackCommand.START
}

internal fun ttsPlaybackButtonMode(
    state: TtsPlaybackState,
    playbackId: String,
): TtsPlaybackButtonMode = when {
    state.playbackId != playbackId -> TtsPlaybackButtonMode.SPEAK
    state.phase == TtsPlaybackPhase.LOADING -> TtsPlaybackButtonMode.PAUSE
    state.phase == TtsPlaybackPhase.PLAYING -> TtsPlaybackButtonMode.PAUSE
    state.phase == TtsPlaybackPhase.PAUSED -> TtsPlaybackButtonMode.RESUME
    else -> TtsPlaybackButtonMode.SPEAK
}

internal fun systemTtsResumeOffset(text: String, lastRangeStart: Int): Int =
    lastRangeStart.coerceIn(0, text.length)

@Singleton
class TtsPlaybackCoordinator @Inject constructor() {
    private val nextToken = AtomicLong(0L)
    private val mutableState = MutableStateFlow(TtsPlaybackState())

    val state: StateFlow<TtsPlaybackState> = mutableState.asStateFlow()

    @Synchronized
    fun begin(playbackId: String, backend: TtsPlaybackBackend): Long {
        val token = nextToken.incrementAndGet()
        mutableState.value = TtsPlaybackState(
            phase = TtsPlaybackPhase.LOADING,
            playbackId = playbackId,
            backend = backend,
            token = token,
        )
        return token
    }

    @Synchronized
    fun transition(token: Long, phase: TtsPlaybackPhase): Boolean {
        val current = mutableState.value
        if (current.token != token || current.phase == TtsPlaybackPhase.IDLE) return false
        mutableState.value = current.copy(phase = phase)
        return true
    }

    @Synchronized
    fun finish(token: Long): Boolean {
        if (mutableState.value.token != token) return false
        mutableState.value = TtsPlaybackState()
        return true
    }

    @Synchronized
    fun clear() {
        mutableState.value = TtsPlaybackState()
    }
}
