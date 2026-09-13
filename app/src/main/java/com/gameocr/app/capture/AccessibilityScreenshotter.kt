package com.gameocr.app.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import com.gameocr.app.trigger.GameOcrAccessibilityService
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/** Full-display bitmap only: region, rotation and masks remain in CaptureService. */
class AccessibilityScreenshotter : Screenshotter {
    private val released = AtomicBoolean(false)
    override val isReady: Boolean
        get() = !released.get() && GameOcrAccessibilityService.isScreenshotReady()

    override suspend fun capture(): Bitmap? = requestMutex.withLock {
        if (!isReady) {
            Timber.w("[a11y-capture] service disconnected or screenshot capability unavailable")
            return@withLock null
        }
        delay(pacing.delayBeforeRequest(SystemClock.uptimeMillis()))
        if (!isReady) return@withLock null
        pacing.requestStarted(SystemClock.uptimeMillis())
        val unconsumed = AtomicReference<Bitmap?>(null)
        try {
            val frame = withTimeoutOrNull(5_000L) {
                withContext(Dispatchers.Default) { capturePlatformScreenshot(unconsumed) }
            }
            if (frame == null || released.get()) {
                Timber.w("[a11y-capture] no frame (failure, disconnect or timeout)")
                null
            } else {
                unconsumed.set(null)
                frame
            }
        } finally {
            // Also covers cancellation during the withContext/timeout result handoff.
            unconsumed.getAndSet(null)?.recycle()
        }
    }

    private suspend fun capturePlatformScreenshot(unconsumed: AtomicReference<Bitmap?>): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val service = GameOcrAccessibilityService.screenshotServiceOrNull() ?: return null
        return suspendCancellableCoroutine { continuation ->
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, callbackExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            var hardwareBitmap: Bitmap? = null
                            var bitmap: Bitmap? = null
                            try {
                                if (continuation.isActive && !released.get() &&
                                    GameOcrAccessibilityService.screenshotServiceOrNull() === service) {
                                    hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                    unconsumed.set(bitmap)
                                }
                            } catch (error: Exception) {
                                Timber.w(error, "[a11y-capture] bitmap conversion failed")
                            } finally {
                                hardwareBitmap?.recycle()
                                buffer.close()
                            }
                            if (!continuation.isActive || released.get()) {
                                bitmap?.recycle()
                                if (continuation.isActive) continuation.resume(null)
                            } else {
                                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                                continuation.resume(bitmap, onCancellation = { _, unclaimed, _ -> unclaimed?.recycle() })
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Timber.w("[a11y-capture] platform failure code=%d", errorCode)
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
            } catch (error: Exception) {
                Timber.w(error, "[a11y-capture] request failed")
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    override fun release() { released.set(true) }

    companion object {
        private val requestMutex = Mutex()
        private val pacing = AccessibilityScreenshotPacing()
        private val callbackExecutor = Executor { command ->
            Dispatchers.Default.dispatch(kotlin.coroutines.EmptyCoroutineContext, command)
        }
    }
}
