package com.gameocr.app.service

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gameocr.app.R
import com.gameocr.app.capture.CaptureStartRequestActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CaptureQuickSettingsTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            CaptureServiceState.running.collectLatest(::updateTileState)
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val action = resolveCaptureQuickSettingsAction(
            serviceRunning = CaptureServiceState.running.value,
        )
        if (action == CaptureQuickSettingsAction.STOP_SERVICE) {
            startService(CaptureService.stopIntent(this))
            return
        }
        val startAction = Runnable {
            // Unlock may have taken seconds. Never stop an independently started session here.
            if (!CaptureServiceState.running.value) performStartAction(action)
        }
        if (isLocked) {
            unlockAndRun(startAction)
        } else {
            startAction.run()
        }
    }

    private fun performStartAction(action: CaptureQuickSettingsAction) {
        when (action) {
            CaptureQuickSettingsAction.STOP_SERVICE -> Unit
            CaptureQuickSettingsAction.REQUEST_START ->
                launchActivity(CaptureStartRequestActivity.newIntent(this), CAPTURE_START_REQUEST_CODE)
        }
    }

    private fun launchActivity(intent: Intent, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun updateTileState(running: Boolean) {
        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.quick_settings_tile_label)
            updateTile()
        }
    }

    companion object {
        private const val CAPTURE_START_REQUEST_CODE = 0x5142
        private const val QUICK_SETTINGS_SETTINGS_ACTION = "android.settings.QUICK_SETTINGS_SETTINGS"

        fun requestAdd(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                val requested = runCatching {
                    statusBarManager.requestAddTileService(
                        ComponentName(context, CaptureQuickSettingsTileService::class.java),
                        context.getString(R.string.quick_settings_tile_label),
                        Icon.createWithResource(context, R.drawable.ic_menu_full_screen),
                        context.mainExecutor,
                    ) { }
                }.isSuccess
                if (requested) return
            }
            val opened = runCatching {
                context.startActivity(
                    Intent(QUICK_SETTINGS_SETTINGS_ACTION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            if (!opened) {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
