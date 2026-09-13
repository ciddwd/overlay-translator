package com.gameocr.app.trigger

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        connectedInstance = this
        connection.value = true
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
        if (connectedInstance === this) {
            connectedInstance = null
            connection.value = false
        }
        mainHandler.removeCallbacks(triggerRunnable)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val COMBO_HOLD_MS = 300L

        @Volatile
        private var connectedInstance: GameOcrAccessibilityService? = null
        private val connection = MutableStateFlow(false)
        val connected = connection.asStateFlow()

        fun isConnected(): Boolean = connectedInstance != null

        fun isScreenshotReady(): Boolean = screenshotServiceOrNull() != null

        fun screenshotServiceOrNull(): GameOcrAccessibilityService? {
            if (Build.VERSION.SDK_INT < 30) return null
            return connectedInstance?.takeIf {
                (it.serviceInfo?.capabilities ?: 0) and
                    AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
            }
        }

        fun captureFocusedInput(): FocusedInputCaptureResult =
            connectedInstance?.captureFocusedInputInternal()
                ?: FocusedInputCaptureResult.Error(FocusedInputReadError.SERVICE_UNAVAILABLE)

        fun replaceFocusedInput(
            original: FocusedInputDescriptor,
            replacement: String,
        ): FocusedInputReplaceResult = connectedInstance
            ?.replaceFocusedInputInternal(original, replacement)
            ?: FocusedInputReplaceResult.SERVICE_UNAVAILABLE

        fun verifyFocusedInput(
            original: FocusedInputDescriptor,
            expectedText: String,
        ): Boolean = connectedInstance
            ?.verifyFocusedInputInternal(original, expectedText)
            ?: false
    }

    private fun captureFocusedInputInternal(): FocusedInputCaptureResult = withFocusedNode(
        onMissing = {
            FocusedInputCaptureResult.Error(FocusedInputReadError.NO_FOCUSED_INPUT)
        },
    ) { node ->
        val descriptor = node.toDescriptor()
        when (FocusedInputPolicy.eligibility(descriptor)) {
            FocusedInputEligibility.ELIGIBLE -> FocusedInputCaptureResult.Ready(descriptor)
            FocusedInputEligibility.PASSWORD ->
                FocusedInputCaptureResult.Error(FocusedInputReadError.PASSWORD)
            FocusedInputEligibility.EMPTY ->
                FocusedInputCaptureResult.Error(FocusedInputReadError.EMPTY)
            FocusedInputEligibility.NOT_FOCUSED ->
                FocusedInputCaptureResult.Error(FocusedInputReadError.NO_FOCUSED_INPUT)
            FocusedInputEligibility.NOT_EDITABLE ->
                FocusedInputCaptureResult.Error(FocusedInputReadError.NOT_EDITABLE)
        }
    }

    private fun replaceFocusedInputInternal(
        original: FocusedInputDescriptor,
        replacement: String,
    ): FocusedInputReplaceResult = withFocusedNode(
        onMissing = { FocusedInputReplaceResult.TARGET_CHANGED },
    ) { node ->
        val current = node.toDescriptor()
        if (!FocusedInputPolicy.unchanged(original, current)) {
            return@withFocusedNode FocusedInputReplaceResult.TARGET_CHANGED
        }
        if (
            FocusedInputPolicy.eligibility(current) != FocusedInputEligibility.ELIGIBLE ||
            !current.supportsSetText
        ) {
            return@withFocusedNode FocusedInputReplaceResult.ACTION_REJECTED
        }
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                replacement,
            )
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            FocusedInputReplaceResult.ACTION_ACCEPTED
        } else {
            FocusedInputReplaceResult.ACTION_REJECTED
        }
    }

    private fun verifyFocusedInputInternal(
        original: FocusedInputDescriptor,
        expectedText: String,
    ): Boolean = withFocusedNode(onMissing = { false }) { node ->
        val current = node.toDescriptor()
        FocusedInputPolicy.replacementMatches(original, current, expectedText)
    }

    private inline fun <T> withFocusedNode(
        onMissing: () -> T,
        block: (AccessibilityNodeInfo) -> T,
    ): T {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Focused input access must run on the main thread"
        }
        val root = rootInActiveWindow ?: return onMissing()
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } finally {
            recycleCompat(root)
        } ?: return onMissing()
        return try {
            block(focused)
        } finally {
            recycleCompat(focused)
        }
    }

    private fun AccessibilityNodeInfo.toDescriptor(): FocusedInputDescriptor {
        val passwordField = isPassword
        val bounds = Rect().also(::getBoundsInScreen)
        val supportsSetText = actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        }
        return FocusedInputDescriptor(
            packageName = packageName?.toString().orEmpty(),
            windowId = windowId,
            viewId = viewIdResourceName,
            className = className?.toString(),
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            text = readNonPasswordInputText(passwordField) { text?.toString().orEmpty() },
            focused = isFocused,
            editable = isEditable,
            supportsSetText = supportsSetText,
            password = passwordField,
        )
    }

    @Suppress("DEPRECATION")
    private fun recycleCompat(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }
}

enum class FocusedInputReadError {
    SERVICE_UNAVAILABLE,
    NO_FOCUSED_INPUT,
    PASSWORD,
    NOT_EDITABLE,
    EMPTY,
}

sealed interface FocusedInputCaptureResult {
    data class Ready(val descriptor: FocusedInputDescriptor) : FocusedInputCaptureResult
    data class Error(val reason: FocusedInputReadError) : FocusedInputCaptureResult
}

enum class FocusedInputReplaceResult {
    ACTION_ACCEPTED,
    TARGET_CHANGED,
    ACTION_REJECTED,
    SERVICE_UNAVAILABLE,
}
