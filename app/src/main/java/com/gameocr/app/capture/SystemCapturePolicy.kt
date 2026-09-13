package com.gameocr.app.capture

import com.gameocr.app.shizuku.ShizukuCapabilities
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CaptureStartMode { SYSTEM, SHIZUKU }
enum class CaptureBackend { ACCESSIBILITY, MEDIA_PROJECTION, SHIZUKU }

internal fun resolveCaptureStartMode(
    preferred: CaptureStartMode?, availability: ShizukuCapabilities.Availability,
): CaptureStartMode = preferred ?: when (availability) {
    ShizukuCapabilities.Availability.READY,
    ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED -> CaptureStartMode.SHIZUKU
    else -> CaptureStartMode.SYSTEM
}

/** Showing a start action is not proof that the overlay permission has been granted. */
internal fun showCaptureStartControls(
    overlayPermissionGranted: Boolean,
    serviceRunning: Boolean,
    availability: ShizukuCapabilities.Availability,
): Boolean = serviceRunning || overlayPermissionGranted ||
    availability == ShizukuCapabilities.Availability.READY

/** An already-authorized Shizuku may configure permissions without changing the capture backend. */
internal fun shouldConfigureCapturePermissions(
    overlayPermissionGranted: Boolean,
    accessibilityReady: Boolean,
    useShizuku: Boolean,
    availability: ShizukuCapabilities.Availability,
): Boolean = (!overlayPermissionGranted || !accessibilityReady) &&
    (useShizuku || availability == ShizukuCapabilities.Availability.READY)

/** Session-only choice shared by the home screen and tile, never persisted in presets. */
object CaptureStartPreference {
    private val selected = MutableStateFlow<CaptureStartMode?>(null)
    val mode = selected.asStateFlow()
    fun select(mode: CaptureStartMode) { selected.value = mode }
}

internal fun systemCaptureBackend(sdk: Int, connected: Boolean, capable: Boolean): CaptureBackend =
    if (sdk >= 30 && connected && capable) CaptureBackend.ACCESSIBILITY
    else CaptureBackend.MEDIA_PROJECTION

internal class CaptureStartGate {
    private val busy = AtomicBoolean(false)
    fun acquire(): Boolean = busy.compareAndSet(false, true)
    fun release() { busy.set(false) }
}

/** AOSP rejects requests <=333 ms apart. Shared by every screenshotter instance. */
internal class AccessibilityScreenshotPacing(private val intervalMs: Long = 350L) {
    private var lastRequestMs: Long? = null
    fun delayBeforeRequest(nowMs: Long): Long = lastRequestMs?.let {
        if (nowMs < it) 0L else (intervalMs - (nowMs - it)).coerceAtLeast(0L)
    } ?: 0L
    fun requestStarted(nowMs: Long) { lastRequestMs = nowMs }
}
