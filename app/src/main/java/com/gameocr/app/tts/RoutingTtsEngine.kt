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
    private val sourceLanguageResolver: SourceTtsLanguageResolver,
) : TtsEngine {
    override val playbackState: StateFlow<TtsPlaybackState> = playbackCoordinator.state
    private val preparation = TtsPreparationGate(playbackCoordinator)

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

    override suspend fun speak(text: String, settings: Settings, playbackId: String) =
        speakWithLanguage(text, settings, playbackId, sourceEvidence = null)

    override suspend fun speakSource(text: String, settings: Settings, playbackId: String, evidence: List<TtsLanguageEvidence>) =
        speakWithLanguage(text, settings, playbackId, sourceEvidence = evidence)

    private suspend fun speakWithLanguage(text: String, settings: Settings, playbackId: String, sourceEvidence: List<TtsLanguageEvidence>?) {
        if (!settings.ttsEnabled) return
        val normalized = normalizedTtsTextOrNull(text) ?: return
        stop()
        val backend = if (settings.ttsProvider == TtsProvider.SYSTEM) {
            TtsPlaybackBackend.SYSTEM
        } else {
            TtsPlaybackBackend.HTTP
        }
        val token = playbackCoordinator.begin(playbackId, backend)
        preparation.begin(token)
        try {
            val routedSettings = settings.copy(targetLang = if (sourceEvidence != null) {
                sourceLanguageResolver.resolve(normalized, sourceEvidence, settings.sourceLang)
            } else {
                resolvedSpokenTtsLanguageTag(normalized, settings.targetLang)
            })
            if (!preparation.awaitReady(token)) return
            preparation.finish(token)
            Timber.i(
                "TTS begin token=%d backend=%s playbackId=%s textLength=%d language=%s",
                token,
                backend.name,
                playbackId,
                normalized.length,
                routedSettings.targetLang,
            )
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
        } finally {
            preparation.finish(token)
        }
    }

    override fun pause() {
        if (preparation.pause()) return
        Timber.i("TTS pause token=%d backend=%s", playbackState.value.token, playbackState.value.backend)
        when (playbackState.value.backend) {
            TtsPlaybackBackend.SYSTEM -> systemTtsEngine.pause()
            TtsPlaybackBackend.HTTP -> httpTtsEngine.pause()
            null -> Unit
        }
    }

    override fun resume() {
        if (preparation.resume()) return
        Timber.i("TTS resume token=%d backend=%s", playbackState.value.token, playbackState.value.backend)
        when (playbackState.value.backend) {
            TtsPlaybackBackend.SYSTEM -> systemTtsEngine.resume()
            TtsPlaybackBackend.HTTP -> httpTtsEngine.resume()
            null -> Unit
        }
    }

    override fun stop() {
        preparation.clear()
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
