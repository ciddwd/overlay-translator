package com.gameocr.app.ocr

import com.gameocr.app.translate.MlKitDownloadPhase
import com.gameocr.app.translate.MlKitModelDownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** No pair/URL: Play services does not expose a public model download address. */
internal class MlKitOcrDownloadSession(
    private val scope: CoroutineScope,
    private val download: suspend () -> Unit,
) {
    private val mutableState = MutableStateFlow(MlKitModelDownloadState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun start(timeoutSeconds: Int) {
        if (state.value.phase == MlKitDownloadPhase.DOWNLOADING) return
        reset()
        val request = generation
        val pending = MlKitModelDownloadState(phase = MlKitDownloadPhase.DOWNLOADING, requestId = request)
        mutableState.value = pending
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(timeoutSeconds.coerceAtLeast(1).toLong() * 1000) { download() }
                if (generation == request) mutableState.value = pending.copy(phase = MlKitDownloadPhase.READY)
            } catch (error: TimeoutCancellationException) {
                if (generation == request) mutableState.value = pending.copy(phase = MlKitDownloadPhase.TIMED_OUT)
            } catch (error: CancellationException) {
                if (generation == request) mutableState.value = pending.copy(phase = MlKitDownloadPhase.CANCELLED)
                throw error
            } catch (error: Exception) {
                if (generation == request) mutableState.value = pending.copy(
                    phase = MlKitDownloadPhase.FAILED, error = error.message ?: error.javaClass.simpleName)
            } finally {
                if (generation == request) job = null
            }
        }.also { it.start() }
    }

    fun cancel(requestId: Long) {
        val previous = state.value
        if (previous.requestId != requestId || previous.phase != MlKitDownloadPhase.DOWNLOADING) return
        reset()
        mutableState.value = previous.copy(phase = MlKitDownloadPhase.CANCELLED)
    }

    fun reset() {
        generation++
        job?.cancel()
        job = null
        mutableState.value = MlKitModelDownloadState()
    }

    fun clearFinished() {
        if (state.value.phase != MlKitDownloadPhase.DOWNLOADING) reset()
    }
}
