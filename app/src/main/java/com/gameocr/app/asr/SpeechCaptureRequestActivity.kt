package com.gameocr.app.asr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.gameocr.app.service.CaptureService
import timber.log.Timber

/**
 * Transparent host for the system prompts speech recognition needs: the recording permission
 * (playback capture requires it, although the microphone is never used) and, when the running
 * capture session has no MediaProjection, a new projection consent. The overlay ball cannot show
 * either prompt itself. Every outcome is reported back to [CaptureService]; cancellation leaves
 * nothing running.
 */
class SpeechCaptureRequestActivity : ComponentActivity() {
    private var reported = false

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) finishWith(SpeechConsentResult.AUDIO_DENIED) else requestProjectionOrFinish()
    }

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            finishWith(SpeechConsentResult.GRANTED, result.resultCode, data)
        } else {
            finishWith(SpeechConsentResult.PROJECTION_CANCELED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The ActivityResultRegistry restores an outstanding prompt after recreation.
        if (savedInstanceState != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestProjectionOrFinish()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestProjectionOrFinish() {
        if (!intent.getBooleanExtra(EXTRA_NEEDS_PROJECTION, true)) {
            finishWith(SpeechConsentResult.GRANTED)
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Same whole-display request as screenshot capture: no single-app choice on Android 14+.
        projection.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
        )
    }

    private fun finishWith(result: SpeechConsentResult, resultCode: Int = 0, data: Intent? = null) {
        if (reported) return
        reported = true
        Timber.i("[asr] consent result=%s", result)
        runCatching {
            startService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_SPEECH_CONSENT
                putExtra(CaptureService.EXTRA_SPEECH_CONSENT, result.name)
                if (data != null) {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                }
            })
        }.onFailure { Timber.w(it, "[asr] could not deliver consent result") }
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    companion object {
        private const val EXTRA_NEEDS_PROJECTION = "com.gameocr.app.asr.NEEDS_PROJECTION"

        fun intent(context: Context, needsProjection: Boolean): Intent =
            Intent(context, SpeechCaptureRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_NEEDS_PROJECTION, needsProjection)
            }
    }
}

enum class SpeechConsentResult {
    GRANTED,
    AUDIO_DENIED,
    PROJECTION_CANCELED,
}
