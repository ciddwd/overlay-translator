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
import com.gameocr.app.ui.openOverlayPermissionSettings
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** Shared transparent launch host, isolated from home so a tile tap preserves the foreground app. */
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
        }
        finishRequest()
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
                        startActivity(Intent(this@CaptureStartRequestActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finishRequest()
                    }
                    CaptureStartStage.FINISHED -> finishRequest()
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

        /** [modeOverride]: the method the home one-button setup resolved; used for this launch only. */
        fun newIntent(context: Context, modeOverride: CaptureStartMode? = null): Intent =
            Intent(context, CaptureStartRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                modeOverride?.let { putExtra(EXTRA_MODE_OVERRIDE, it.name) }
            }
    }
}

enum class CaptureStartStage { WAITING, PROJECTION, OVERLAY_PERMISSION, ACCESSIBILITY_SETTINGS, SETUP, FINISHED }

@HiltViewModel
class CaptureStartViewModel @Inject constructor(
    private val coordinator: CaptureStartCoordinator,
    private val savedState: SavedStateHandle,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val ownsGate = coordinator.gate.acquire()
    private val currentStage = MutableStateFlow(CaptureStartStage.WAITING)
    val stage = currentStage.asStateFlow()
    private var projectionRequested = savedState.get<Boolean>("projectionRequested") == true

    init {
        viewModelScope.launch {
            if (!ownsGate) {
                currentStage.value = CaptureStartStage.FINISHED
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
                            coordinator.start(decision.backend)
                            currentStage.value = CaptureStartStage.FINISHED
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
        try {
            coordinator.start(CaptureBackend.MEDIA_PROJECTION, code, data)
        } catch (error: Exception) {
            Timber.w(error, "[capture-start] projection startup failed")
        }
    }

    override fun onCleared() {
        if (ownsGate) coordinator.gate.release()
    }
}
