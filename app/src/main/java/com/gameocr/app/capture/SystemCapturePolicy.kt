package com.gameocr.app.capture

import com.gameocr.app.shizuku.ShizukuCapabilities
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit user choice. A null [CaptureStartPreference.mode] means Auto. SYSTEM is the MediaProjection prompt. */
enum class CaptureStartMode { ROOT, SHIZUKU, ACCESSIBILITY, SYSTEM }
enum class CaptureBackend { ACCESSIBILITY, MEDIA_PROJECTION, SHIZUKU, ROOT }

internal fun isShizukuUsableForCapture(availability: ShizukuCapabilities.Availability): Boolean =
    availability == ShizukuCapabilities.Availability.READY ||
        availability == ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED

/**
 * Auto order is fixed everywhere: Root, then Shizuku, then Accessibility, then the system
 * screenshot prompt. [rootAvailable] means a su binary exists and root was not refused this session.
 */
internal fun resolveCaptureStartMode(
    preferred: CaptureStartMode?,
    rootAvailable: Boolean,
    availability: ShizukuCapabilities.Availability,
    sdk: Int,
    accessibilityEnabled: Boolean,
): CaptureStartMode = preferred ?: when {
    rootAvailable -> CaptureStartMode.ROOT
    isShizukuUsableForCapture(availability) -> CaptureStartMode.SHIZUKU
    sdk >= 30 && accessibilityEnabled -> CaptureStartMode.ACCESSIBILITY
    else -> CaptureStartMode.SYSTEM
}

/**
 * Backend for every non-Shizuku start. Null means an explicit Accessibility choice that is not
 * ready yet, so the caller must send the user to accessibility settings instead of falling back.
 */
internal fun nonShizukuCaptureBackend(
    preferred: CaptureStartMode?,
    sdk: Int,
    connected: Boolean,
    capable: Boolean,
): CaptureBackend? {
    if (preferred == CaptureStartMode.SYSTEM) return CaptureBackend.MEDIA_PROJECTION
    val backend = systemCaptureBackend(sdk, connected, capable)
    return if (preferred == CaptureStartMode.ACCESSIBILITY && sdk >= 30 &&
        backend != CaptureBackend.ACCESSIBILITY) null else backend
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
    /** Null restores Auto. */
    fun select(mode: CaptureStartMode?) { selected.value = mode }
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
