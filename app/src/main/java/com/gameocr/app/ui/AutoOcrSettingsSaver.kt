package com.gameocr.app.ui

import com.gameocr.app.data.AutoOcrSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owned by the ViewModel so returning from the page does not cancel an in-flight write. */
internal class AutoOcrSettingsSaver(
    private val scope: CoroutineScope,
    private val write: suspend (AutoOcrSettings) -> Unit,
) {
    private val mutex = Mutex()

    fun save(value: AutoOcrSettings): Deferred<AutoOcrSettings> =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                val normalized = value.normalized()
                write(normalized)
                normalized
            }
        }
}
