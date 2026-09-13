package com.gameocr.app.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.gameocr.app.service.CaptureService
import com.gameocr.app.service.CaptureServiceState
import com.gameocr.app.R
import com.gameocr.app.data.LogRepository
import com.gameocr.app.shizuku.AppPermissionCoordinator
import com.gameocr.app.shizuku.ShizukuManager
import com.gameocr.app.shizuku.ShizukuCapabilities
import com.gameocr.app.trigger.AccessibilityServiceStatus
import com.gameocr.app.trigger.GameOcrAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import timber.log.Timber

internal sealed interface CaptureStartDecision {
    data object AlreadyRunning : CaptureStartDecision
    data object OpenSetup : CaptureStartDecision
    data object OpenOverlaySettings : CaptureStartDecision
    data class Ready(val backend: CaptureBackend) : CaptureStartDecision
}

/** The ONLY startup policy for home, tile and future entry points. Never called on capture failure. */
@Singleton
class CaptureStartCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuManager,
    private val permissions: AppPermissionCoordinator,
    private val capabilities: ShizukuCapabilities,
    private val logs: LogRepository,
) {
    internal val gate = CaptureStartGate()

    internal suspend fun prepare(): CaptureStartDecision {
        if (CaptureServiceState.running.value) {
            return CaptureStartDecision.AlreadyRunning
        }
        val preference = CaptureStartPreference.mode.value
        // Resolve initial asynchronous readiness before choosing a backend, not from a stale UI snapshot.
        if (preference != CaptureStartMode.SYSTEM) shizuku.verifyShellPrivilegeAsync()
        val useShizuku = resolveCaptureStartMode(preference, capabilities.availability(context)) == CaptureStartMode.SHIZUKU
        if (useShizuku && !shizuku.ensureReady()) return CaptureStartDecision.OpenSetup
        val permissionState = permissions.currentState()
        if (shouldConfigureCapturePermissions(
                overlayPermissionGranted = permissionState.overlayGranted,
                accessibilityReady = permissionState.accessibilityReady,
                useShizuku = useShizuku,
                availability = capabilities.availability(context),
            )) {
            if (useShizuku) permissions.configure() else permissions.configureIfReady()
        }
        if (!Settings.canDrawOverlays(context)) return CaptureStartDecision.OpenOverlaySettings
        if (CaptureServiceState.running.value) return CaptureStartDecision.AlreadyRunning
        if (useShizuku) return CaptureStartDecision.Ready(CaptureBackend.SHIZUKU)
        if (Build.VERSION.SDK_INT >= 30 && AccessibilityServiceStatus.isEnabled(context)) {
            repeat(15) {
                if (GameOcrAccessibilityService.isScreenshotReady()) {
                    return CaptureStartDecision.Ready(CaptureBackend.ACCESSIBILITY)
                }
                delay(100)
            }
        }
        return CaptureStartDecision.Ready(systemCaptureBackend(Build.VERSION.SDK_INT,
            GameOcrAccessibilityService.isConnected(), GameOcrAccessibilityService.isScreenshotReady()))
    }

    internal fun start(backend: CaptureBackend, resultCode: Int = 0, data: Intent? = null) {
        if (CaptureServiceState.running.value) return
        require(backend != CaptureBackend.MEDIA_PROJECTION || data != null)
        Timber.i("[capture-start] backend=%s", backend)
        try {
            ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_CAPTURE_BACKEND, backend.name)
                if (backend == CaptureBackend.MEDIA_PROJECTION) {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                }
            })
        } catch (error: Exception) {
            logs.error(LogRepository.Category.CAPTURE, context.getString(R.string.log_msg_capture_failed), error)
            showStartFailure()
        }
    }

    private fun showStartFailure() {
        Toast.makeText(context, R.string.toast_capture_failed, Toast.LENGTH_LONG).show()
    }
}
