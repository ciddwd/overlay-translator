package com.gameocr.app.capture

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal interface LatestFrameSource<T> {
    fun acquireLatest(): T?
    fun setOnFrameAvailableListener(listener: (() -> Unit)?)
}

internal suspend fun <T> awaitLatestFrame(
    source: LatestFrameSource<T>,
    timeoutMs: Long,
    closeUndelivered: (T) -> Unit = {}
): T? {
    source.acquireLatest()?.let { return it }

    val frame = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            var delivered = false

            fun tryResumeWithLatest() {
                val latest = source.acquireLatest() ?: return
                if (delivered || !cont.isActive) {
                    closeUndelivered(latest)
                    return
                }
                delivered = true
                source.setOnFrameAvailableListener(null)
                cont.resume(latest)
            }

            cont.invokeOnCancellation {
                delivered = true
                source.setOnFrameAvailableListener(null)
            }

            source.setOnFrameAvailableListener { tryResumeWithLatest() }
            tryResumeWithLatest()
        }
    }

    return frame ?: source.acquireLatest()
}
