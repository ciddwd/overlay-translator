package com.gameocr.app.tts

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TtsProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

@Singleton
class RoutingTtsEngine @Inject constructor(
    private val systemTtsEngine: SystemTtsEngine,
    private val httpTtsEngine: HttpTtsEngine,
    private val playbackCoordinator: TtsPlaybackCoordinator,
) : TtsEngine {
    override val playbackState: StateFlow<TtsPlaybackState> = playbackCoordinator.state

    override suspend fun toggle(text: String, settings: Settings, playbackId: String) {
        val before = playbackState.value
        val command = ttsPlaybackCommand(before, playbackId)
        Timber.i(
            "TTS toggle command=%s requestedId=%s currentId=%s phase=%s token=%d",
            command.name,
            playbackId,
            before.playbackId.orEmpty(),
            before.phase.name,
            before.token,
        )
        when (command) {
            TtsPlaybackCommand.START -> speak(text, settings, playbackId)
            TtsPlaybackCommand.PAUSE -> pause()
            TtsPlaybackCommand.RESUME -> resume()
        }
    }

    override suspend fun speak(text: String, settings: Settings, playbackId: String) {
        if (!settings.ttsEnabled) return
        val normalized = normalizedTtsTextOrNull(text) ?: return
        val routedSettings = settings.copy(
            targetLang = resolvedSpokenTtsLanguageTag(normalized, settings.targetLang)
        )
        stop()
        val backend = if (routedSettings.ttsProvider == TtsProvider.SYSTEM) {
            TtsPlaybackBackend.SYSTEM
        } else {
            TtsPlaybackBackend.HTTP
        }
        val token = playbackCoordinator.begin(playbackId, backend)
        Timber.i(
            "TTS begin token=%d backend=%s playbackId=%s textLength=%d language=%s",
            token,
            backend.name,
            playbackId,
            normalized.length,
            routedSettings.targetLang,
        )
        try {
            when (routedSettings.ttsProvider) {
                TtsProvider.SYSTEM -> systemTtsEngine.speak(normalized, routedSettings, token)
                TtsProvider.GENERIC_HTTP,
                TtsProvider.VOLCENGINE,
                TtsProvider.MINIMAX,
                TtsProvider.MIMO -> httpTtsEngine.speak(normalized, routedSettings, token)
            }
        } catch (error: Throwable) {
            playbackCoordinator.finish(token)
            throw error
        }
    }

    override fun pause() {
        Timber.i("TTS pause token=%d backend=%s", playbackState.value.token, playbackState.value.backend)
        when (playbackState.value.backend) {
            TtsPlaybackBackend.SYSTEM -> systemTtsEngine.pause()
            TtsPlaybackBackend.HTTP -> httpTtsEngine.pause()
            null -> Unit
        }
    }

    override fun resume() {
        Timber.i("TTS resume token=%d backend=%s", playbackState.value.token, playbackState.value.backend)
        when (playbackState.value.backend) {
            TtsPlaybackBackend.SYSTEM -> systemTtsEngine.resume()
            TtsPlaybackBackend.HTTP -> httpTtsEngine.resume()
            null -> Unit
        }
    }

    override fun stop() {
        val active = playbackState.value
        if (active.phase != TtsPlaybackPhase.IDLE) {
            Timber.i(
                "TTS stop token=%d playbackId=%s phase=%s",
                active.token,
                active.playbackId.orEmpty(),
                active.phase.name,
            )
        }
        systemTtsEngine.stop()
        httpTtsEngine.stop()
        playbackCoordinator.clear()
    }
}
