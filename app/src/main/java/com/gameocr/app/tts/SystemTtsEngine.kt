package com.gameocr.app.tts

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.gameocr.app.data.Languages
import com.gameocr.app.data.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val SYSTEM_TTS_START_WATCHDOG_MS = 1_500L
private const val SYSTEM_TTS_RETRY_DELAY_MS = 180L
internal const val SYSTEM_TTS_MAX_START_ATTEMPTS = 2

internal enum class SystemTtsTerminalAction {
    RETRY,
    FINISH,
}

internal class SystemTtsLanguageUnavailableException(
    val languageTag: String,
) : IllegalStateException("System TTS language is not supported: $languageTag")

internal fun systemTtsTerminalAction(
    utteranceStarted: Boolean,
    startAttempt: Int,
    maxStartAttempts: Int = SYSTEM_TTS_MAX_START_ATTEMPTS,
): SystemTtsTerminalAction = if (!utteranceStarted && startAttempt < maxStartAttempts) {
    SystemTtsTerminalAction.RETRY
} else {
    SystemTtsTerminalAction.FINISH
}

@Singleton
class SystemTtsEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playbackCoordinator: TtsPlaybackCoordinator,
) {
    private val initMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionLock = Any()
    private var tts: TextToSpeech? = null
    private var session: SystemTtsSession? = null

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            markSessionStarted(utteranceId)?.let { current ->
                Timber.i(
                    "System TTS started token=%d attempt=%d",
                    current.token,
                    current.startAttempt,
                )
                playbackCoordinator.transition(current.token, TtsPlaybackPhase.PLAYING)
            }
        }

        override fun onRangeStart(
            utteranceId: String,
            start: Int,
            end: Int,
            frame: Int,
        ) {
            synchronized(sessionLock) {
                val current = session ?: return
                if (current.utteranceId != utteranceId) return
                session = current.copy(
                    lastRangeStart = systemTtsResumeOffset(
                        current.fullText,
                        current.startOffset + start,
                    ),
                )
            }
        }

        override fun onDone(utteranceId: String) {
            handleTerminalCallback(utteranceId, "done")
        }

        @Deprecated("Deprecated by Android")
        override fun onError(utteranceId: String) {
            handleTerminalCallback(utteranceId, "error")
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            handleTerminalCallback(utteranceId, "error:$errorCode")
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            val current = activeSession(utteranceId) ?: return
            if (
                playbackCoordinator.state.value.token == current.token &&
                playbackCoordinator.state.value.phase == TtsPlaybackPhase.PAUSED
            ) {
                return
            }
            handleTerminalCallback(utteranceId, "stopped")
        }
    }

    suspend fun speak(text: String, settings: Settings, token: Long) {
        val normalized = normalizedTtsTextOrNull(text) ?: return
        synchronized(sessionLock) {
            session = SystemTtsSession(
                token = token,
                fullText = normalized,
            )
        }
        try {
            val engine = ensureEngineReady()
            withContext(Dispatchers.Main.immediate) {
                if (!isCurrentSession(token)) return@withContext
                configureEngine(engine, settings)
                synchronized(sessionLock) {
                    val current = session ?: return@synchronized
                    if (current.token == token) session = current.copy(configured = true)
                }
                if (
                    playbackCoordinator.state.value.token == token &&
                    playbackCoordinator.state.value.phase != TtsPlaybackPhase.PAUSED
                ) {
                    val result = startRemainingOnMain(engine, token)
                    if (result == TextToSpeech.ERROR) {
                        throw RuntimeException("System TTS rejected utterance")
                    }
                }
            }
        } catch (error: Throwable) {
            clearSession(token)
            playbackCoordinator.finish(token)
            throw error
        }
    }

    suspend fun availableVoices(preferredLanguageTag: String): List<SystemTtsVoiceOption> {
        val engine = ensureEngineReady()
        return withContext(Dispatchers.Main.immediate) {
            orderedSystemTtsVoiceOptions(
                voices = engine.voices.orEmpty().map { voice ->
                    SystemTtsVoiceOption(
                        name = voice.name,
                        localeTag = voice.locale.toLanguageTag(),
                        networkConnectionRequired = voice.isNetworkConnectionRequired,
                    )
                },
                preferredLanguageTag = preferredLanguageTag,
            )
        }
    }

    fun pause() {
        val token = synchronized(sessionLock) {
            val current = session ?: return
            if (playbackCoordinator.state.value.token != current.token) return
            session = current.copy(
                utteranceId = null,
                lastRangeStart = systemTtsResumeOffset(current.fullText, current.lastRangeStart),
            )
            current.token
        }
        if (!playbackCoordinator.transition(token, TtsPlaybackPhase.PAUSED)) return
        runOnMain { runCatching { tts?.stop() } }
    }

    fun resume() {
        val current = synchronized(sessionLock) { session } ?: return
        val state = playbackCoordinator.state.value
        if (state.token != current.token || state.phase != TtsPlaybackPhase.PAUSED) return
        if (!playbackCoordinator.transition(current.token, TtsPlaybackPhase.LOADING)) return
        runOnMain {
            val engine = tts ?: return@runOnMain
            val ready = synchronized(sessionLock) {
                session?.takeIf { it.token == current.token }?.configured == true
            }
            if (ready) startRemainingOnMain(engine, current.token)
        }
    }

    fun stop() {
        val token = synchronized(sessionLock) {
            val currentToken = session?.token
            session = null
            currentToken
        }
        token?.let(playbackCoordinator::finish)
        runOnMain { runCatching { tts?.stop() } }
    }

    private fun configureEngine(engine: TextToSpeech, settings: Settings) {
        val languageTag = settings.targetLang.takeUnless { it == Languages.AUTO.code }
            ?: Locale.getDefault().toLanguageTag()
        val languageResult = engine.setLanguage(Locale.forLanguageTag(languageTag))
        if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            throw SystemTtsLanguageUnavailableException(languageTag)
        }
        val requestedVoiceName = selectSystemTtsVoiceForLanguage(
            voices = engine.voices.orEmpty().map { voice ->
                SystemTtsVoiceOption(
                    name = voice.name,
                    localeTag = voice.locale.toLanguageTag(),
                    networkConnectionRequired = voice.isNetworkConnectionRequired,
                )
            },
            requested = settings.ttsVoice,
            languageTag = languageTag,
        )
        requestedVoiceName?.let { name ->
            engine.voices.orEmpty().firstOrNull { it.name == name }?.let { voice ->
                engine.voice = voice
            }
        }
        engine.setSpeechRate(ttsSpeechRate(settings.ttsSpeed))
        engine.setPitch(ttsPitch(settings.ttsPitch))
    }

    private fun startRemainingOnMain(engine: TextToSpeech, token: Long): Int {
        val next = synchronized(sessionLock) {
            val current = session ?: return TextToSpeech.ERROR
            if (current.token != token || !current.configured || current.utteranceId != null) {
                return TextToSpeech.ERROR
            }
            val offset = systemTtsResumeOffset(current.fullText, current.lastRangeStart)
            if (offset >= current.fullText.length) {
                session = null
                playbackCoordinator.finish(token)
                return TextToSpeech.SUCCESS
            }
            val utteranceId = "overlay-tts-${UUID.randomUUID()}"
            val attempt = current.startAttempt + 1
            session = current.copy(
                utteranceId = utteranceId,
                startOffset = offset,
                lastRangeStart = offset,
                startAttempt = attempt,
                utteranceStarted = false,
            )
            SystemTtsSubmission(
                text = current.fullText.substring(offset),
                utteranceId = utteranceId,
                attempt = attempt,
            )
        }
        val result = engine.speak(
            next.text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            next.utteranceId,
        )
        Timber.i(
            "System TTS submitted token=%d attempt=%d result=%d textLength=%d",
            token,
            next.attempt,
            result,
            next.text.length,
        )
        if (result == TextToSpeech.ERROR) {
            clearSession(token)
            playbackCoordinator.finish(token)
        } else {
            scheduleStartWatchdog(
                engine = engine,
                token = token,
                utteranceId = next.utteranceId,
                attempt = next.attempt,
            )
        }
        return result
    }

    private fun activeSession(utteranceId: String): SystemTtsSession? =
        synchronized(sessionLock) { session?.takeIf { it.utteranceId == utteranceId } }

    private fun markSessionStarted(utteranceId: String): SystemTtsSession? =
        synchronized(sessionLock) {
            val current = session ?: return null
            if (current.utteranceId != utteranceId) return null
            current.copy(utteranceStarted = true).also { session = it }
        }

    private fun handleTerminalCallback(utteranceId: String, reason: String) {
        val outcome = synchronized(sessionLock) {
            val current = session ?: return
            if (current.utteranceId != utteranceId) return
            if (
                systemTtsTerminalAction(
                    utteranceStarted = current.utteranceStarted,
                    startAttempt = current.startAttempt,
                ) == SystemTtsTerminalAction.RETRY
            ) {
                session = current.copy(
                    utteranceId = null,
                    utteranceStarted = false,
                )
                SystemTtsTerminalOutcome.Retry(current.token, current.startAttempt)
            } else {
                session = null
                SystemTtsTerminalOutcome.Finish(
                    token = current.token,
                    started = current.utteranceStarted,
                    attempt = current.startAttempt,
                )
            }
        }
        when (outcome) {
            is SystemTtsTerminalOutcome.Retry -> {
                Timber.w(
                    "System TTS ended before start token=%d attempt=%d reason=%s; retrying",
                    outcome.token,
                    outcome.attempt,
                    reason,
                )
                scheduleRetry(outcome.token, outcome.attempt)
            }
            is SystemTtsTerminalOutcome.Finish -> {
                Timber.i(
                    "System TTS finished token=%d attempt=%d started=%s reason=%s",
                    outcome.token,
                    outcome.attempt,
                    outcome.started,
                    reason,
                )
                playbackCoordinator.finish(outcome.token)
            }
        }
    }

    private fun scheduleStartWatchdog(
        engine: TextToSpeech,
        token: Long,
        utteranceId: String,
        attempt: Int,
    ) {
        mainHandler.postDelayed({
            val outcome = synchronized(sessionLock) {
                val current = session ?: return@postDelayed
                if (
                    current.token != token ||
                    current.utteranceId != utteranceId ||
                    current.startAttempt != attempt ||
                    current.utteranceStarted
                ) {
                    return@postDelayed
                }
                if (
                    systemTtsTerminalAction(
                        utteranceStarted = false,
                        startAttempt = attempt,
                    ) == SystemTtsTerminalAction.RETRY
                ) {
                    session = current.copy(
                        utteranceId = null,
                        utteranceStarted = false,
                    )
                    SystemTtsTerminalAction.RETRY
                } else {
                    session = null
                    SystemTtsTerminalAction.FINISH
                }
            }
            runCatching { engine.stop() }
            when (outcome) {
                SystemTtsTerminalAction.RETRY -> {
                    Timber.w(
                        "System TTS start timeout token=%d attempt=%d; retrying",
                        token,
                        attempt,
                    )
                    scheduleRetry(token, attempt)
                }
                SystemTtsTerminalAction.FINISH -> {
                    Timber.w(
                        "System TTS start timeout token=%d attempt=%d; giving up",
                        token,
                        attempt,
                    )
                    playbackCoordinator.finish(token)
                }
            }
        }, SYSTEM_TTS_START_WATCHDOG_MS)
    }

    private fun scheduleRetry(token: Long, previousAttempt: Int) {
        mainHandler.postDelayed({
            val state = playbackCoordinator.state.value
            val canRetry = synchronized(sessionLock) {
                val current = session
                current != null &&
                    current.token == token &&
                    current.utteranceId == null &&
                    current.startAttempt == previousAttempt
            }
            if (
                !canRetry ||
                state.token != token ||
                state.phase != TtsPlaybackPhase.LOADING
            ) {
                return@postDelayed
            }
            val engine = tts
            if (engine == null) {
                clearSession(token)
                playbackCoordinator.finish(token)
                return@postDelayed
            }
            startRemainingOnMain(engine, token)
        }, SYSTEM_TTS_RETRY_DELAY_MS)
    }

    private fun clearSession(token: Long) {
        synchronized(sessionLock) {
            if (session?.token == token) session = null
        }
    }

    private fun isCurrentSession(token: Long): Boolean =
        synchronized(sessionLock) { session?.token == token }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private suspend fun ensureEngineReady(): TextToSpeech {
        tts?.let { return it }
        return initMutex.withLock {
            tts?.let { return@withLock it }
            withContext(Dispatchers.Main.immediate) {
                val ready = CompletableDeferred<Int>()
                val engine = TextToSpeech(appContext) { status -> ready.complete(status) }
                val status = withTimeoutOrNull(5_000L) { ready.await() } ?: TextToSpeech.ERROR
                if (status != TextToSpeech.SUCCESS) {
                    runCatching { engine.shutdown() }
                    throw RuntimeException("System TTS init failed: status=$status")
                }
                engine.setOnUtteranceProgressListener(progressListener)
                tts = engine
                engine
            }
        }
    }
}

private data class SystemTtsSession(
    val token: Long,
    val fullText: String,
    val configured: Boolean = false,
    val utteranceId: String? = null,
    val startOffset: Int = 0,
    val lastRangeStart: Int = 0,
    val startAttempt: Int = 0,
    val utteranceStarted: Boolean = false,
)

private data class SystemTtsSubmission(
    val text: String,
    val utteranceId: String,
    val attempt: Int,
)

private sealed interface SystemTtsTerminalOutcome {
    data class Retry(val token: Long, val attempt: Int) : SystemTtsTerminalOutcome

    data class Finish(
        val token: Long,
        val started: Boolean,
        val attempt: Int,
    ) : SystemTtsTerminalOutcome
}
