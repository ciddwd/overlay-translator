package com.gameocr.app.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Socket shutdown can write TLS records, so even idle-connection eviction belongs on IO. */
internal class ScreenWakeConnectionCleaner(
    private val evictIdleConnections: () -> Int,
    private val onRecovered: (Long?, Int) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val cleanupMutex = Mutex()
    private var scope: CoroutineScope? = null

    fun start() = synchronized(lock) {
        if (scope == null) scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    fun stop() = synchronized(lock) {
        scope?.cancel()
        scope = null
    }

    fun enqueue(screenOffDurationMs: Long?) = synchronized(lock) {
        val session = scope ?: return@synchronized
        session.launch {
            try {
                cleanupMutex.withLock {
                    coroutineContext.ensureActive()
                    val cleared = evictIdleConnections()
                    // A running socket close cannot be interrupted by coroutine cancellation.
                    // Do not publish its result after stop() or a subsequent start().
                    synchronized(lock) {
                        coroutineContext.ensureActive()
                        if (scope === session) onRecovered(screenOffDurationMs, cleared)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(lock) {
                    coroutineContext.ensureActive()
                    if (scope === session) onFailure(error)
                }
            }
        }
        Unit
    }
}
