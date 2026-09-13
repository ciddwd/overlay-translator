package com.gameocr.app.ui

import com.gameocr.app.data.Settings
import com.gameocr.app.data.normalizedFloatingButtonAlpha
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only these five fields are auto-saved; unrelated settings drafts remain untouched. */
internal data class FloatingButtonSettings(
    val sizeDp: Int,
    val alpha: Float,
    val snapToEdge: Boolean,
    val autoDock: Boolean,
    val dockInsetDp: Int,
) {
    fun normalized() = copy(
        sizeDp = sizeDp.coerceIn(32, 96),
        alpha = normalizedFloatingButtonAlpha(alpha),
        dockInsetDp = dockInsetDp.coerceIn(0, 40),
    )

    fun applyTo(settings: Settings): Settings = settings.copy(
        floatingButtonSizeDp = sizeDp,
        floatingButtonAlpha = alpha,
        floatingButtonSnapToEdge = snapToEdge,
        floatingButtonAutoDock = autoDock,
        floatingButtonDockInsetDp = dockInsetDp,
    )
}

/** ViewModel-owned writes survive leaving the composable and retain UI event order. */
internal class FloatingButtonSettingsSaver(
    private val scope: CoroutineScope,
    private val write: suspend (FloatingButtonSettings) -> Unit,
) {
    private val mutex = Mutex()

    fun save(value: FloatingButtonSettings): Deferred<FloatingButtonSettings> =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                val normalized = value.normalized()
                write(normalized)
                normalized
            }
        }
}
