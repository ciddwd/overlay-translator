package com.gameocr.app.trigger

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.appcontext.ForegroundAppResolver
import com.gameocr.app.service.CaptureService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class GameOcrAccessibilityService : AccessibilityService() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var foregroundAppResolver: ForegroundAppResolver

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var volumeTriggerEnabled = false

    private var volumeUpDown = false
    private var volumeDownDown = false
    private var comboLatched = false

    private val triggerRunnable = Runnable {
        if (comboLatched) {
            Timber.i("A11y combo fired: vol+ vol- long-press ${COMBO_HOLD_MS}ms")
            triggerCapture()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope.launch {
            settingsRepository.settings.collect { settings ->
                volumeTriggerEnabled = settings.a11yVolumeTrigger
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundAppResolver.recordAccessibilityPackage(event.packageName)
        }
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacks(triggerRunnable)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!volumeTriggerEnabled) return false
        val isVolUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isVolDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolUp && !isVolDown) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    if (isVolUp) volumeUpDown = true
                    if (isVolDown) volumeDownDown = true
                    if (!comboLatched && volumeUpDown && volumeDownDown) {
                        comboLatched = true
                        mainHandler.removeCallbacks(triggerRunnable)
                        mainHandler.postDelayed(triggerRunnable, COMBO_HOLD_MS)
                    }
                }
                return comboLatched
            }
            KeyEvent.ACTION_UP -> {
                if (isVolUp) volumeUpDown = false
                if (isVolDown) volumeDownDown = false
                val wasLatched = comboLatched
                if (!volumeUpDown && !volumeDownDown) {
                    comboLatched = false
                    mainHandler.removeCallbacks(triggerRunnable)
                }
                return wasLatched
            }
        }
        return false
    }

    private fun triggerCapture() {
        val svc = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_TRIGGER_ONCE
        }
        startService(svc)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(triggerRunnable)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val COMBO_HOLD_MS = 300L
    }
}
