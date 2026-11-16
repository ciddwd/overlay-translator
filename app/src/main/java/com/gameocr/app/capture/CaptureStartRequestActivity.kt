package com.gameocr.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.gameocr.app.ui.MainActivity
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.service.CaptureServiceState
import com.gameocr.app.ui.openOverlayPermissionSettings
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Shared transparent launch host. Only Quick Settings needs a separate task. */
@AndroidEntryPoint
class CaptureStartRequestActivity : ComponentActivity() {
    private val viewModel: CaptureStartViewModel by viewModels()

    private val mpm by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Timber.i("MediaProjection granted")
            viewModel.projectionGranted(result.resultCode, result.data!!)
        } else {
            Timber.w("MediaProjection denied")
            finishRequest()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            viewModel.stage.collect { stage ->
                when (stage) {
                    CaptureStartStage.WAITING -> Unit
                    CaptureStartStage.PROJECTION -> if (viewModel.consumeProjectionRequest()) {
                        val fullScreen = viewModel.shouldShareEntireScreen()
                        val intent = if (Build.VERSION.SDK_INT >= 34 && fullScreen) {
                            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                        } else {
                            mpm.createScreenCaptureIntent()
                        }
                        launcher.launch(intent)
                    }
                    CaptureStartStage.OVERLAY_PERMISSION -> {
                        openOverlayPermissionSettings(this@CaptureStartRequestActivity)
                        finishRequest()
                    }
                    CaptureStartStage.ACCESSIBILITY_SETTINGS -> {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        finishRequest()
                    }
                    CaptureStartStage.SETUP -> {
                        if (isTaskRoot) {
                            startActivity(Intent(this@CaptureStartRequestActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        }
                        finishRequest()
                    }
                    CaptureStartStage.FINISHED -> finishRequest()
                    CaptureStartStage.CLOSE_PAGE -> finish()
                }
            }
        }
    }

    private fun finishRequest() {
        // Remove only this temporary task; never clear a caller's existing activity stack.
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    companion object {
        internal const val EXTRA_MODE_OVERRIDE = "com.gameocr.app.capture.MODE_OVERRIDE"
        internal const val EXTRA_FROM_QUICK_SETTINGS = "com.gameocr.app.capture.FROM_QUICK_SETTINGS"

        /** [modeOverride]: the method the home one-button setup resolved; used for this launch only. */
        fun newIntent(
            context: Context,
            modeOverride: CaptureStartMode? = null,
            fromQuickSettings: Boolean = false,
        ): Intent =
            Intent(context, CaptureStartRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                if (fromQuickSettings) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_FROM_QUICK_SETTINGS, true)
                }
                modeOverride?.let { putExtra(EXTRA_MODE_OVERRIDE, it.name) }
            }
    }
}

enum class CaptureStartStage { WAITING, PROJECTION, OVERLAY_PERMISSION, ACCESSIBILITY_SETTINGS, SETUP, FINISHED, CLOSE_PAGE }

@HiltViewModel
class CaptureStartViewModel @Inject constructor(
    private val coordinator: CaptureStartCoordinator,
    private val savedState: SavedStateHandle,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val ownsGate = coordinator.gate.acquire()
    private val currentStage = MutableStateFlow(CaptureStartStage.WAITING)
    val stage = currentStage.asStateFlow()
    private val fromQuickSettings = savedState.get<Boolean>(CaptureStartRequestActivity.EXTRA_FROM_QUICK_SETTINGS) == true
    private var projectionRequested = savedState.get<Boolean>("projectionRequested") == true

    init {
        viewModelScope.launch {
            if (!ownsGate) {
                // Another request may still be starting the foreground service.
                currentStage.value = CaptureStartStage.CLOSE_PAGE
            } else if (projectionRequested) {
                // The ActivityResultRegistry restores the outstanding consent request after recreation.
                currentStage.value = CaptureStartStage.PROJECTION
            } else try {
                val modeOverride = savedState.get<String>(CaptureStartRequestActivity.EXTRA_MODE_OVERRIDE)
                    ?.let { name -> CaptureStartMode.entries.firstOrNull { it.name == name } }
                when (val decision = coordinator.prepare(modeOverride)) {
                    CaptureStartDecision.AlreadyRunning -> currentStage.value = CaptureStartStage.FINISHED
                    CaptureStartDecision.OpenSetup -> currentStage.value = CaptureStartStage.SETUP
                    CaptureStartDecision.OpenOverlaySettings -> currentStage.value = CaptureStartStage.OVERLAY_PERMISSION
                    CaptureStartDecision.OpenAccessibilitySettings ->
                        currentStage.value = CaptureStartStage.ACCESSIBILITY_SETTINGS
                    is CaptureStartDecision.Ready -> {
                        if (decision.backend == CaptureBackend.MEDIA_PROJECTION) {
                            currentStage.value = CaptureStartStage.PROJECTION
                        } else {
                            finishAfterStart(coordinator.start(decision.backend))
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "[capture-start] prepare failed")
                currentStage.value = CaptureStartStage.SETUP
            }
        }
    }

    fun consumeProjectionRequest(): Boolean {
        if (projectionRequested) return false
        projectionRequested = true
        savedState["projectionRequested"] = true
        return true
    }

    suspend fun shouldShareEntireScreen(): Boolean {
        val settings = settingsRepository.get()
        return shouldRequestEntireScreen(
            Build.VERSION.SDK_INT, settings.developerOptionsEnabled, settings.shareEntireScreen,
        )
    }

    fun projectionGranted(code: Int, data: Intent) {
        viewModelScope.launch {
            finishAfterStart(coordinator.start(CaptureBackend.MEDIA_PROJECTION, code, data))
        }
    }

    private suspend fun finishAfterStart(dispatched: Boolean) {
        if (!fromQuickSettings) {
            currentStage.value = CaptureStartStage.FINISHED
            return
        }

        // running becomes true only after CaptureService has entered the foreground.
        val running = dispatched && withTimeoutOrNull(5_000L) {
            CaptureServiceState.running.first { it }
        } == true
        // On failure or timeout, close only the page. Do not remove its task during startup.
        currentStage.value = if (running) CaptureStartStage.FINISHED else CaptureStartStage.CLOSE_PAGE
    }

    override fun onCleared() {
        if (ownsGate) coordinator.gate.release()
    }
}
