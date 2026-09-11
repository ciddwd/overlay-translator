package com.gameocr.app.tile

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import android.annotation.SuppressLint
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.ContextCompat
import com.gameocr.app.R
import com.gameocr.app.capture.MediaProjectionRequestActivity
import com.gameocr.app.onboarding.OnboardingPrefs
import com.gameocr.app.service.CaptureService
import com.gameocr.app.service.CaptureServiceState
import com.gameocr.app.shizuku.ShizukuCapabilities
import com.gameocr.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

/** 快捷设置磁贴（issue #23）：只做 Android 胶水，路由判定全在纯策略里（例外只有失败兜底），好让上不了真机的接线仍能被 `*WiringTest` 锁住。 */
@AndroidEntryPoint
class CaptureTileService : TileService() {

    @Inject lateinit var shizukuCapabilities: ShizukuCapabilities

    /** 上次被接受的启动时刻。只在启动侧写入；挡住停止会让服务关不掉 —— 理由见 [shouldAcceptTileTrigger]。 */
    private var lastAcceptedStartAtMs: Long? = null

    private val watchHandler = Handler(Looper.getMainLooper())

    /** 点击后仍在等结果的轮询；磁贴被销毁时要撤掉，否则会空转到窗口结束。 */
    private var watchRunnable: Runnable? = null

    /** 服务可能已在运行：不立刻渲染的话，系统给的默认 INACTIVE 会一直显示到下次点击。 */
    override fun onTileAdded() {
        super.onTileAdded()
        renderTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        renderTileState()
    }

    override fun onDestroy() {
        watchRunnable?.let(watchHandler::removeCallbacks)
        watchRunnable = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val decision = decideCaptureStartRoute(
            CaptureStartRouteInput(
                setupCompleted = OnboardingPrefs.isCompleted(this),
                // 必须回读校验，不能相信自己刚做的权限写入（appops 写入 ≠ 权限生效，因 OEM 而异）。
                canDrawOverlay = Settings.canDrawOverlays(this),
                serviceRunning = CaptureServiceState.running.value,
                isLocked = isLocked(),
                preferShizuku = preferShizuku(),
            )
        )
        Timber.i("tile click → route=%s useShizuku=%s", decision.route, decision.useShizuku)

        var startedDirectly = false
        when (decision.route) {
            CaptureStartRoute.STOP -> startService(CaptureService.stopIntent(this))

            CaptureStartRoute.DIRECT_FGS ->
                if (acceptStart()) startedDirectly = startCaptureDirect(decision.useShizuku)

            CaptureStartRoute.TRAMPOLINE -> if (acceptStart()) launchProjectionGateway()

            CaptureStartRoute.NEEDS_OVERLAY_PERMISSION -> openOverlayPermissionSettings()

            CaptureStartRoute.BLOCKED_NEED_UNLOCK -> toast(R.string.tile_toast_unlock_first)

            CaptureStartRoute.UNAVAILABLE -> openMainApp()
        }
        resolveTileClickState(decision.route, startedDirectly)?.let(::applyTileState)
        if (routeStartsCaptureAttempt(decision.route)) watchStartOutcome()
    }

    /** 直连在 Android 12–14 未实测，失败必须回落授权窗，否则用户只看到「点了没反应」。返回值表示是否真的走上了直连。 */
    private fun startCaptureDirect(useShizuku: Boolean): Boolean {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_USE_SHIZUKU, useShizuku)
        }
        return try {
            ContextCompat.startForegroundService(this, intent)
            true
        } catch (t: Throwable) {
            Timber.w(t, "tile direct FGS rejected — falling back to MediaProjection gateway")
            launchProjectionGateway()
            false
        }
    }

    /** 经 [MediaProjectionRequestActivity] 启动：由它弹系统授权框，拿到 token 后自己起服务。 */
    private fun launchProjectionGateway() {
        startActivityFromTile(MediaProjectionRequestActivity.newIntent(this))
    }

    private fun openOverlayPermissionSettings() {
        toast(R.string.tile_toast_overlay_required)
        startActivityFromTile(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun openMainApp() {
        startActivityFromTile(Intent(this, MainActivity::class.java))
    }

    private fun renderTileState() {
        val running = CaptureServiceState.running.value
        val setupDone = OnboardingPrefs.isCompleted(this)
        applyTileState(
            resolveCaptureTileState(setupCompleted = setupDone, serviceRunning = running)
        )
    }

    private fun applyTileState(state: CaptureTileState) {
        val tile = qsTile ?: run {
            Timber.i("tile render skipped: qsTile is null")
            return
        }
        tile.state = when (state) {
            CaptureTileState.ACTIVE -> Tile.STATE_ACTIVE
            CaptureTileState.INACTIVE -> Tile.STATE_INACTIVE
            CaptureTileState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
        }
        tile.label = getString(R.string.tile_capture_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_capture)
        tile.updateTile()
        Timber.i("tile state applied → %s", state)
    }

    /** 磁贴是 SystemUI 的界面，普通 `startActivity` 会被后台启动限制静默拦下（无异常）。 */
    private fun startActivityFromTile(target: Intent) {
        val launch = Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launchNeedsPendingIntent(Build.VERSION.SDK_INT)) {
            startActivityAndCollapse(pendingIntentFor(launch))
        } else {
            // API < 34 没有 PendingIntent 重载，只能用这个；它只在 targetSdk ≥ 34 时才会抛
            // UnsupportedOperationException，而本分支已由 `launchNeedsPendingIntent` 限定在 34 以下。
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(launch)
        }
    }

    private fun pendingIntentFor(launch: Intent): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // targetSdk ≥ 35 起创建方默认不再委派 BAL 权限，必须显式 opt-in，否则启动被静默拦下。
        return if (launchNeedsCreatorOptIn(Build.VERSION.SDK_INT)) {
            val options = ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            PendingIntent.getActivity(
                this,
                TILE_LAUNCH_REQUEST_CODE,
                launch,
                flags,
                options.toBundle(),
            )
        } else {
            PendingIntent.getActivity(this, TILE_LAUNCH_REQUEST_CODE, launch, flags)
        }
    }

    /** 把策略结论翻译成 lint 能读懂的 SDK 断言（lint 不认间接判断，会误报 `NewApi`）。 */
    @ChecksSdkIntAtLeast(parameter = 0, api = TILE_PENDING_INTENT_MIN_SDK)
    private fun launchNeedsPendingIntent(sdkInt: Int): Boolean =
        resolveTileLaunchApi(sdkInt) == TileLaunchApi.PENDING_INTENT

    /** 同上：`setPendingIntentCreatorBackgroundActivityStartMode` 需 API 34，靠这个注解让 lint 认账。 */
    @ChecksSdkIntAtLeast(parameter = 0, api = PENDING_INTENT_CREATOR_OPT_IN_MIN_SDK)
    private fun launchNeedsCreatorOptIn(sdkInt: Int): Boolean =
        needsPendingIntentCreatorOptIn(sdkInt)

    /** 去抖：接受则记下时刻。只在「启动」侧调用，停止侧永不调用。 */
    private fun acceptStart(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val accepted = shouldAcceptTileTrigger(lastAcceptedStartAtMs, now)
        if (accepted) lastAcceptedStartAtMs = now
        return accepted
    }

    /** 冷启时特权还在异步校验、读到的不是 READY，就走去授权窗 —— 宁可多弹一次框，也不赌一个跑不动 `screencap` 的 Shizuku。 */
    private fun preferShizuku(): Boolean = try {
        shizukuCapabilities.availability(this) == ShizukuCapabilities.Availability.READY
    } catch (t: Throwable) {
        Timber.w(t, "tile shizuku availability probe failed — falling back to MediaProjection")
        false
    }

    /**
     * 有界轮询启动结果：超时什么都不写（保留乐观值，理由见 [resolveTilePostClickState]），窗口须盖住
     * 服务最坏结算耗时，且只在结论变化时写，以免逐帧白打 SystemUI binder。
     */
    private fun watchStartOutcome() {
        val stoppedAtClick = CaptureServiceState.stoppedWithoutRunning.value
        var remaining = TILE_START_WATCH_ATTEMPTS
        var lastWritten: CaptureTileState? = null
        val tick = object : Runnable {
            override fun run() {
                val verdict = resolveTilePostClickState(
                    serviceRunning = CaptureServiceState.running.value,
                    stoppedWithoutRunningSinceClick =
                        CaptureServiceState.stoppedWithoutRunning.value > stoppedAtClick,
                )
                if (verdict != null && verdict != lastWritten) {
                    applyTileState(verdict)
                    lastWritten = verdict
                }
                if (--remaining > 0) {
                    watchHandler.postDelayed(this, TILE_START_WATCH_INTERVAL_MS)
                } else {
                    watchRunnable = null
                }
            }
        }
        watchRunnable?.let(watchHandler::removeCallbacks)
        watchRunnable = tick
        watchHandler.postDelayed(tick, TILE_START_WATCH_INTERVAL_MS)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TILE_LAUNCH_REQUEST_CODE = 0x7A11

        /**
         * 轮询窗口 = 12s。必须盖住服务最坏情况的结算耗时：Shizuku dry-run 失败后服务还要等 8.5s
         * 让用户读完错误条才 `stopSelf()`（`CaptureService`），窗口短于它就永远看不到那次停止，
         * 磁贴在面板开着期间会一直停在 ACTIVE。
         */
        const val TILE_START_WATCH_ATTEMPTS = 48
        const val TILE_START_WATCH_INTERVAL_MS = 250L
    }
}
