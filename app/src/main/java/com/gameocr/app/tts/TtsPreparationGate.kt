package com.gameocr.app.tts

import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

/** Pause/close/new utterance must also apply while language identification is in flight. */
internal class TtsPreparationGate(private val coordinator: TtsPlaybackCoordinator) {
    private val pending = AtomicLong(0L)

    fun begin(token: Long) { pending.set(token) }
    fun finish(token: Long) { pending.compareAndSet(token, 0L) }
    fun clear() { pending.set(0L) }

    fun pause(): Boolean {
        val token = pending.get().takeIf { it != 0L } ?: return false
        return coordinator.transition(token, TtsPlaybackPhase.PAUSED)
    }

    fun resume(): Boolean {
        val token = pending.get().takeIf { it != 0L } ?: return false
        return coordinator.transition(token, TtsPlaybackPhase.LOADING)
    }

    suspend fun awaitReady(token: Long): Boolean = coordinator.state.first {
        it.token != token || it.phase != TtsPlaybackPhase.PAUSED
    }.let { it.token == token && it.phase == TtsPlaybackPhase.LOADING }
}
