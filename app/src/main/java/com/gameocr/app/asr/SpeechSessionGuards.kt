package com.gameocr.app.asr

import com.gameocr.app.overlay.SpeechBallState
import kotlinx.coroutines.CancellationException

/**
 * One start request at a time, from the tap until it hands off to the consent flow or the
 * controller. Main thread only. A new tap while a request is active is ignored; a stop
 * invalidates the active request so its later hand-off does nothing.
 */
internal class SpeechStartGuard {
    private var active: Long? = null
    private var next = 0L

    val isActive: Boolean get() = active != null

    /** Returns the new request's token, or null while another request is still preparing. */
    fun begin(): Long? {
        if (active != null) return null
        return (++next).also { active = it }
    }

    fun isCurrent(token: Long): Boolean = active == token

    /** Returns true when [token] was the active request and is now finished. */
    fun finish(token: Long): Boolean {
        if (active != token) return false
        active = null
        return true
    }

    fun invalidate() {
        active = null
    }
}

internal object SpeechFailurePolicy {
    /**
     * An asynchronous engine failure may end the session only when it comes from the engine that
     * is still loaded. Pause and resume change the generation but keep the engine, so the
     * generation cannot be used to recognize a replaced engine.
     */
    fun acceptsCloudFailure(
        state: SpeechBallState,
        engineLoaded: Boolean,
        failedInstance: Long,
        currentInstance: Long,
    ): Boolean = state != SpeechBallState.IDLE && engineLoaded && failedInstance == currentInstance
}

/**
 * Runs a session worker loop. An unexpected exception must not escape the coroutine, which would
 * crash the process and skip cleanup; it is passed to [onFailure] instead. Cancellation is a
 * normal stop and is rethrown.
 */
internal inline fun runWorkerLoop(onFailure: (Exception) -> Unit, body: () -> Unit) {
    try {
        body()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
    }
}
