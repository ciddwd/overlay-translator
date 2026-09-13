package com.gameocr.app.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class MlKitDownloadPhase { IDLE, DOWNLOADING, READY, FAILED, TIMED_OUT, CANCELLED }

internal data class MlKitModelDownloadState(
    val pair: Pair<String, String>? = null,
    val phase: MlKitDownloadPhase = MlKitDownloadPhase.IDLE,
    val missingLanguages: Set<String> = emptySet(),
    val error: String? = null,
    val requestId: Long = 0,
) {
    val browserUrl: String?
        get() = if (phase == MlKitDownloadPhase.TIMED_OUT && pair != null) {
            MlKitModelDownloadLinks.forPair(pair, missingLanguages)
        } else null
}

/** Shared UI operation for settings, quick download and onboarding. The translator owns the deadline. */
internal class MlKitModelDownloadSession(
    private val scope: CoroutineScope,
    private val download: suspend (Pair<String, String>) -> Unit,
) {
    private val mutableState = MutableStateFlow(MlKitModelDownloadState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun start(pair: Pair<String, String>, missingLanguages: Set<String>) {
        if (state.value.phase == MlKitDownloadPhase.DOWNLOADING && state.value.pair == pair) return
        reset()
        val request = generation
        val pending = MlKitModelDownloadState(
            pair, MlKitDownloadPhase.DOWNLOADING, missingLanguages.toSet(), requestId = request,
        )
        mutableState.value = pending
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                download(pair)
                if (generation == request) mutableState.value = pending.copy(phase = MlKitDownloadPhase.READY)
            } catch (error: TimeoutCancellationException) {
                if (generation == request) mutableState.value = pending.copy(phase = MlKitDownloadPhase.TIMED_OUT)
            } catch (error: CancellationException) {
                if (generation == request) mutableState.value = pending.copy(phase = MlKitDownloadPhase.CANCELLED)
                throw error
            } catch (error: Exception) {
                if (generation == request) mutableState.value = pending.copy(
                    phase = MlKitDownloadPhase.FAILED,
                    error = error.message ?: error.javaClass.simpleName,
                )
            } finally {
                if (generation == request) job = null
            }
        }.also { it.start() }
    }

    fun cancel(expectedRequestId: Long = state.value.requestId) {
        val previous = state.value
        if (previous.phase != MlKitDownloadPhase.DOWNLOADING || previous.requestId != expectedRequestId) return
        reset()
        mutableState.value = previous.copy(phase = MlKitDownloadPhase.CANCELLED, error = null)
    }

    fun reset() {
        generation += 1
        job?.cancel()
        job = null
        mutableState.value = MlKitModelDownloadState()
    }
}

internal object MlKitModelDownloadLinks {
    // Browser diagnostics only; SDK downloads still use the official model manager.
    // Verified against translate:17.0.3 res/raw/translate_models_metadata.json (PKG_HIGH).
    // All 58 packages are format v5/revision 29; all returned HTTP 200 via HEAD + redirects
    // on 2026-09-11. Package names use sorted SDK language codes, not always "en_<language>".
    // Keep the complete catalog regression in sync and reverify URLs when upgrading the SDK.

    fun forPair(pair: Pair<String, String>, missingLanguages: Set<String>): String? {
        val source = runCatching { MlKitLanguagePolicy.resolveConfiguredSource(pair.first) }.getOrNull() ?: return null
        val target = runCatching { MlKitLanguagePolicy.resolveTarget(pair.second) }.getOrNull() ?: return null
        val required = MlKitLanguagePolicy.requiredDownloadLanguages(source, target)
        val missing = missingLanguages.mapNotNull {
            runCatching { MlKitLanguagePolicy.resolveTarget(it) }.getOrNull()
        }.toSet()
        val language = required.firstOrNull { it in missing } ?: return null
        val packageLanguage = if (language == "he") "iw" else language
        val packageName = listOf("en", packageLanguage).sorted().joinToString("_")
        return "https://redirector.gvt1.com/edgedl/translate/offline/v5/high/r29/$packageName.zip"
    }
}
