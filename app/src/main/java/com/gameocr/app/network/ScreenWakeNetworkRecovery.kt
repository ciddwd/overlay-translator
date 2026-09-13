package com.gameocr.app.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.gameocr.app.util.RuntimePerformanceDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Drops only idle OkHttp connections after the screen wakes. A socket kept in the pool while an
 * aggressive ROM suspends networking may otherwise look reusable until the first write/read times
 * out. Active HTTP calls are not evicted by OkHttp's ConnectionPool.evictAll().
 */
@Singleton
class ScreenWakeNetworkRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val performanceDiagnostics: RuntimePerformanceDiagnostics,
) {
    private val lock = Any()
    private var registered = false
    private var state = ScreenWakeRecoveryState()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onScreenPowerEvent(intent?.action.toScreenPowerEvent())
        }
    }

    fun start() = synchronized(lock) {
        if (registered) return
        val now = SystemClock.elapsedRealtime()
        val powerManager = context.getSystemService(PowerManager::class.java)
        state = ScreenWakeRecoveryPolicy.initialState(
            isInteractive = powerManager?.isInteractive != false,
            nowElapsedMs = now,
        )
        if (state.recoveryPending) performanceDiagnostics.onScreenOff()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun stop() = synchronized(lock) {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { Timber.w(it, "Failed to unregister screen wake network recovery") }
        registered = false
        state = ScreenWakeRecoveryState()
        performanceDiagnostics.resetWakeContext()
    }

    private fun onScreenPowerEvent(event: ScreenPowerEvent) = synchronized(lock) {
        if (!registered) return
        if (event == ScreenPowerEvent.SCREEN_OFF) performanceDiagnostics.onScreenOff()
        val decision = ScreenWakeRecoveryPolicy.transition(
            state = state,
            event = event,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        state = decision.state
        if (!decision.evictIdleConnections) return

        val idleConnections = httpClient.connectionPool.idleConnectionCount()
        httpClient.connectionPool.evictAll()
        performanceDiagnostics.onScreenWake(
            screenOffDurationMs = decision.screenOffDurationMs,
            idleConnectionsCleared = idleConnections,
        )
        val message = buildString {
            append("[network-wake] cleared idle cloud connections=")
            append(idleConnections)
            decision.screenOffDurationMs?.let {
                append(" screenOffMs=")
                append(it)
            }
        }
        Timber.tag(NETWORK_PERF_TAG).i(message)
    }

    private fun String?.toScreenPowerEvent(): ScreenPowerEvent = when (this) {
        Intent.ACTION_SCREEN_OFF -> ScreenPowerEvent.SCREEN_OFF
        Intent.ACTION_SCREEN_ON -> ScreenPowerEvent.SCREEN_ON
        Intent.ACTION_USER_PRESENT -> ScreenPowerEvent.USER_PRESENT
        else -> ScreenPowerEvent.OTHER
    }
}

internal const val NETWORK_PERF_TAG = "NetworkPerf"
