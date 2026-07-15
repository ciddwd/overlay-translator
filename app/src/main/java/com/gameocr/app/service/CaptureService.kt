package com.gameocr.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import com.gameocr.app.R
import com.gameocr.app.capture.CaptureCoordinateRelation
import com.gameocr.app.capture.CaptureRegion
import com.gameocr.app.capture.LoopFrameChangePolicy
import com.gameocr.app.capture.LoopFrameFingerprint
import com.gameocr.app.capture.LoopFrameFingerprintFactory
import com.gameocr.app.capture.LoopFramePreOcrDecision
import com.gameocr.app.capture.LoopFramePreOcrResult
import com.gameocr.app.capture.LoopFramePostOcrDecision
import com.gameocr.app.capture.LoopFrameStabilityDecision
import com.gameocr.app.capture.LoopFrameStabilityPolicy
import com.gameocr.app.capture.LoopFrameStabilityState
import com.gameocr.app.capture.LoopRoiStabilityDecision
import com.gameocr.app.capture.LoopRoiFallbackEvent
import com.gameocr.app.capture.LoopRoiFallbackPolicy
import com.gameocr.app.capture.LoopRoiStabilityPolicy
import com.gameocr.app.capture.LoopRoiStabilityState
import com.gameocr.app.capture.LoopRoiVisualFingerprintFactory
import com.gameocr.app.capture.LoopRoiCoordinatePolicy
import com.gameocr.app.capture.LoopTextRect
import com.gameocr.app.capture.LoopTextRoiCandidate
import com.gameocr.app.capture.LoopTextRoiPolicy
import com.gameocr.app.capture.LoopActiveResultDecision
import com.gameocr.app.capture.LoopIndicatorMode
import com.gameocr.app.capture.LoopRuntimePolicy
import com.gameocr.app.capture.MediaProjectionScreenshotter
import com.gameocr.app.capture.Screenshotter
import com.gameocr.app.capture.ShizukuScreenshotter
import com.gameocr.app.capture.diagnoseCaptureGeometry
import com.gameocr.app.shizuku.ShizukuCapabilities
import com.gameocr.app.data.LogRepository
import com.gameocr.app.data.LoopTriggerMode
import com.gameocr.app.data.LoopTextRegionMode
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OverlayFontManager
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.TranslationPreset
import com.gameocr.app.data.TranslationPresetCatalog
import com.gameocr.app.data.needsRawBitmap
import com.gameocr.app.data.Languages
import com.gameocr.app.ocr.BitmapPreprocessor
import com.gameocr.app.ocr.MangaOcrModelInstaller
import com.gameocr.app.ocr.OcrEngine
import com.gameocr.app.ocr.OrientationCoordinator
import com.gameocr.app.ocr.OrientationResult
import com.gameocr.app.ocr.OrientationRouting
import com.gameocr.app.ocr.PaddleTextLineOrientationClassifier
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.TextOrientation
import com.gameocr.app.ocr.TranslationOutputOrientationPolicy
import com.gameocr.app.ocr.findOcrResultQualityIssue
import com.gameocr.app.ocr.mapBlocksFromRotated180
import com.gameocr.app.ocr.orientationHintFromLayout
import com.gameocr.app.ocr.resolveTextBlockReadingOrientation
import com.gameocr.app.ocr.shouldRerunLowQualityChinesePaddleOcr
import com.gameocr.app.ocr.sortTextBlocksForReading
import com.gameocr.app.data.resolveTranslationOutputSettings
import com.gameocr.app.data.FloatingSkill
import com.gameocr.app.overlay.FloatingButtonManager
import com.gameocr.app.overlay.LanguageQuickSwitchOverlay
import com.gameocr.app.overlay.OverlayManager
import com.gameocr.app.overlay.PresetQuickSwitchOverlay
import com.gameocr.app.overlay.RegionPickerOverlay
import com.gameocr.app.overlay.TranslationBlockCopyOverlay
import com.gameocr.app.overlay.TranslationCardOverlay
import com.gameocr.app.overlay.WordSelectOverlay
import com.gameocr.app.ui.MainActivity
import com.gameocr.app.translate.TranslationException
import com.gameocr.app.translate.Translator
import com.gameocr.app.translate.WordHeuristic
import com.gameocr.app.translate.WordResult
import com.gameocr.app.translate.WordSelectTranslationCoordinator
import com.gameocr.app.translate.WordSelectTranslationStage
import com.gameocr.app.util.VerticalDiagnosticLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

internal fun allowsFrequentTextStabilityProbe(
    ocrEngine: OcrEngineKind,
    configuredEndToEnd: Boolean,
): Boolean {
    if (configuredEndToEnd) return false
    return when (ocrEngine) {
        OcrEngineKind.UMI_OCR,
        OcrEngineKind.LUNA_OCR,
        OcrEngineKind.ML_KIT_AUTO,
        OcrEngineKind.ML_KIT_LATIN,
        OcrEngineKind.ML_KIT_JAPANESE,
        OcrEngineKind.ML_KIT_CHINESE,
        OcrEngineKind.ML_KIT_KOREAN,
        OcrEngineKind.PADDLE_ONNX,
        OcrEngineKind.MANGA_OCR_JA -> true
        OcrEngineKind.BAIDU,
        OcrEngineKind.TENCENT,
        OcrEngineKind.YOUDAO,
        OcrEngineKind.PADDLE_AI_STUDIO -> false
    }
}

/**
 * 截屏前台服务：所有截屏 + OCR + 翻译 + 悬浮窗显示都在这里串。
 *
 * Android 14+ 要求 MediaProjection 必须 (1) 先 startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)，
 * (2) 之后才能拿 MediaProjectionManager.getMediaProjection(token)。本服务严格按这个顺序。
 */
@AndroidEntryPoint
class CaptureService : Service() {

    private data class PendingLoopRoiResult(
        val blocks: List<TextBlock>,
        val renderOrientation: TextOrientation,
        val effectiveEngine: OcrEngineKind,
        val roi: LoopTextRect,
        val stabilityState: LoopRoiStabilityState,
        val initialOcrElapsedMs: Long,
    )

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var overlayFontManager: OverlayFontManager
    @Inject lateinit var ocrEngine: OcrEngine
    @Inject lateinit var translator: Translator
    @Inject lateinit var shizukuCapabilities: ShizukuCapabilities
    @Inject lateinit var logRepository: LogRepository
    // 端侧 LLM 翻译共享层。设置里切走 LOCAL_* 引擎或 Service 销毁时主动 unload 释放 ~500 MB 内存。
    @Inject lateinit var llamaEngineHolder: com.gameocr.app.llm.LlamaEngineHolder
    // 文本方向自动判别 + 路由。仅在 settings.textOrientationAutoDetect = true 时启用。
    @Inject lateinit var orientationCoordinator: OrientationCoordinator
    // 用于路由层判断"manga-ocr 模型是否已下载"——未下载时 (VERTICAL_RTL, ja) 不会被路由到 manga
    @Inject lateinit var mangaOcrModelInstaller: MangaOcrModelInstaller

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureLock = Mutex()

    private var screenshotter: Screenshotter? = null
    private var projection: MediaProjection? = null
    private var floatingButton: FloatingButtonManager? = null
    private var overlay: OverlayManager? = null
    private var regionPicker: RegionPickerOverlay? = null
    private var languageQuickSwitch: LanguageQuickSwitchOverlay? = null
    private var presetQuickSwitch: PresetQuickSwitchOverlay? = null
    private var wordSelect: WordSelectOverlay? = null
    private var translationCard: TranslationCardOverlay? = null
    private var translationBlockCopyOverlay: TranslationBlockCopyOverlay? = null

    private var loopJob: Job? = null
    private var previousLoopFingerprint: LoopFrameFingerprint? = null
    private var previousLoopOcrText: String? = null
    private var loopFrameStabilityState = LoopFrameStabilityState()
    private var pendingLoopRoiResult: PendingLoopRoiResult? = null
    private var loopRoiTextFallbackActive: Boolean = false
    @Volatile private var loopTranslationInFlight = false
    @Volatile private var loopSessionId = 0L
    @Volatile private var lastLoopRuntimeLogState: String? = null
    // 订阅 SettingsRepository.settings flow，让设置页保存后所有显示项立即生效
    // （悬浮按钮大小、配色主题、字号、透明度、紧贴文位置 …）。原先只在 captureOnce
    // 时读 settings，导致用户必须停止/重启服务或触发一次截屏才能看到改动。
    private var settingsCollectJob: Job? = null
    @Volatile private var loopMode: Boolean = false
    private var captureSequence: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        VerticalDiagnosticLog.i(
            "service configurationChanged orientation=${newConfig.orientation.toDiagOrientation()} " +
                "display=${currentDisplayGeometry().toDiagString()} projection=${projectionDiagnosticSummary()}"
        )
        resizeProjectionForCurrentDisplay("serviceConfigurationChanged")
        // 屏幕方向变了把圆球 + 悬浮窗口 + 截屏区域都按比例重算位置。
        // captureRegion 走 SettingsRepository.rescaleCaptureRegionIfNeeded——
        // 它用 saved 元数据按当前屏幕尺寸自动算比例，比"上次 onConfigurationChanged 拿到的尺寸"更稳。
        floatingButton?.onConfigurationChanged()
        mainScope.launch { overlay?.onConfigurationChanged() }
        scope.launch {
            val dm = resources.displayMetrics
            settingsRepository.rescaleCaptureRegionIfNeeded(dm.widthPixels, dm.heightPixels)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopSelf()
            ACTION_TRIGGER_ONCE -> triggerOnce()
            ACTION_PICK_REGION -> showRegionPickerOverlay()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // Service 启动时主动 rescale 一次 captureRegion——如果用户在 service 没跑时旋转了屏幕，
        // 这里把 region 校正到当前屏幕方向。
        scope.launch {
            val dmInit = resources.displayMetrics
            settingsRepository.rescaleCaptureRegionIfNeeded(dmInit.widthPixels, dmInit.heightPixels)
        }
        // 如果已经启动过，先 cleanup 旧资源避免悬浮窗叠加 / 截屏链路泄漏。
        // 用户重复点"启动"按钮、或者切换 Shizuku ↔ MediaProjection 路径都走这条。
        cleanupCapture()

        // 先判断要走的截屏路径：用户启用 Shizuku 且就绪 → Shizuku；否则 → MediaProjection
        val useShizuku = intent.getBooleanExtra(EXTRA_USE_SHIZUKU, false) &&
            shizukuCapabilities.availability(this) == ShizukuCapabilities.Availability.READY

        // 前台服务：Android 14+ 必须显式传非零 type，否则 InvalidForegroundServiceTypeException。
        // MediaProjection 路径走 MEDIA_PROJECTION；Shizuku 路径走 SPECIAL_USE。
        val fgType = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
            useShizuku -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        // Android 14+ HyperOS/MIUI 上常见 race：MediaProjectionRequestActivity onActivityResult
        // 收到 RESULT_OK 后立即 startForegroundService，此时 `android:project_media` app-op
        // grant 尚未异步落地，startForeground 抛 SecurityException 闪退。
        // workaround：捕获异常后 postDelayed 重试一次，给 op 200ms 落地时间；仍失败再 stopSelf。
        if (!startForegroundCompat(fgType, intent)) {
            return
        }

        if (useShizuku) {
            screenshotter = ShizukuScreenshotter()
            Timber.i("CaptureService started with Shizuku path")
        } else {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (data == null) {
                Timber.w("MediaProjection result data is null")
                stopSelf()
                return
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)
            val mp = projection
            if (mp == null) {
                Timber.w("getMediaProjection returned null")
                stopSelf()
                return
            }
            screenshotter = MediaProjectionScreenshotter(this, mp)
            Timber.i("CaptureService started with MediaProjection path")
        }

        VerticalDiagnosticLog.i(
            "service capture path=${screenshotter?.javaClass?.simpleName ?: "null"} " +
                "display=${currentDisplayGeometry().toDiagString()} projection=${projectionDiagnosticSummary()}"
        )

        overlay = OverlayManager(
            context = this,
            settingsRepository = settingsRepository,
            ioScope = scope,
            onTranslationBlockDetailRequested = ::showTranslationBlockCopyPanel,
        )
        floatingButton = FloatingButtonManager(
            this,
            // 主球单击：按当前 skill 路由。FloatingButtonManager.skill 由 settings collect 同步保持最新；
            // 这里读 floatingButton?.skill 而不是构造时快照，保证用户从菜单切技能后立刻生效。
            onSingleTap = {
                when (floatingButton?.skill ?: FloatingSkill.FULL_SCREEN) {
                    FloatingSkill.FULL_SCREEN -> triggerOnce()
                    FloatingSkill.WORD_SELECT -> triggerWordSelect()
                    FloatingSkill.LOOP -> toggleLoopMode()
                }
            },
            onSwitchToLoop = { applyFloatingSkill(FloatingSkill.LOOP) },
            settingsRepository = settingsRepository,
            ioScope = scope
        ).also {
            // 截图区域调整：用悬浮窗版替代旧的 Activity 跳转——不再切走游戏 / 漫画，且重复
            // 点菜单也只弹一次（show 内部去重）。
            it.onMenuPickRegion = { showRegionPickerOverlay() }
            it.onMenuLanguagePair = { showLanguageQuickSwitchOverlay() }
            it.onMenuPresetSwitch = { showPresetQuickSwitchOverlay() }
            it.onMenuOpenSettings = {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_START_ROUTE, MainActivity.ROUTE_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
            it.onMenuOpenMainActivity = { startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            ) }
            // 主球技能切换：菜单点了「划词翻译 / 全屏翻译」时由 FloatingButtonManager 触发，
            // 这里负责持久化到 Settings + 弹个 info 提示告知新行为；图标切换由 FloatingButtonManager
            // 在 applySkillIcon 里同步。
            it.onSwitchSkill = { newSkill -> applyFloatingSkill(newSkill) }
        }
        // 异步读 settings 应用大小 + 还原上次松手位置后再 show，避免阻塞 startForeground 流程
        scope.launch {
            val s = settingsRepository.get()
            floatingButton?.sizeDp = s.floatingButtonSizeDp
            floatingButton?.initialX = s.floatingButtonX
            floatingButton?.initialY = s.floatingButtonY
            floatingButton?.snapToEdgeEnabled = s.floatingButtonSnapToEdge
            floatingButton?.autoDockEnabled = s.floatingButtonAutoDock
            floatingButton?.dockEdgeInsetPx = (s.floatingButtonDockInsetDp * resources.displayMetrics.density).toInt()
            floatingButton?.menuItemOrder = s.floatingMenuItemOrder
            floatingButton?.arcMenuPageSize = s.arcMenuPageSize
            floatingButton?.skill = s.floatingButtonSkill
            mainScope.launch { floatingButton?.show() }
        }

        // 订阅 settings 热更新：跳过首次 emit（避免和上面 show() 流程重叠初始化），之后任何
        // 改动都立即应用到 overlay 与悬浮按钮。一次性 collect，cleanupCapture 时取消。
        settingsCollectJob?.cancel()
        settingsCollectJob = scope.launch {
            var first = true
            var lastEngine: com.gameocr.app.data.TranslatorEngine? = null
            settingsRepository.settings.collect { s ->
                if (first) {
                    first = false
                    lastEngine = s.translatorEngine
                    return@collect
                }
                applyOverlayConfig(s)
                // 端侧 LLM 引擎切走时主动释放权重：避免 500MB+ 模型常驻内存抢 Bitmap / OCR 模型空间。
                val wasLocal = lastEngine?.name?.startsWith("LOCAL_") == true
                val isLocal = s.translatorEngine.name.startsWith("LOCAL_")
                if (wasLocal && !isLocal) {
                    scope.launch { runCatching { llamaEngineHolder.unload() } }
                }
                lastEngine = s.translatorEngine
            }
        }

        CaptureServiceState.setRunning(true)

        // Shizuku 路径 dry-run：即使 availability == READY，未通过 ADB / root 配对的 Shizuku 也会
        // 让 newProcess(screencap) 失败（exit=1）。立刻跑一次截屏，失败则用悬浮错误条引导用户改
        // 用 MediaProjection 并 stopSelf——比让他看到通用「截屏失败」反复试错好。
        if (useShizuku) {
            scope.launch {
                val shotter = screenshotter ?: return@launch
                val test = shotter.capture()
                if (test == null) {
                    Timber.w("Shizuku dry-run failed; stopping service")
                    logRepository.error(
                        LogRepository.Category.CAPTURE,
                        getString(R.string.log_msg_shizuku_dry_run_failed)
                    )
                    mainScope.launch {
                        overlay?.showErrorHint(
                            getString(R.string.toast_shizuku_dry_run_failed),
                            durationMs = 8000L
                        )
                    }
                    kotlinx.coroutines.delay(8500L)
                    stopSelf()
                } else {
                    test.recycle()
                }
            }
        }
    }

    private suspend fun prepareCleanCaptureFrame(
        hideFloatingButton: Boolean,
        keepLoading: Boolean = false
    ) {
        mainScope.launch {
            overlay?.clear(keepLoading = keepLoading)
            translationCard?.dismiss()
            translationBlockCopyOverlay?.dismiss()
            if (hideFloatingButton) floatingButton?.hide()
        }.join()
        delay(CAPTURE_CHROME_SETTLE_MS)
    }

    private fun restoreCaptureChrome(showLoading: Boolean, restoreFloatingButton: Boolean) {
        mainScope.launch {
            if (restoreFloatingButton) floatingButton?.show()
            if (showLoading) overlay?.showLoadingHint()
        }
    }

    /**
     * 包裹 [ServiceCompat.startForeground]，处理 Android 14+ HyperOS/MIUI 上的 `android:project_media`
     * app-op race：MediaProjectionRequestActivity 拿到 RESULT_OK 后 op grant 是异步的，立刻 startForeground
     * 可能在 op 还没落地时抛 `SecurityException`。
     *
     * 流程：
     *  1) 直接尝试 startForeground —— 多数 ROM 正常工作
     *  2) 抛 SecurityException → postDelayed 200ms 后重试一次（给 op 落地时间）
     *  3) 重试成功 → 重新跑一遍 handleStart（cleanupCapture 幂等，重入安全）
     *  4) 重试失败 → 弹 Toast 引导 + stopSelf
     *
     * @return true 代表 startForeground 同步成功，调用方可继续后续初始化；false 代表已进入异步
     *  重试模式，调用方应立刻 return（避免在前台未确立时初始化截屏 / 悬浮窗）。
     */
    private fun startForegroundCompat(fgType: Int, originalIntent: Intent): Boolean {
        val tryStart = {
            ServiceCompat.startForeground(
                this,
                CaptureNotification.NOTIF_ID,
                CaptureNotification.build(this),
                fgType
            )
        }
        return try {
            tryStart()
            true
        } catch (se: SecurityException) {
            Timber.w(se, "startForeground SecurityException; retry in 200ms")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    tryStart()
                    Timber.i("startForeground retry succeeded; rerunning handleStart")
                    handleStart(originalIntent)
                } catch (e2: SecurityException) {
                    Timber.e(e2, "startForeground retry also failed")
                    logRepository.error(
                        LogRepository.Category.CAPTURE,
                        "startForeground SecurityException after retry: ${e2.message}",
                        e2
                    )
                    stopSelf()
                }
            }, 200L)
            false
        }
    }

    private fun triggerOnce() {
        if (captureLock.isLocked) {
            Timber.i("Skip manual trigger because capture is already running")
            return
        }
        // MediaProjection captures the app's own overlay; capture a clean frame first,
        // then restore the floating button and loading indicator.
        scope.launch {
            val loadingShown = mainScope.async { overlay?.showLoadingHint() == true }.await()
            prepareCleanCaptureFrame(hideFloatingButton = true, keepLoading = loadingShown)
            captureOnce(
                showLoadingAfterScreenshot = !loadingShown,
                restoreFloatingButtonAfterScreenshot = true
            )
        }
    }

    /**
     * 划词翻译入口：先 hide 球，弹 WordSelectOverlay 让用户拖矩形，确认后截屏 → crop 该区域 →
     * OCR → 判定单词 → translate / translateWord → 弹 TranslationCardOverlay。
     *
     * 全程错误用 [OverlayManager.showErrorHint] 红条提示（跟主链路同链路），不弹 Toast。
     */
    private fun triggerWordSelect() {
        val ws = wordSelect ?: WordSelectOverlay(this).also { wordSelect = it }
        if (ws.isShown()) return
        mainScope.launch {
            floatingButton?.hide()
            ws.show(
                onTranslate = { rect ->
                    scope.launch {
                        prepareCleanCaptureFrame(hideFloatingButton = true)
                        runWordSelectPipeline(
                            rect,
                            restoreFloatingButtonAfterScreenshot = true
                        )
                    }
                },
                onCancel = {
                    mainScope.launch { floatingButton?.show() }
                }
            )
        }
    }

    /** 框选后真正跑流水线。screenshot → crop → OCR → translate → 弹卡片。 */
    private suspend fun runWordSelectPipeline(
        rect: android.graphics.Rect,
        restoreFloatingButtonAfterScreenshot: Boolean = false
    ) {
        if (!captureLock.tryLock()) {
            restoreCaptureChrome(
                showLoading = false,
                restoreFloatingButton = restoreFloatingButtonAfterScreenshot
            )
            mainScope.launch { overlay?.dismissLoading() }
            return
        }
        val diagId = ++captureSequence
        val perfStartedAt = SystemClock.elapsedRealtime()
        fun logWordSelectPerf(stage: String, details: String = "") {
            Timber.tag("WordSelectPerf").i(
                "capture=%d stage=%s totalMs=%d%s",
                diagId,
                stage,
                SystemClock.elapsedRealtime() - perfStartedAt,
                details.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(),
            )
        }
        logWordSelectPerf("started", "rect=${rect.width()}x${rect.height()}")
        var captureChromeRestored = false
        fun restoreCaptureChromeOnce(showLoading: Boolean) {
            if (captureChromeRestored) return
            captureChromeRestored = true
            restoreCaptureChrome(
                showLoading = showLoading,
                restoreFloatingButton = restoreFloatingButtonAfterScreenshot
            )
        }
        try {
            logVerticalDiag(diagId, "wordSelect start rect=${rect.toDiagString()}")
            val shotter = screenshotter ?: run {
                logVerticalDiag(diagId, "skip: screenshotter is null")
                restoreCaptureChromeOnce(showLoading = false)
                return
            }
            val full = shotter.capture()
            if (full == null) {
                restoreCaptureChromeOnce(showLoading = false)
                val msg = getString(R.string.toast_capture_failed)
                mainScope.launch { overlay?.showErrorHint(msg) }
                return
            }
            logWordSelectPerf("screenshot_ready", "frame=${full.width}x${full.height}")
            restoreCaptureChromeOnce(showLoading = false)
            val fullStats = sampleBitmapFrameStats(full)
            logVerticalDiag(
                diagId,
                "screenshot full=${full.width}x${full.height} stats=${fullStats.toDiagString()}"
            )
            logCaptureGeometry(diagId, "wordSelect", full)
            logBlankLikeFrame(diagId, "screenshot", fullStats)
            val settings = settingsRepository.get()
            // 用 word-select rect 裁剪，**不**走 settings.captureRegion——划词是一次性独立选区
            val cropped = try {
                cropRect(full, rect)
            } catch (t: Throwable) {
                full.recycle()
                throw t
            }
            full.recycle()
            if (cropped == null) {
                val msg = getString(R.string.word_card_no_text)
                mainScope.launch { overlay?.showErrorHint(msg) }
                return
            }
            logWordSelectPerf("crop_ready", "crop=${cropped.width}x${cropped.height}")
            dumpCaptureFrameForDebug(this, diagId, "word-select-crop", cropped)?.let { file ->
                logVerticalDiag(diagId, "debug crop dumped path=${file.absolutePath}")
                logRepository.info(
                    LogRepository.Category.CAPTURE,
                    getString(R.string.log_msg_capture_frame_dumped_format, file.name),
                    imagePath = file.absolutePath,
                )
            }
            val croppedStats = sampleBitmapFrameStats(cropped)
            logVerticalDiag(
                diagId,
                "wordSelect workBitmap=${cropped.width}x${cropped.height} " +
                    "rect=${rect.toDiagString()} stats=${croppedStats.toDiagString()}"
            )
            logBlankLikeFrame(diagId, "wordSelect workBitmap", croppedStats)
            val ocrStartedAt = System.currentTimeMillis()
            logWordSelectPerf("ocr_started", "engine=${settings.ocrEngine.name}")
            val ocrBlocks = try {
                ocrEngine.recognize(cropped, settings.ocrEngine)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                cropped.recycle()
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "Word-select OCR failed")
                logRepository.error(
                    LogRepository.Category.OCR,
                    getString(R.string.log_msg_ocr_failed_format, settings.ocrEngine.name),
                    t,
                    elapsedMs = elapsedSince(ocrStartedAt)
                )
                val msg = getString(
                    R.string.toast_word_select_ocr_failed_format,
                    settings.ocrEngine.name,
                    shortError(t)
                )
                mainScope.launch { overlay?.showErrorHint(msg) }
                cropped.recycle()
                return
            }
            logWordSelectPerf(
                "ocr_finished",
                "ocrMs=${elapsedSince(ocrStartedAt)} blocks=${ocrBlocks.size}",
            )
            cropped.recycle()
            logVerticalBlocks(diagId, "wordSelect rawBlocks engine=${settings.ocrEngine.name}", ocrBlocks)
            val orderedOcrBlocks = sortTextBlocksForReading(ocrBlocks)
            logVerticalBlocks(diagId, "wordSelect orderedBlocks", orderedOcrBlocks)
            val text = orderedOcrBlocks.joinToString(" ") { it.text.trim() }.trim()
            logVerticalDiag(diagId, "wordSelect joined ${text.toDiagText()}")
            if (text.isEmpty()) {
                val msg = getString(R.string.word_card_no_text)
                mainScope.launch { overlay?.showErrorHint(msg) }
                return
            }
            logRepository.info(
                LogRepository.Category.OCR,
                getString(R.string.log_msg_ocr_results_format, ocrBlocks.size, settings.ocrEngine.name, text),
                elapsedMs = elapsedSince(ocrStartedAt)
            )

            val dictionaryTerm = WordHeuristic.dictionaryTermOrNull(text, settings.sourceLang)
            // 词典化：只在「单词 + OpenAI 兼容引擎」时尝试 LLM JSON prompt；其他全部走纯翻译
            val shouldRequestDictionary = dictionaryTerm != null &&
                settings.translatorEngine == com.gameocr.app.data.TranslatorEngine.OPENAI
            logVerticalDiag(
                diagId,
                "wordSelect dictionary eligible=${dictionaryTerm != null} " +
                    "request=$shouldRequestDictionary engine=${settings.translatorEngine.name} " +
                    "term=${dictionaryTerm?.toDiagText() ?: "none"}"
            )
            val card = withContext(Dispatchers.Main) {
                (translationCard ?: TranslationCardOverlay(this@CaptureService).also {
                    translationCard = it
                }).also {
                    it.show(text, null, null, settings, loading = true)
                }
            }
            logWordSelectPerf("card_visible")

            // 单词先请求结构化词典；没有有效词典结果时，才回退到主翻译流式输出。
            val translateStartedAt = System.currentTimeMillis()
            val translateStartedElapsed = SystemClock.elapsedRealtime()
            val outcome = WordSelectTranslationCoordinator(translator).execute(
                source = text,
                settings = settings,
                dictionaryTerm = dictionaryTerm.takeIf { shouldRequestDictionary },
                onPartialTranslation = { partial ->
                    withContext(Dispatchers.Main) { card.updateTranslation(partial) }
                },
                onWordResult = { result ->
                    logVerticalDiag(
                        diagId,
                        "wordSelect dictionary result=ready " +
                            "phonetic=${result.phonetic.isNotBlank()} pos=${result.pos.size} " +
                            "definitions=${result.definitions.size} notes=${result.difficultyNotes.size} " +
                            "examples=${result.examples.size}"
                    )
                    withContext(Dispatchers.Main) { card.updateWordResult(result) }
                },
                onStage = { stage ->
                    logWordSelectPerf(
                        stage = when (stage) {
                            WordSelectTranslationStage.PRIMARY_STARTED -> "primary_started"
                            WordSelectTranslationStage.FIRST_PARTIAL_VISIBLE -> "first_partial_visible"
                            WordSelectTranslationStage.PRIMARY_FINISHED -> "primary_finished"
                            WordSelectTranslationStage.DICTIONARY_STARTED -> "dictionary_started"
                            WordSelectTranslationStage.DICTIONARY_FINISHED -> "dictionary_finished"
                        },
                        details = "translationMs=${SystemClock.elapsedRealtime() - translateStartedElapsed}",
                    )
                },
            )
            outcome.dictionaryError?.let { error ->
                Timber.w(error, "Word-select dictionary request failed")
                logVerticalDiag(diagId, "wordSelect dictionary failed error=${shortError(error)}")
            }
            logVerticalDiag(
                diagId,
                "wordSelect translation chunks=${outcome.chunkCount} " +
                    "firstChunkMs=${outcome.firstChunkLatencyMs ?: -1}"
            )
            outcome.translationError?.let { error ->
                Timber.w(error, "Word-select translate failed")
                logRepository.error(
                    LogRepository.Category.TRANSLATE,
                    getString(R.string.log_msg_translate_failed_format, settings.translatorEngine.name),
                    error,
                    elapsedMs = elapsedSince(translateStartedAt)
                )
            }

            val dictionaryFallback = outcome.wordResult?.fallbackTranslation
                ?: outcome.wordResult?.definitions?.firstOrNull()
            val displayedTranslation = outcome.translation?.takeIf { it.isNotBlank() }
                ?: dictionaryFallback?.takeIf { it.isNotBlank() }
                ?: resolveTranslationOutput(
                    initialOutput = outcome.translation,
                    source = text,
                    settings = settings,
                    diagId = diagId,
                    label = "wordSelect",
                ).text
            withContext(Dispatchers.Main) {
                card.updateWordResult(outcome.wordResult)
                card.updateTranslation(displayedTranslation)
            }
            if (outcome.translationError != null && outcome.wordResult == null) {
                val msg = getString(
                    R.string.toast_word_select_translate_failed_format,
                    settings.translatorEngine.name,
                    shortError(outcome.translationError)
                )
                mainScope.launch { overlay?.showErrorHint(msg) }
            }
            if (outcome.translation?.isNotBlank() == true) {
                logRepository.pair(
                    LogRepository.Category.TRANSLATE,
                    text,
                    outcome.translation,
                    elapsedMs = elapsedSince(translateStartedAt)
                )
            }
            logWordSelectPerf(
                "result_complete",
                "translationMs=${elapsedSince(translateStartedAt)} chunks=${outcome.chunkCount}",
            )
        } finally {
            restoreCaptureChromeOnce(showLoading = false)
            logWordSelectPerf("pipeline_finished")
            logVerticalDiag(diagId, "finish")
            mainScope.launch { overlay?.dismissLoading() }
            captureLock.unlock()
        }
    }

    /** 按 rect 裁出 bitmap。rect 越界自动夹回；裁出后 ≤ 8px 任一边视为无效返回 null。 */
    private fun cropRect(src: Bitmap, rect: android.graphics.Rect): Bitmap? {
        val l = rect.left.coerceIn(0, src.width)
        val t = rect.top.coerceIn(0, src.height)
        val r = rect.right.coerceIn(0, src.width)
        val b = rect.bottom.coerceIn(0, src.height)
        if (r - l <= 8 || b - t <= 8) return null
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    private fun shouldRerunForTextLine180(result: OrientationResult): Boolean =
        result.source == PaddleTextLineOrientationClassifier.SOURCE && result.rawAngle == 180

    private suspend fun rerunOcrRotated180(
        bitmap: Bitmap,
        engine: OcrEngineKind,
        diagId: Long,
    ): List<TextBlock> {
        val rotated = rotateBitmap180(bitmap)
        return try {
            logVerticalDiag(diagId, "rerun OCR rotated180 engine=${engine.name} bitmap=${bitmap.width}x${bitmap.height}")
            val blocks = ocrEngine.recognize(rotated, engine)
            val mapped = mapBlocksFromRotated180(blocks, bitmap.width, bitmap.height)
            logVerticalBlocks(diagId, "rerunBlocks rotated180 engine=${engine.name}", mapped)
            mapped
        } finally {
            rotated.recycle()
        }
    }

    private fun rotateBitmap180(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(180f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 切换主球技能：写 Settings 持久化 + 同步 floatingButton 字段 + 弹个 info 提示告诉用户
     * 新行为。settings flow collect 也会触发 applyOverlayConfig 把图标同步，但这里直接调
     * applySkillIcon 避免一次跨线程延迟。
     */
    private fun applyFloatingSkill(newSkill: FloatingSkill) {
        if (newSkill != FloatingSkill.LOOP && loopMode) toggleLoopMode()
        scope.launch {
            settingsRepository.update { it.copy(floatingButtonSkill = newSkill) }
        }
        floatingButton?.skill = newSkill
        mainScope.launch { floatingButton?.applySkillIcon() }
        val msgRes = when (newSkill) {
            FloatingSkill.FULL_SCREEN -> R.string.toast_skill_switched_full_screen
            FloatingSkill.WORD_SELECT -> R.string.toast_skill_switched_word_select
            FloatingSkill.LOOP -> R.string.toast_skill_switched_loop
        }
        mainScope.launch { overlay?.showInfoHint(getString(msgRes)) }
    }

    private fun showLanguageQuickSwitchOverlay() {
        val panel = languageQuickSwitch ?: LanguageQuickSwitchOverlay(this).also {
            languageQuickSwitch = it
        }
        if (panel.isShown()) return
        scope.launch {
            val settings = settingsRepository.get()
            mainScope.launch {
                panel.show(settings) { source, target ->
                    scope.launch {
                        settingsRepository.update {
                            it.copy(sourceLang = source, targetLang = target)
                        }
                    }
                    overlay?.showInfoHint(
                        getString(
                            R.string.language_quick_updated_format,
                            Languages.nameOf(this@CaptureService, source),
                            Languages.nameOf(this@CaptureService, target)
                        )
                    )
                }
            }
        }
    }

    private fun showPresetQuickSwitchOverlay() {
        val panel = presetQuickSwitch ?: PresetQuickSwitchOverlay(this).also {
            presetQuickSwitch = it
        }
        if (panel.isShown()) return
        scope.launch {
            val settings = settingsRepository.get()
            mainScope.launch {
                panel.show(settings) { preset ->
                    scope.launch {
                        settingsRepository.update { current ->
                            preset.applyTo(current).copy(activeTranslationPresetId = preset.id)
                        }
                    }
                    overlay?.showInfoHint(
                        getString(
                            R.string.preset_quick_applied_format,
                            presetDisplayName(preset)
                        )
                    )
                }
            }
        }
    }

    private fun presetDisplayName(preset: TranslationPreset): String = when (preset.id) {
        TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH ->
            getString(R.string.settings_translation_preset_builtin_manga)
        else -> preset.name
    }

    private fun toggleLoopMode() {
        if (loopMode) {
            loopMode = false
            loopJob?.cancel()
            loopJob = null
            resetLoopFrameHistory()
            resetLoopRuntimeState()
            mainScope.launch {
                // 中途切回 OFF：若倒计时圆圈还没消失，立刻撤掉
                overlay?.cancelStartCountdown()
                floatingButton?.setLoopActive(false, 0L)
            }
            Timber.i("Loop mode OFF")
            logRepository.info(LogRepository.Category.CAPTURE, getString(R.string.log_msg_loop_off))
            val msg = getString(R.string.toast_loop_off)
            mainScope.launch { overlay?.showInfoHint(msg) }
        } else {
            resetLoopFrameHistory()
            resetLoopRuntimeState()
            loopMode = true
            // 先弹屏幕中央 3-2-1 倒计时圆圈，圆圈 removeView + ~80ms VSYNC 缓冲后才启动 loopJob，
            // 保证首次 captureOnce 截屏时画面干净（圆圈已消失）。showInfoHint 提示条不在主 OCR 区域，
            // 可能被截到但概率低 & 影响小，保留以提供更可靠的视觉反馈。
            scope.launch {
                val s = settingsRepository.get()
                val interval = if (s.captureLoopIntervalMs <= 0) 2000L else s.captureLoopIntervalMs
                val secsStr = if (interval % 1000L == 0L) {
                    (interval / 1000L).toString()
                } else {
                    String.format(java.util.Locale.US, "%.1f", interval / 1000.0)
                }
                val smartTrigger = s.loopTriggerMode == LoopTriggerMode.WAIT_FOR_TEXT_COMPLETE
                val msg = if (smartTrigger) {
                    getString(R.string.toast_loop_on_smart, s.loopTextStableDurationMs)
                } else {
                    getString(R.string.toast_loop_on, secsStr)
                }
                val indicator = LoopRuntimePolicy.indicatorSpec(interval, smartTrigger)
                VerticalDiagnosticLog.i(
                    "loop start mode=${s.loopTriggerMode.name} intervalMs=$interval " +
                        "pollMs=${LoopFrameStabilityPolicy.pollingIntervalMs(interval, smartTrigger)} " +
                        "stableMs=${s.loopTextStableDurationMs} skipSimilar=${s.loopSkipSimilarFrames} " +
                        "similarity=${s.loopFrameSimilarityThreshold.toDiagFloat()} " +
                        "textRegion=${s.loopTextRegionMode.name} regionOnly=${s.loopTranslateRegionOnly} " +
                        "indicator=${indicator.mode.name}/${indicator.periodMs}ms"
                )
                mainScope.launch {
                    // 悬浮提示放在倒计时之前：先让用户看到「自动翻译已开启（每 xx 秒一次）」的参数确认，
                    // 再看 3-2-1 圆圈。showInfoHint 默认 1800ms 自动消失，倒计时还在进行时就已经淡出，
                    // 不会跟圆圈视觉打架；且 OCR 在倒计时结束 +80ms 才开始，不会截到提示条。
                    overlay?.showInfoHint(msg)
                    overlay?.showStartCountdown(
                        seconds = 3,
                        hintText = getString(R.string.loop_countdown_hint)
                    ) {
                        // onFinish 时圆圈已经 removeView 且 ~80ms VSYNC 缓冲过
                        if (!loopMode) return@showStartCountdown // 倒计时途中被关掉
                        floatingButton?.setLoopActive(
                            active = true,
                            intervalMs = indicator.periodMs,
                            indeterminate = indicator.mode == LoopIndicatorMode.INDETERMINATE,
                        )
                        loopJob = scope.launch {
                            while (isActive && loopMode) {
                                captureOnce()
                                val s2 = settingsRepository.get()
                                val ivl = LoopFrameStabilityPolicy.pollingIntervalMs(
                                    configuredLoopIntervalMs = s2.captureLoopIntervalMs,
                                    enabled = s2.loopTriggerMode == LoopTriggerMode.WAIT_FOR_TEXT_COMPLETE,
                                )
                                delay(ivl)
                            }
                        }
                    }
                }
            }
            Timber.i("Loop mode ON")
            logRepository.info(LogRepository.Category.CAPTURE, getString(R.string.log_msg_loop_on))
        }
    }

    private fun showTranslationBlockCopyPanel(source: String, translation: String) {
        scope.launch {
            val settings = settingsRepository.get()
            mainScope.launch {
                translationCard?.dismiss()
                val copyOverlay = translationBlockCopyOverlay
                    ?: TranslationBlockCopyOverlay(this@CaptureService).also {
                        translationBlockCopyOverlay = it
                    }
                copyOverlay.show(source, translation, settings)
            }
        }
    }

    private fun resetLoopFrameHistory() {
        previousLoopFingerprint = null
        previousLoopOcrText = null
        loopFrameStabilityState = LoopFrameStabilityState()
        pendingLoopRoiResult = null
        loopRoiTextFallbackActive = LoopRoiFallbackPolicy.transition(
            loopRoiTextFallbackActive,
            LoopRoiFallbackEvent.RESET,
        )
    }

    private fun resetLoopRuntimeState() {
        loopSessionId += 1L
        loopTranslationInFlight = false
        lastLoopRuntimeLogState = null
    }

    private fun beginLoopTranslation(diagId: Long?): Long? {
        if (!loopMode) return null
        val sessionId = loopSessionId
        loopTranslationInFlight = true
        lastLoopRuntimeLogState = "translation_started"
        diagId?.let { logVerticalDiag(it, "loop runtime translation started session=$sessionId") }
        return sessionId
    }

    private fun finishLoopTranslation(
        diagId: Long?,
        sessionId: Long?,
    ) {
        if (sessionId == null || sessionId != loopSessionId || !loopMode) return
        loopTranslationInFlight = false
        lastLoopRuntimeLogState = "translation_finished"
        diagId?.let {
            logVerticalDiag(it, "loop runtime translation finished manualDismissRequired=true")
        }
    }

    private fun markLoopResultVisible(diagId: Long?) {
        if (!loopMode) return
        loopTranslationInFlight = false
        lastLoopRuntimeLogState = "result_displayed"
        diagId?.let { logVerticalDiag(it, "loop runtime result visible manualDismissRequired=true") }
    }

    private fun logLoopRuntimeTransition(diagId: Long, state: String, message: String) {
        if (lastLoopRuntimeLogState == state) return
        lastLoopRuntimeLogState = state
        logVerticalDiag(diagId, message)
    }

    private fun createLoopFrameFingerprint(
        bitmap: Bitmap,
        settings: Settings,
    ): LoopFrameFingerprint? {
        val needsFingerprint = settings.loopSkipSimilarFrames ||
            settings.loopTriggerMode == LoopTriggerMode.WAIT_FOR_TEXT_COMPLETE
        if (!loopMode || !needsFingerprint) {
            if (loopMode) resetLoopFrameHistory()
            return null
        }
        val exclusion = floatingButton?.captureExclusionRect()?.let { screenRect ->
            val offsetX = settings.captureRegion?.left ?: 0
            val offsetY = settings.captureRegion?.top ?: 0
            Rect(screenRect).apply { offset(-offsetX, -offsetY) }
                .takeIf { it.intersect(0, 0, bitmap.width, bitmap.height) }
        }
        return LoopFrameFingerprintFactory.create(
            bitmap = bitmap,
            contextId = settings.hashCode(),
            excludedRect = exclusion,
        )
    }

    private fun selectLoopTextRoi(
        blocks: List<TextBlock>,
        bitmap: Bitmap,
        mode: LoopTextRegionMode,
    ): LoopTextRect? = LoopTextRoiPolicy.select(
        candidates = blocks.map { block ->
            val box = block.boundingBox
            LoopTextRoiCandidate(
                text = block.text,
                rect = LoopTextRect(box.left, box.top, box.right, box.bottom),
            )
        },
        imageWidth = bitmap.width,
        imageHeight = bitmap.height,
        mode = mode,
    )

    private suspend fun recognizeLoopRoi(
        bitmap: Bitmap,
        pending: PendingLoopRoiResult,
        settings: Settings,
    ): List<TextBlock> {
        val roi = pending.roi
        val cropped = Bitmap.createBitmap(bitmap, roi.left, roi.top, roi.width, roi.height)
        val preprocess = if (pending.effectiveEngine.needsRawBitmap) {
            settings.preprocess.copy(invert = false, binarize = false)
        } else {
            settings.preprocess
        }
        val preprocessed = BitmapPreprocessor.apply(cropped, preprocess)
        return try {
            val raw = ocrEngine.recognize(preprocessed, pending.effectiveEngine)
            val mapped = raw.map { block ->
                val box = block.boundingBox
                val mappedBox = LoopRoiCoordinatePolicy.mapFromRoi(
                    block = LoopTextRect(box.left, box.top, box.right, box.bottom),
                    roi = roi,
                    upscale2x = preprocess.upscale2x,
                )
                block.copy(
                    boundingBox = Rect(
                        mappedBox.left,
                        mappedBox.top,
                        mappedBox.right,
                        mappedBox.bottom,
                    )
                )
            }
            sortTextBlocksForReading(mapped, pending.renderOrientation)
        } finally {
            if (preprocessed !== cropped) preprocessed.recycle()
            cropped.recycle()
        }
    }

    private suspend fun deliverStableLoopBlocks(
        diagId: Long,
        blocks: List<TextBlock>,
        settings: Settings,
        renderOrientation: TextOrientation,
        effectiveEngine: OcrEngineKind,
        currentFingerprint: LoopFrameFingerprint?,
        ocrElapsedMs: Long,
        source: String,
    ) {
        val normalizedText = LoopFrameChangePolicy.normalizeOcrText(blocks.map { it.text })
        if (blocks.isEmpty() || normalizedText.isEmpty()) {
            logVerticalDiag(diagId, "stop: no OCR blocks source=$source")
            return
        }
        val qualityIssue = findOcrResultQualityIssue(blocks)
        if (qualityIssue != null) {
            logVerticalDiag(
                diagId,
                "stop: unreliable OCR source=$source engine=${effectiveEngine.name} ${qualityIssue.toLogString()}"
            )
            return
        }
        val postOcr = LoopFrameStabilityPolicy.afterOcr(
            state = loopFrameStabilityState,
            current = currentFingerprint,
            trigger = LoopFrameStabilityDecision.PROCESS,
            normalizedOcrText = normalizedText,
            enabled = true,
            skipAlreadyProcessed = settings.loopSkipSimilarFrames,
            stableDurationMs = settings.loopTextStableDurationMs,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        loopFrameStabilityState = postOcr.state
        commitLoopFrame(currentFingerprint, normalizedText)
        if (postOcr.decision != LoopFramePostOcrDecision.TRANSLATE) {
            loopRoiTextFallbackActive = LoopRoiFallbackPolicy.transition(
                loopRoiTextFallbackActive,
                LoopRoiFallbackEvent.TEXT_FINISHED,
            )
            logVerticalDiag(
                diagId,
                "skip stable loop result source=$source relation=${postOcr.textObservation?.relation?.name ?: "none"} " +
                    "similarity=${postOcr.textObservation?.similarity?.toDiagFloat() ?: "n/a"}"
            )
            return
        }
        loopRoiTextFallbackActive = LoopRoiFallbackPolicy.transition(
            loopRoiTextFallbackActive,
            LoopRoiFallbackEvent.TEXT_FINISHED,
        )
        val joined = blocks.mapIndexed { index, block -> "#${index + 1} ${block.text}" }.joinToString(" | ")
        logRepository.info(
            LogRepository.Category.OCR,
            getString(R.string.log_msg_ocr_results_format, blocks.size, effectiveEngine.name, joined),
            elapsedMs = ocrElapsedMs,
        )
        logVerticalDiag(
            diagId,
            "render stable loop result source=$source engine=${effectiveEngine.name} " +
                "orientation=$renderOrientation blocks=${blocks.size}"
        )
        val redBoxActive = DeveloperOcrDebugPolicy.redBoxActive(
            settings.developerOptionsEnabled,
            settings.ocrRedBoxModeEnabled,
        )
        val debugShouldTranslate = DeveloperOcrDebugPolicy.shouldTranslate(
            settings.developerOptionsEnabled,
            settings.ocrRedBoxModeEnabled,
            settings.ocrRedBoxShowTranslation,
        )
        if (redBoxActive && !debugShouldTranslate) {
            withContext(Dispatchers.Main) {
                overlay?.showBlocks(blocks.map { it to "" }, renderOrientation, diagnosticId = diagId)
            }
            markLoopResultVisible(diagId)
            return
        }
        when {
            redBoxActive -> renderBlocks(blocks, settings, renderOrientation, diagId)
            settings.renderMode == RenderMode.BLOCKS -> renderBlocks(blocks, settings, renderOrientation, diagId)
            else -> renderFloatingWindow(blocks, settings, diagId)
        }
    }

    private fun commitLoopFrame(
        fingerprint: LoopFrameFingerprint?,
        normalizedOcrText: String,
    ) {
        if (!loopMode || fingerprint == null) return
        previousLoopFingerprint = fingerprint
        previousLoopOcrText = normalizedOcrText
    }

    /**
     * 启动悬浮窗版区域选择。流程：
     *  1) 拉框前隐藏悬浮球（避免遮挡 + 防止它跑进选区影响后续截屏 OCR）
     *  2) 把当前 captureRegion 作为初始框传入，用户可在原基础上调整
     *  3) 确认 → 写回 Settings；取消 → 不写
     *  4) 不管哪种结束都恢复悬浮球
     *
     * 重复点菜单时通过 [RegionPickerOverlay.isShown] 去重，永远只有一个 picker。
     */
    private fun showRegionPickerOverlay() {
        val picker = regionPicker ?: RegionPickerOverlay(this).also { regionPicker = it }
        if (picker.isShown()) return
        mainScope.launch {
            // 拿当前屏幕尺寸先把 region rescale 一遍，确保 initial 显示在当前方向的正确位置。
            val dm = resources.displayMetrics
            settingsRepository.rescaleCaptureRegionIfNeeded(dm.widthPixels, dm.heightPixels)
            val initial = settingsRepository.get().captureRegion?.let {
                android.graphics.Rect(it.left, it.top, it.right, it.bottom)
            }
            floatingButton?.hide()
            picker.show(
                initial = initial,
                onConfirm = { rect ->
                    scope.launch {
                        val dm2 = resources.displayMetrics
                        settingsRepository.update {
                            it.copy(
                                captureRegion = CaptureRegion(rect.left, rect.top, rect.right, rect.bottom),
                                captureRegionSavedScreenW = dm2.widthPixels,
                                captureRegionSavedScreenH = dm2.heightPixels
                            )
                        }
                    }
                    mainScope.launch { floatingButton?.show() }
                },
                onCancel = {
                    mainScope.launch { floatingButton?.show() }
                },
                onClearAll = {
                    // 双击 = 选择整屏：跟主屏「清除选框」按钮完全一致——captureRegion=null，下次截屏走整屏。
                    scope.launch {
                        settingsRepository.update { it.copy(captureRegion = null) }
                    }
                    mainScope.launch { floatingButton?.show() }
                }
            )
        }
    }

    /** 截断过长的错误信息：完整 stack trace 在悬浮错误条里体验差，截到 ~140 字以内可读即可。 */
    private fun shortError(t: Throwable): String {
        val raw = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return if (raw.length > 140) raw.take(140) + "…" else raw
    }

    private fun elapsedSince(startMs: Long): Long =
        (System.currentTimeMillis() - startMs).coerceAtLeast(0L)

    private suspend fun captureOnce(
        showLoadingAfterScreenshot: Boolean = false,
        restoreFloatingButtonAfterScreenshot: Boolean = false
    ) {
        if (!captureLock.tryLock()) {
            restoreCaptureChrome(
                showLoading = false,
                restoreFloatingButton = restoreFloatingButtonAfterScreenshot
            )
            mainScope.launch { overlay?.dismissLoading() }
            return
        }
        val diagId = ++captureSequence
        var captureAttemptStarted = false
        var captureChromeRestored = false
        fun restoreCaptureChromeOnce(showLoading: Boolean) {
            if (captureChromeRestored) return
            captureChromeRestored = true
            restoreCaptureChrome(
                showLoading = showLoading,
                restoreFloatingButton = restoreFloatingButtonAfterScreenshot
            )
        }
        try {
            if (loopMode) {
                val loopSettings = settingsRepository.get()
                val hasActiveResult = overlay?.hasActiveResult() == true
                val activeResultDecision = LoopRuntimePolicy.activeResultDecision(
                    hasActiveResult = hasActiveResult,
                    translationInFlight = loopTranslationInFlight,
                )
                when (activeResultDecision) {
                    LoopActiveResultDecision.CAPTURE -> lastLoopRuntimeLogState = null
                    LoopActiveResultDecision.KEEP_TRANSLATING -> {
                        logLoopRuntimeTransition(
                            diagId,
                            state = "translation_in_flight",
                            message = "loop wait reason=translation_in_flight activeResult=$hasActiveResult",
                        )
                        return
                    }
                    LoopActiveResultDecision.KEEP_VISIBLE -> {
                        logLoopRuntimeTransition(
                            diagId,
                            state = "result_visible",
                            message = "loop wait reason=result_visible manualDismissRequired=true " +
                                "mode=${loopSettings.loopTriggerMode.name}",
                        )
                        return
                    }
                }
            }
            captureAttemptStarted = true
            logVerticalDiag(diagId, "start loopMode=$loopMode")
            val shotter = screenshotter ?: run {
                restoreCaptureChromeOnce(showLoading = false)
                return
            }
            val full = shotter.capture()
            if (full == null) {
                // 截屏链路返回 null（MediaProjection token 失效 / Shizuku 调用失败等），
                // 之前直接 return，用户只看到圈转一下；现在显式提示。
                Timber.w("Screenshot capture returned null")
                restoreCaptureChromeOnce(showLoading = false)
                logRepository.error(LogRepository.Category.CAPTURE, getString(R.string.log_msg_capture_failed))
                val msg = getString(R.string.toast_capture_failed)
                mainScope.launch { overlay?.showErrorHint(msg) }
                return
            }
            // 在拿 settings 之前先 rescale region，免得拿到的是旧屏幕方向的坐标。
            restoreCaptureChromeOnce(showLoading = showLoadingAfterScreenshot)
            val fullStats = sampleBitmapFrameStats(full)
            logVerticalDiag(
                diagId,
                "screenshot full=${full.width}x${full.height} stats=${fullStats.toDiagString()}"
            )
            logCaptureGeometry(diagId, "fullScreen", full)
            dumpCaptureFrameForDebug(this, diagId, "full", full)?.let { file ->
                logVerticalDiag(diagId, "debug frame dumped path=${file.absolutePath}")
                logRepository.info(
                    LogRepository.Category.CAPTURE,
                    getString(R.string.log_msg_capture_frame_dumped_format, file.name),
                    imagePath = file.absolutePath
                )
            }
            logBlankLikeFrame(diagId, "screenshot", fullStats)
            val dmNow = resources.displayMetrics
            settingsRepository.rescaleCaptureRegionIfNeeded(dmNow.widthPixels, dmNow.heightPixels)
            val settings = settingsRepository.get()
            applyOverlayConfig(settings)
            logVerticalSettings(diagId, settings, dmNow.widthPixels, dmNow.heightPixels)

            val region = settings.captureRegion
            val workBitmap = cropIfNeeded(full, region) ?: run {
                logVerticalDiag(diagId, "crop skipped: invalid bitmap from region=${region.toDiagString()}")
                full.recycle()
                return
            }
            logVerticalDiag(
                diagId,
                "workBitmap=${workBitmap.width}x${workBitmap.height} region=${region.toDiagString()}"
            )
            val workStats = if (workBitmap === full) {
                fullStats
            } else {
                sampleBitmapFrameStats(workBitmap)
            }
            logVerticalDiag(
                diagId,
                "workBitmap stats=${workStats.toDiagString()}"
            )
            logBlankLikeFrame(diagId, "workBitmap", workStats)
            if (workBitmap !== full) full.recycle()

            val redBoxActive = DeveloperOcrDebugPolicy.redBoxActive(
                settings.developerOptionsEnabled,
                settings.ocrRedBoxModeEnabled,
            )
            val debugShouldTranslate = DeveloperOcrDebugPolicy.shouldTranslate(
                settings.developerOptionsEnabled,
                settings.ocrRedBoxModeEnabled,
                settings.ocrRedBoxShowTranslation,
            )
            val routingT = translator as? com.gameocr.app.translate.RoutingTranslator
            val configuredEndToEnd = routingT?.isEndToEndFor(settings) ?: translator.isEndToEnd
            // Source-only red-box mode needs the regular OCR pipeline so no translator/API is called.
            val isEndToEnd = configuredEndToEnd && debugShouldTranslate

            val smartLoopEnabled = loopMode &&
                settings.loopTriggerMode == LoopTriggerMode.WAIT_FOR_TEXT_COMPLETE
            val roiOptimizationEnabled = smartLoopEnabled && allowsFrequentTextStabilityProbe(
                settings.ocrEngine,
                isEndToEnd,
            )
            var forceTextStabilityFallback = loopRoiTextFallbackActive
            if (roiOptimizationEnabled && !forceTextStabilityFallback) {
                val pending = pendingLoopRoiResult
                if (pending != null) {
                    val currentRoiFingerprint = LoopRoiVisualFingerprintFactory.create(
                        bitmap = workBitmap,
                        roi = pending.roi,
                        contextId = settings.hashCode(),
                    )
                    if (currentRoiFingerprint == null) {
                        pendingLoopRoiResult = null
                        forceTextStabilityFallback = true
                        loopRoiTextFallbackActive = LoopRoiFallbackPolicy.transition(
                            loopRoiTextFallbackActive,
                            LoopRoiFallbackEvent.ENTER,
                        )
                        logVerticalDiag(diagId, "ROI visual sample unavailable; fallback to OCR text stability")
                    } else {
                        val roiResult = LoopRoiStabilityPolicy.observe(
                            state = pending.stabilityState,
                            current = currentRoiFingerprint,
                            similarityThreshold = settings.loopFrameSimilarityThreshold,
                            stableDurationMs = settings.loopTextStableDurationMs,
                            nowElapsedMs = SystemClock.elapsedRealtime(),
                        )
                        pendingLoopRoiResult = pending.copy(stabilityState = roiResult.state)
                        logVerticalDiag(
                            diagId,
                            "ROI stability decision=${roiResult.decision.name} " +
                                "similarity=${roiResult.similarity.toDiagFloat()} " +
                                "changed=${roiResult.state.changedSinceInitialOcr} roi=${pending.roi}"
                        )
                        when (roiResult.decision) {
                            LoopRoiStabilityDecision.WAIT -> {
                                workBitmap.recycle()
                                return
                            }
                            LoopRoiStabilityDecision.TRANSLATE_CACHED -> {
                                pendingLoopRoiResult = null
                                val fingerprint = createLoopFrameFingerprint(workBitmap, settings)
                                workBitmap.recycle()
                                deliverStableLoopBlocks(
                                    diagId = diagId,
                                    blocks = pending.blocks,
                                    settings = settings,
                                    renderOrientation = pending.renderOrientation,
                                    effectiveEngine = pending.effectiveEngine,
                                    currentFingerprint = fingerprint,
                                    ocrElapsedMs = pending.initialOcrElapsedMs,
                                    source = "roi_cached",
                                )
                                return
                            }
                            LoopRoiStabilityDecision.RUN_FINAL_ROI_OCR -> {
                                val roiOcrStartedAt = System.currentTimeMillis()
                                val finalBlocks = try {
                                    recognizeLoopRoi(workBitmap, pending, settings)
                                } catch (ce: kotlinx.coroutines.CancellationException) {
                                    workBitmap.recycle()
                                    throw ce
                                } catch (t: Throwable) {
                                    logVerticalDiag(t, diagId, "final ROI OCR failed; fallback to full OCR text stability")
                                    emptyList()
                                }
                                pendingLoopRoiResult = null
                                val finalQualityIssue = finalBlocks
                                    .takeIf { it.isNotEmpty() }
                                    ?.let(::findOcrResultQualityIssue)
                                if (finalBlocks.isNotEmpty() && finalQualityIssue == null) {
                                    val fingerprint = createLoopFrameFingerprint(workBitmap, settings)
                                    workBitmap.recycle()
                                    deliverStableLoopBlocks(
                                        diagId = diagId,
                                        blocks = finalBlocks,
                                        settings = settings,
                                        renderOrientation = pending.renderOrientation,
                                        effectiveEngine = pending.effectiveEngine,
                                        currentFingerprint = fingerprint,
                                        ocrElapsedMs = elapsedSince(roiOcrStartedAt),
                                        source = "roi_final_ocr",
                                    )
                                    return
                                }
                                if (finalQualityIssue != null) {
                                    logVerticalDiag(
                                        diagId,
                                        "final ROI OCR unreliable ${finalQualityIssue.toLogString()}; " +
                                            "fallback to full OCR text stability"
                                    )
                                }
                                forceTextStabilityFallback = true
                                loopRoiTextFallbackActive = LoopRoiFallbackPolicy.transition(
                                    loopRoiTextFallbackActive,
                                    LoopRoiFallbackEvent.ENTER,
                                )
                            }
                            LoopRoiStabilityDecision.FALLBACK_TO_TEXT_STABILITY -> {
                                pendingLoopRoiResult = null
                                forceTextStabilityFallback = true
                                loopRoiTextFallbackActive = LoopRoiFallbackPolicy.transition(
                                    loopRoiTextFallbackActive,
                                    LoopRoiFallbackEvent.ENTER,
                                )
                            }
                        }
                    }
                }
            } else {
                pendingLoopRoiResult = null
                if (!roiOptimizationEnabled) {
                    loopRoiTextFallbackActive = LoopRoiFallbackPolicy.transition(
                        loopRoiTextFallbackActive,
                        LoopRoiFallbackEvent.RESET,
                    )
                }
            }

            val currentLoopFingerprint = createLoopFrameFingerprint(workBitmap, settings)
            val stabilityResult = if (forceTextStabilityFallback) {
                null
            } else currentLoopFingerprint?.let { current ->
                LoopFrameStabilityPolicy.beforeOcr(
                    state = loopFrameStabilityState,
                    current = current,
                    enabled = smartLoopEnabled,
                    allowTextStabilityProbe = allowsFrequentTextStabilityProbe(
                        settings.ocrEngine,
                        isEndToEnd,
                    ),
                    skipAlreadyProcessed = settings.loopSkipSimilarFrames,
                    stableDurationMs = settings.loopTextStableDurationMs,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
            }
            stabilityResult?.let { loopFrameStabilityState = it.state }
            val stabilityTrigger = when {
                forceTextStabilityFallback -> LoopFrameStabilityDecision.PROBE_TEXT_STABILITY
                stabilityResult != null -> stabilityResult.decision
                else -> LoopFrameStabilityDecision.PROCESS
            }
            if (stabilityTrigger == LoopFrameStabilityDecision.WAIT_FOR_STABLE_FRAME ||
                stabilityTrigger == LoopFrameStabilityDecision.SKIP_ALREADY_PROCESSED
            ) {
                logVerticalDiag(
                    diagId,
                    "skip loop frame stability=${stabilityTrigger.name} " +
                        "waitMs=${settings.loopTextStableDurationMs}"
                )
                workBitmap.recycle()
                return
            }

            val loopPreOcrResult = currentLoopFingerprint?.let { current ->
                LoopFrameChangePolicy.beforeOcr(
                    previous = previousLoopFingerprint,
                    current = current,
                    enabled = settings.loopSkipSimilarFrames,
                    similarityThreshold = settings.loopFrameSimilarityThreshold,
                )
            } ?: LoopFramePreOcrResult(LoopFramePreOcrDecision.PROCESS)
            if (loopPreOcrResult.decision == LoopFramePreOcrDecision.SKIP_EXACT_FRAME) {
                logVerticalDiag(diagId, "skip loop frame reason=exact_hash similarity=1.000")
                Timber.i("Skip loop frame: exact hash match")
                workBitmap.recycle()
                return
            }
            loopPreOcrResult.similarity?.let { similarity ->
                logVerticalDiag(
                    diagId,
                    "loop frame comparison decision=${loopPreOcrResult.decision.name} " +
                        "similarity=${String.format(Locale.US, "%.3f", similarity)} " +
                        "threshold=${String.format(Locale.US, "%.2f", settings.loopFrameSimilarityThreshold)}"
                )
            }

            // 端到端引擎（有道图片翻译）：跳过 OCR 阶段，直接拿带译文的 box；不走 mergeAdjacentBlocks
            // 也不走后续 translateOne，因为译文已经在 region 粒度上对齐好了。
            logVerticalDiag(
                diagId,
                "translator=${settings.translatorEngine.name} isEndToEnd=$isEndToEnd " +
                    "configuredEndToEnd=$configuredEndToEnd redBox=$redBoxActive " +
                    "debugTranslate=$debugShouldTranslate stability=${stabilityTrigger.name} " +
                    "renderMode=${settings.renderMode.name}"
            )
            val ocrStartedAt = System.currentTimeMillis()
            if (isEndToEnd) {
                val translatedBlocks = try {
                    translator.ocrAndTranslate(workBitmap, settings)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    workBitmap.recycle()
                    throw ce
                } catch (t: Throwable) {
                    Timber.w(t, "End-to-end OCR+translate failed")
                    logRepository.error(
                        LogRepository.Category.OCR,
                        getString(R.string.log_msg_ocr_failed_format, settings.translatorEngine.name),
                        t,
                        elapsedMs = elapsedSince(ocrStartedAt)
                    )
                    val msg = getString(R.string.toast_ocr_failed_format, settings.translatorEngine.name, shortError(t))
                    mainScope.launch { overlay?.showErrorHint(msg) }
                    workBitmap.recycle()
                    return
                }
                workBitmap.recycle()
                val normalizedEndToEndText = LoopFrameChangePolicy.normalizeOcrText(
                    translatedBlocks.map { (block, _) -> block.text }
                )
                val endToEndStability = LoopFrameStabilityPolicy.afterOcr(
                    state = loopFrameStabilityState,
                    current = currentLoopFingerprint,
                    trigger = stabilityTrigger,
                    normalizedOcrText = normalizedEndToEndText,
                    enabled = smartLoopEnabled,
                    skipAlreadyProcessed = settings.loopSkipSimilarFrames,
                    stableDurationMs = settings.loopTextStableDurationMs,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
                loopFrameStabilityState = endToEndStability.state
                commitLoopFrame(
                    currentLoopFingerprint,
                    normalizedEndToEndText,
                )
                logVerticalTranslatedBlocks(diagId, "endToEnd", translatedBlocks)
                if (translatedBlocks.isNotEmpty()) {
                    val joined = translatedBlocks.mapIndexed { i, (b, dst) ->
                        "#${i + 1} ${b.text} → $dst"
                    }.joinToString(" | ")
                    logRepository.info(
                        LogRepository.Category.OCR,
                        "[${settings.translatorEngine.name}] ${translatedBlocks.size} 段: $joined",
                        elapsedMs = elapsedSince(ocrStartedAt)
                    )
                } else {
                    logRepository.info(
                        LogRepository.Category.OCR,
                        "[${settings.translatorEngine.name}] 无识别结果",
                        elapsedMs = elapsedSince(ocrStartedAt)
                    )
                    return
                }
                renderTranslatedBlocks(
                    translatedBlocks,
                    settings,
                    diagId,
                    translationElapsedMs = elapsedSince(ocrStartedAt)
                )
                return
            }

            // manga-ocr 训练时见的是漫画原图（含网点 / 灰阶），invert / binarize 后效果显著下降，
            // 所以 [OcrEngineKind.needsRawBitmap] = true 时跳过这两步。upscale2x 保留——它对
            // DBNet 检测小字仍有帮助，对 manga-ocr 224×224 squash resize 后也无副作用。
            // 第一次 OCR：用用户在 settings 选的引擎跑
            var effectiveEngine = settings.ocrEngine
            var orientationHint: OrientationResult? = null
            val hasMangaOcr = mangaOcrModelInstaller.checkInstalled() != null
            val baiduConfigured = settings.baiduOcrApiKey.isNotBlank() &&
                settings.baiduOcrSecretKey.isNotBlank()
            logVerticalDiag(
                diagId,
                "ocr route initial=${effectiveEngine.name} hasMangaOcr=$hasMangaOcr " +
                    "paddleVersion=${settings.paddleModelVersion.name} baiduEndpoint=${settings.baiduOcrEndpoint.name} " +
                    "baiduConfigured=$baiduConfigured autoDetect=${settings.textOrientationAutoDetect} " +
                    "manual=${settings.manualTextOrientation?.name ?: "null"}"
            )

            if (settings.textOrientationAutoDetect) {
                val preHint = settings.manualTextOrientation
                    ?.let { OrientationResult(it, 1f, 0, "manual") }
                    ?: orientationCoordinator.classifyPreOcr(workBitmap)
                logVerticalOrientation(diagId, "pre", preHint)
                if (preHint.orientation != TextOrientation.UNKNOWN ||
                    preHint.source != "heuristic-bitmap-na"
                ) {
                    orientationHint = preHint
                }
                val preEngine = OrientationRouting.resolveEngine(
                    orientation = preHint.orientation,
                    sourceLangBcp47 = settings.sourceLang,
                    userEngine = effectiveEngine,
                    hasMangaOcr = hasMangaOcr,
                    baiduConfigured = baiduConfigured
                )
                if (preEngine != null && preEngine != effectiveEngine) {
                    logOcrInfo(
                        "[orient-pre] %s conf=%.2f raw=%d src=%s -> first pass %s (was %s)".format(
                            preHint.orientation.name, preHint.confidence, preHint.rawAngle,
                            preHint.source, preEngine.name, effectiveEngine.name
                        )
                    )
                    effectiveEngine = preEngine
                } else if (preHint.source != "heuristic-bitmap-na") {
                    logOcrInfo(
                        "[orient-pre] %s conf=%.2f raw=%d src=%s -> keep %s".format(
                            preHint.orientation.name, preHint.confidence, preHint.rawAngle,
                            preHint.source, effectiveEngine.name
                        )
                    )
                }
            }
            val firstPreprocess = if (effectiveEngine.needsRawBitmap) {
                settings.preprocess.copy(invert = false, binarize = false)
            } else {
                settings.preprocess
            }
            logVerticalDiag(
                diagId,
                "first OCR engine=${effectiveEngine.name} preprocess=${firstPreprocess.toDiagString()} " +
                    "paddleVersion=${settings.paddleModelVersion.name} " +
                    "needsRaw=${effectiveEngine.needsRawBitmap}"
            )
            var preprocessed: Bitmap = BitmapPreprocessor.apply(workBitmap, firstPreprocess)
            logVerticalDiag(diagId, "preprocessed=${preprocessed.width}x${preprocessed.height}")
            val firstBlocks = try {
                ocrEngine.recognize(preprocessed, effectiveEngine)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // 协程取消（用户长按关循环 / Service 销毁）不是真错误，让它传播出去，
                // 不要记为 OCR 失败也不要弹错误条。Bitmap 在 finally 里没法回收，这里手动清。
                if (preprocessed !== workBitmap) preprocessed.recycle()
                workBitmap.recycle()
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "OCR failed")
                logVerticalDiag(t, diagId, "first OCR failed engine=${effectiveEngine.name}")
                logRepository.error(
                    LogRepository.Category.OCR,
                    getString(R.string.log_msg_ocr_failed_format, effectiveEngine.name),
                    t,
                    elapsedMs = elapsedSince(ocrStartedAt)
                )
                // 提示给用户：不然只看到 loading 圈转一下就消失，必须翻日志才知道是 OCR 失败。
                // 用悬浮错误条显示（走 loading 圈同链路），跨 ROM 一致——不走 Toast，因为后台
                // Service Toast 在 HyperOS / MIUI 等国产 ROM 上会被静默丢弃。
                val msg = getString(R.string.toast_ocr_failed_format, effectiveEngine.name, shortError(t))
                mainScope.launch { overlay?.showErrorHint(msg) }
                if (preprocessed !== workBitmap) preprocessed.recycle()
                workBitmap.recycle()
                return
            }
            logVerticalBlocks(diagId, "firstBlocks engine=${effectiveEngine.name}", firstBlocks)
            val firstPassNonWhitespaceChars = firstBlocks.sumOf { block ->
                block.text.count { ch -> !ch.isWhitespace() }
            }
            val firstPassPortraitBlocks = firstBlocks.count { block ->
                val box = block.boundingBox
                box.height() > 0 && box.height().toFloat() / box.width().coerceAtLeast(1) >= 1.25f
            }
            val lowQualityChinesePaddleFallback = shouldRerunLowQualityChinesePaddleOcr(
                sourceLangBcp47 = settings.sourceLang,
                engine = effectiveEngine,
                autoDetect = settings.textOrientationAutoDetect,
                manualOrientationLocked = settings.manualTextOrientation != null,
                imageWidth = preprocessed.width,
                imageHeight = preprocessed.height,
                blockCount = firstBlocks.size,
                portraitBlockCount = firstPassPortraitBlocks,
                nonWhitespaceChars = firstPassNonWhitespaceChars
            )
            if (lowQualityChinesePaddleFallback) {
                logVerticalDiag(
                    diagId,
                    "low-quality zh Paddle candidate blocks=${firstBlocks.size} " +
                        "portraitBlocks=$firstPassPortraitBlocks chars=$firstPassNonWhitespaceChars " +
                        "image=${preprocessed.width}x${preprocessed.height}"
                )
            }

            // 文本方向自动分流：OCR 后看 bbox 几何判方向。如果路由层判定当前引擎对此方向不合适
            // （如发现是日文竖排但用了 ML Kit Latin），用更合适的引擎重跑一次。横排场景路由返回
            // null，零开销；竖排误用其它引擎时 OCR 跑 2 次但用户被自动救。详见 OrientationCoordinator。
            // hint 提升到外层：渲染层 renderBlocks 也要用（按方向选 TextView vs VerticalTextView）
            val rawBlocks: List<TextBlock> = if (
                settings.textOrientationAutoDetect &&
                (firstBlocks.isNotEmpty() || lowQualityChinesePaddleFallback)
            ) {
                val hint = if (settings.manualTextOrientation != null) {
                    orientationHint ?: OrientationResult(settings.manualTextOrientation, 1f, 0, "manual")
                } else {
                    val layoutHint = orientationHintFromLayout(firstBlocks)
                    val refined = if (firstBlocks.isEmpty()) {
                        OrientationResult(TextOrientation.UNKNOWN, 0f, 0, "post-ocr-empty")
                    } else {
                        orientationCoordinator.classifyPostOcr(
                            preprocessed,
                            firstBlocks,
                            orientationHint ?: OrientationResult(TextOrientation.UNKNOWN, 0f, 0, "post-ocr")
                        )
                    }
                    when {
                        refined.orientation == TextOrientation.UNKNOWN && layoutHint != null -> layoutHint
                        refined.orientation == TextOrientation.UNKNOWN &&
                            orientationHint != null &&
                            orientationHint!!.orientation != TextOrientation.UNKNOWN -> orientationHint!!
                        else -> refined
                    }
                }
                orientationHint = hint
                logVerticalOrientation(diagId, "post", hint)
                val upsideDownRerun = if (shouldRerunForTextLine180(hint)) {
                    try {
                        val rerun = rerunOcrRotated180(preprocessed, effectiveEngine, diagId)
                        logOcrInfo(
                            "[orient-textline] 180deg conf=%.2f src=%s -> rerun %s and map boxes back".format(
                                hint.confidence, hint.source, effectiveEngine.name
                            )
                        )
                        rerun
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        if (preprocessed !== workBitmap) preprocessed.recycle()
                        workBitmap.recycle()
                        throw ce
                    } catch (t: Throwable) {
                        Timber.w(t, "OCR rerun after text-line 180 failed; keep original $effectiveEngine result")
                        logVerticalDiag(t, diagId, "text-line 180 rerun failed engine=${effectiveEngine.name}")
                        logOcrInfo(
                            "[orient-textline] 180deg rerun failed (${shortError(t)}), keep ${effectiveEngine.name}"
                        )
                        null
                    }
                } else {
                    null
                }
                if (upsideDownRerun != null) {
                    upsideDownRerun
                } else {
                    val newEngine = OrientationRouting.resolveEngine(
                        orientation = hint.orientation,
                        sourceLangBcp47 = settings.sourceLang,
                        userEngine = effectiveEngine,
                        hasMangaOcr = hasMangaOcr,
                        baiduConfigured = baiduConfigured
                    )
                    val lowQualityVerticalHint = if (newEngine == null && lowQualityChinesePaddleFallback) {
                        OrientationResult(TextOrientation.VERTICAL_RTL, 0.51f, 0, "low-quality-zh-paddle")
                    } else {
                        null
                    }
                    val rerunEngine = newEngine ?: lowQualityVerticalHint?.let { fallbackHint ->
                        OrientationRouting.resolveEngine(
                            orientation = fallbackHint.orientation,
                            sourceLangBcp47 = settings.sourceLang,
                            userEngine = effectiveEngine,
                            hasMangaOcr = hasMangaOcr,
                            baiduConfigured = baiduConfigured
                        )
                    }
                    if (rerunEngine != null && rerunEngine != effectiveEngine) {
                        val rerunReason = if (lowQualityVerticalHint != null) {
                            "low-quality zh Paddle OCR"
                        } else {
                            "orientation"
                        }
                        logVerticalDiag(
                            diagId,
                            "rerun requested by $rerunReason: ${effectiveEngine.name} -> ${rerunEngine.name}"
                        )
                        // 切引擎重跑：新引擎可能 needsRawBitmap 不同（如 manga-ocr 要原图），重做预处理
                        if (preprocessed !== workBitmap) preprocessed.recycle()
                        val rerunPreprocess = if (rerunEngine.needsRawBitmap) {
                            settings.preprocess.copy(invert = false, binarize = false)
                        } else {
                            settings.preprocess
                        }
                        logVerticalDiag(
                            diagId,
                            "rerun OCR engine=${rerunEngine.name} preprocess=${rerunPreprocess.toDiagString()} " +
                                "needsRaw=${rerunEngine.needsRawBitmap}"
                        )
                        preprocessed = BitmapPreprocessor.apply(workBitmap, rerunPreprocess)
                        val rerunBlocks = try {
                            ocrEngine.recognize(preprocessed, rerunEngine)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            if (preprocessed !== workBitmap) preprocessed.recycle()
                            workBitmap.recycle()
                            throw ce
                        } catch (t: Throwable) {
                            // 重跑失败不抛——沿用第一次 OCR 结果是更安全的兜底（用户至少有东西看）
                            Timber.w(t, "OCR rerun with $rerunEngine failed; keep original $effectiveEngine result")
                            logVerticalDiag(
                                t,
                                diagId,
                                "rerun OCR failed engine=${rerunEngine.name}; keep ${effectiveEngine.name}"
                            )
                            logOcrInfo(
                                "[orient] 重跑 ${rerunEngine.name} 失败 (${shortError(t)})，沿用 ${effectiveEngine.name} 原结果"
                            )
                            null
                        }
                        if (rerunBlocks != null) {
                            logVerticalBlocks(diagId, "rerunBlocks engine=${rerunEngine.name}", rerunBlocks)
                            if (lowQualityVerticalHint != null) {
                                val rerunHint = orientationHintFromLayout(rerunBlocks) ?: lowQualityVerticalHint
                                orientationHint = rerunHint
                                logVerticalOrientation(diagId, "post-rerun", rerunHint)
                            }
                            val message = if (lowQualityVerticalHint != null) {
                                "[orient-low-quality] 中文 Paddle 首轮过少 blocks=%d portraitBlocks=%d chars=%d image=%dx%d → 按竖排重跑 %s (原 %s)".format(
                                    firstBlocks.size,
                                    firstPassPortraitBlocks,
                                    firstPassNonWhitespaceChars,
                                    preprocessed.width,
                                    preprocessed.height,
                                    rerunEngine.name,
                                    effectiveEngine.name
                                )
                            } else {
                                "[orient] %s conf=%.2f src=%s → 切换 %s (原 %s)".format(
                                    hint.orientation.name,
                                    hint.confidence,
                                    hint.source,
                                    rerunEngine.name,
                                    effectiveEngine.name
                                )
                            }
                            logOcrInfo(message)
                            effectiveEngine = rerunEngine
                            rerunBlocks
                        } else {
                            firstBlocks
                        }
                    } else {
                        if (lowQualityVerticalHint != null) {
                            logOcrInfo(
                                "[orient-low-quality] 中文 Paddle 首轮过少 blocks=${firstBlocks.size} " +
                                    "portraitBlocks=$firstPassPortraitBlocks chars=$firstPassNonWhitespaceChars，" +
                                    "但没有可切换的中文竖排 OCR 引擎"
                            )
                        }
                        val message = verticalChineseNoBaiduMessage(hint, settings, effectiveEngine, baiduConfigured)
                            ?: "[orient] %s conf=%.2f src=%s → 沿用 %s (无需切换)".format(
                                hint.orientation.name, hint.confidence, hint.source, effectiveEngine.name
                            )
                        logOcrInfo(message)
                        firstBlocks
                    }
                }
            } else {
                firstBlocks
            }

            if (preprocessed !== workBitmap) preprocessed.recycle()
            logVerticalDiag(
                diagId,
                "ocr final engine=${effectiveEngine.name} paddleVersion=${settings.paddleModelVersion.name} " +
                    "orientationHint=${orientationHint?.orientation?.name ?: "null"}"
            )
            logVerticalBlocks(diagId, "rawBlocks final", rawBlocks)
            val recognizedReadingOrientation = resolveTextBlockReadingOrientation(
                rawBlocks,
                orientationHint?.orientation,
            )
            val translationOutput = resolveTranslationOutputSettings(
                settings.translationOutputFollowRecognition,
                settings.translationOutputLayout,
                settings.translationOutputDirection,
            )
            val renderOrientation = TranslationOutputOrientationPolicy.resolve(
                recognized = recognizedReadingOrientation,
                followRecognition = translationOutput.followRecognition,
                layout = translationOutput.layout,
                direction = translationOutput.direction,
            )
            val orderedRawBlocks = sortTextBlocksForReading(rawBlocks, renderOrientation)
            logVerticalBlocks(diagId, "orderedRawBlocks final", orderedRawBlocks)
            // 把所有 box 拼成"#1 原文 / #2 原文 / ..."一条日志，避免一次 OCR 写多条。
            // 用 effectiveEngine 而非 settings.ocrEngine——方向自动分流时实际跑的可能是另一个引擎
            val ocrElapsedMs = elapsedSince(ocrStartedAt)

            // 预处理 upscale 会让 boundingBox 坐标变成 2 倍，渲染前缩回
            val blocks = if (settings.preprocess.upscale2x) {
                orderedRawBlocks.map { tb ->
                    val r = tb.boundingBox
                    tb.copy(boundingBox = android.graphics.Rect(r.left / 2, r.top / 2, r.right / 2, r.bottom / 2))
                }
            } else orderedRawBlocks
            if (settings.preprocess.upscale2x) {
                logVerticalBlocks(diagId, "scaledBlocks for overlay", blocks)
            }
            val normalizedLoopOcrText = LoopFrameChangePolicy.normalizeOcrText(blocks.map { it.text })
            if (blocks.isEmpty()) {
                workBitmap.recycle()
                val emptyStability = LoopFrameStabilityPolicy.afterOcr(
                    state = loopFrameStabilityState,
                    current = currentLoopFingerprint,
                    trigger = stabilityTrigger,
                    normalizedOcrText = normalizedLoopOcrText,
                    enabled = smartLoopEnabled,
                    skipAlreadyProcessed = settings.loopSkipSimilarFrames,
                    stableDurationMs = settings.loopTextStableDurationMs,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
                loopFrameStabilityState = emptyStability.state
                commitLoopFrame(currentLoopFingerprint, normalizedLoopOcrText)
                logVerticalDiag(diagId, "stop: no OCR blocks")
                logRepository.info(
                    LogRepository.Category.OCR,
                    getString(R.string.log_msg_ocr_no_result_format, effectiveEngine.name),
                    elapsedMs = ocrElapsedMs
                )
                return
            }
            val qualityIssue = findOcrResultQualityIssue(blocks)
            if (qualityIssue != null) {
                workBitmap.recycle()
                val message = getString(R.string.toast_ocr_unreliable_result)
                logVerticalDiag(
                    diagId,
                    "stop: unreliable OCR engine=${effectiveEngine.name} ${qualityIssue.toLogString()}"
                )
                Timber.tag("OcrQuality").i(
                    "unreliable result engine=%s %s",
                    effectiveEngine.name,
                    qualityIssue.toLogString()
                )
                logRepository.warn(LogRepository.Category.OCR, message, elapsedMs = ocrElapsedMs)
                mainScope.launch { overlay?.showErrorHint(message) }
                return
            }
            val shouldSeedLoopRoi = roiOptimizationEnabled &&
                !forceTextStabilityFallback &&
                stabilityTrigger == LoopFrameStabilityDecision.PROBE_TEXT_STABILITY &&
                pendingLoopRoiResult == null
            if (shouldSeedLoopRoi) {
                val roi = selectLoopTextRoi(blocks, workBitmap, settings.loopTextRegionMode)
                val roiFingerprint = roi?.let { selected ->
                    LoopRoiVisualFingerprintFactory.create(
                        bitmap = workBitmap,
                        roi = selected,
                        contextId = settings.hashCode(),
                    )
                }
                if (roi != null && roiFingerprint != null) {
                    val roiBlocks = blocks.filter { block ->
                        val box = block.boundingBox
                        LoopTextRoiPolicy.containsCenter(
                            roi = roi,
                            candidate = LoopTextRect(box.left, box.top, box.right, box.bottom),
                        )
                    }
                    if (roiBlocks.isEmpty()) {
                        logVerticalDiag(diagId, "selected ROI contains no OCR blocks; fallback to OCR text stability")
                    } else {
                        val cachedBlocks = if (settings.loopTranslateRegionOnly) roiBlocks else blocks
                        pendingLoopRoiResult = PendingLoopRoiResult(
                            blocks = cachedBlocks.map { block ->
                                block.copy(boundingBox = Rect(block.boundingBox))
                            },
                            renderOrientation = renderOrientation,
                            effectiveEngine = effectiveEngine,
                            roi = roi,
                            stabilityState = LoopRoiStabilityPolicy.start(
                                fingerprint = roiFingerprint,
                                nowElapsedMs = SystemClock.elapsedRealtime(),
                            ),
                            initialOcrElapsedMs = ocrElapsedMs,
                        )
                        logVerticalDiag(
                            diagId,
                            "seed ROI from first OCR mode=${settings.loopTextRegionMode.name} roi=$roi " +
                                "blocks=${cachedBlocks.size}/${blocks.size} regionOnly=${settings.loopTranslateRegionOnly} " +
                                "textLen=${LoopFrameChangePolicy.normalizeOcrText(roiBlocks.map { it.text }).length} " +
                                "next=visual_stability"
                        )
                        workBitmap.recycle()
                        return
                    }
                }
                loopRoiTextFallbackActive = LoopRoiFallbackPolicy.transition(
                    loopRoiTextFallbackActive,
                    LoopRoiFallbackEvent.ENTER,
                )
                logVerticalDiag(diagId, "first OCR produced no usable ROI; fallback to OCR text stability")
            }
            workBitmap.recycle()
            val postOcrStability = LoopFrameStabilityPolicy.afterOcr(
                state = loopFrameStabilityState,
                current = currentLoopFingerprint,
                trigger = stabilityTrigger,
                normalizedOcrText = normalizedLoopOcrText,
                enabled = smartLoopEnabled,
                skipAlreadyProcessed = settings.loopSkipSimilarFrames,
                stableDurationMs = settings.loopTextStableDurationMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )
            loopFrameStabilityState = postOcrStability.state
            when (postOcrStability.decision) {
                LoopFramePostOcrDecision.WAIT_FOR_STABLE_TEXT -> {
                    val textObservation = postOcrStability.textObservation
                    logVerticalDiag(
                        diagId,
                        "wait for stable OCR text len=${normalizedLoopOcrText.length} " +
                            "waitMs=${settings.loopTextStableDurationMs} " +
                            "fallbackActive=$loopRoiTextFallbackActive " +
                            "relation=${textObservation?.relation?.name ?: "none"} " +
                            "similarity=${textObservation?.similarity?.toDiagFloat() ?: "n/a"}"
                    )
                    loopRoiTextFallbackActive = LoopRoiFallbackPolicy.transition(
                        loopRoiTextFallbackActive,
                        LoopRoiFallbackEvent.TEXT_STILL_WAITING,
                    )
                    return
                }
                LoopFramePostOcrDecision.SKIP_ALREADY_PROCESSED -> {
                    loopRoiTextFallbackActive = LoopRoiFallbackPolicy.transition(
                        loopRoiTextFallbackActive,
                        LoopRoiFallbackEvent.TEXT_FINISHED,
                    )
                    commitLoopFrame(currentLoopFingerprint, normalizedLoopOcrText)
                    val textObservation = postOcrStability.textObservation
                    logVerticalDiag(
                        diagId,
                        "skip already processed stable OCR text " +
                            "relation=${textObservation?.relation?.name ?: "none"} " +
                            "similarity=${textObservation?.similarity?.toDiagFloat() ?: "n/a"}"
                    )
                    return
                }
                LoopFramePostOcrDecision.TRANSLATE -> {
                    loopRoiTextFallbackActive = LoopRoiFallbackPolicy.transition(
                        loopRoiTextFallbackActive,
                        LoopRoiFallbackEvent.TEXT_FINISHED,
                    )
                }
            }
            val skipRepeatedTranslation = LoopFrameChangePolicy.shouldSkipTranslation(
                preOcrDecision = loopPreOcrResult.decision,
                previousOcrText = previousLoopOcrText,
                currentOcrText = normalizedLoopOcrText,
            )
            commitLoopFrame(currentLoopFingerprint, normalizedLoopOcrText)
            if (skipRepeatedTranslation) {
                logVerticalDiag(
                    diagId,
                    "skip loop translation reason=same_ocr_text " +
                        "similarity=${loopPreOcrResult.similarity ?: 0f}"
                )
                Timber.i("Skip loop translation: similar frame has unchanged OCR text")
                return
            }
            val joined = orderedRawBlocks.mapIndexed { i, b -> "#${i + 1} ${b.text}" }.joinToString(" | ")
            logRepository.info(
                LogRepository.Category.OCR,
                getString(R.string.log_msg_ocr_results_format, orderedRawBlocks.size, effectiveEngine.name, joined),
                elapsedMs = ocrElapsedMs
            )

            if (redBoxActive && !debugShouldTranslate) {
                logVerticalDiag(diagId, "render OCR debug boxes without translation")
                withContext(Dispatchers.Main) {
                    overlay?.showBlocks(
                        blocks.map { it to "" },
                        renderOrientation,
                        diagnosticId = diagId,
                    )
                }
                markLoopResultVisible(diagId)
                return
            }
            logVerticalDiag(
                diagId,
                "render mode=${settings.renderMode.name} recognizedOrientation=$recognizedReadingOrientation " +
                    "renderOrientation=$renderOrientation blocks=${blocks.size}"
            )
            when {
                redBoxActive -> renderBlocks(blocks, settings, renderOrientation, diagId)
                settings.renderMode == RenderMode.BLOCKS -> renderBlocks(blocks, settings, renderOrientation, diagId)
                else -> renderFloatingWindow(blocks, settings, diagId)
            }
        } finally {
            if (captureAttemptStarted) {
                restoreCaptureChromeOnce(showLoading = false)
                logVerticalDiag(diagId, "finish")
                // 兜底：所有提前 return / 异常路径下都要把 loading 圈关掉，避免"一直转圈"。
                // 正常完成时 showBlocks/showFullScreen 内部已经 dismiss 过；幂等调用没害。
                mainScope.launch { overlay?.dismissLoading() }
            }
            captureLock.unlock()
        }
    }

    private fun cropIfNeeded(src: Bitmap, region: CaptureRegion?): Bitmap? {
        if (region == null || !region.isValid()) return src
        val l = region.left.coerceIn(0, src.width)
        val t = region.top.coerceIn(0, src.height)
        val r = region.right.coerceIn(0, src.width)
        val b = region.bottom.coerceIn(0, src.height)
        if (r - l <= 8 || b - t <= 8) return src
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    /**
     * 端到端引擎（有道图片翻译）专用渲染：bitmap → 已经带译文的 box 列表，无需再调翻译。
     * 直接按 renderMode 一次性吐到 overlay。每段原文/译文写一条 LogRepository pair。
     */
    private suspend fun renderTranslatedBlocks(
        items: List<Pair<TextBlock, String>>,
        settings: Settings,
        diagId: Long? = null,
        translationElapsedMs: Long? = null
    ) {
        val recognizedOrientation = resolveTextBlockReadingOrientation(
            items.map { it.first },
        )
        val translationOutput = resolveTranslationOutputSettings(
            settings.translationOutputFollowRecognition,
            settings.translationOutputLayout,
            settings.translationOutputDirection,
        )
        val outputOrientation = TranslationOutputOrientationPolicy.resolve(
            recognized = recognizedOrientation,
            followRecognition = translationOutput.followRecognition,
            layout = translationOutput.layout,
            direction = translationOutput.direction,
        )
        diagId?.let { logVerticalTranslatedBlocks(it, "renderTranslatedBlocks", items) }
        items.forEach { (b, dst) ->
            logRepository.pair(
                LogRepository.Category.TRANSLATE,
                b.text,
                dst,
                elapsedMs = translationElapsedMs
            )
        }
        when {
            DeveloperOcrDebugPolicy.redBoxActive(
                settings.developerOptionsEnabled,
                settings.ocrRedBoxModeEnabled,
            ) -> withContext(Dispatchers.Main) {
                overlay?.showBlocks(items, outputOrientation, diagnosticId = diagId)
            }
            settings.renderMode == RenderMode.BLOCKS -> withContext(Dispatchers.Main) {
                overlay?.showBlocks(items, outputOrientation, diagnosticId = diagId)
            }
            else -> withContext(Dispatchers.Main) {
                overlay?.showFullScreen(items.map { (b, dst) -> b.text to dst })
            }
        }
        markLoopResultVisible(diagId)
    }

    private suspend fun renderBlocks(
        blocks: List<TextBlock>,
        settings: Settings,
        orientation: TextOrientation = TextOrientation.HORIZONTAL_LTR,
        diagId: Long? = null
    ) {
        diagId?.let {
            logVerticalDiag(
                it,
                "renderBlocks show placeholders orientation=$orientation count=${blocks.size}"
            )
        }
        // 先把所有原文块以占位"…"显示在原文下方。orientation 决定用 TextView 还是 VerticalTextView
        withContext(Dispatchers.Main) {
            overlay?.showBlocks(blocks.map { it to "…" }, orientation, diagnosticId = diagId)
        }
        // 引擎支持批处理（如 DeepL）→ 一次 HTTP 译多段，避免限频。否则保留逐段流式
        // 调用 translateOne（OpenAI 兼容 LLM 用户依赖逐 token 流式更新体验）。
        val routing = translator as? com.gameocr.app.translate.RoutingTranslator
        val useBatch = routing?.prefersBatchFor(settings) ?: translator.prefersBatch
        diagId?.let {
            logVerticalDiag(
                it,
                "renderBlocks translate useBatch=$useBatch engine=${settings.translatorEngine.name} " +
                    "streaming=${settings.streamingTranslate}"
            )
        }
        val loopSession = beginLoopTranslation(diagId)
        if (useBatch) {
            scope.launch {
                try {
                    batchTranslateBlocks(blocks, settings, diagId)
                } finally {
                    finishLoopTranslation(diagId, loopSession)
                }
            }
        } else {
            scope.launch {
                try {
                    blocks.mapIndexed { idx, block ->
                        async {
                            translateOne(block.text, settings, diagId, idx) { partial ->
                                withContext(Dispatchers.Main) {
                                    overlay?.updateBlockText(idx, partial)
                                }
                            }
                        }
                    }.awaitAll()
                } finally {
                    finishLoopTranslation(diagId, loopSession)
                }
            }
        }
    }

    private suspend fun batchTranslateBlocks(
        blocks: List<TextBlock>,
        settings: Settings,
        diagId: Long? = null
    ) {
        val sources = blocks.map { it.text }
        diagId?.let {
            logVerticalDiag(
                it,
                "batchTranslate begin engine=${settings.translatorEngine.name} count=${sources.size} " +
                    "${settings.sourceLang}->${settings.targetLang}"
            )
            sources.forEachIndexed { idx, source ->
                logVerticalDiag(it, "batchTranslate src#${idx + 1} ${source.toDiagText()}")
            }
        }
        val translateStartedAt = System.currentTimeMillis()
        val translated = try {
            withContext(Dispatchers.IO) { translator.translateBatch(sources, settings) }
        } catch (t: Throwable) {
            val translateElapsedMs = elapsedSince(translateStartedAt)
            Timber.w(t, "Batch translate failed")
            diagId?.let { logVerticalDiag(t, it, "batchTranslate failed engine=${settings.translatorEngine.name}") }
            logRepository.error(
                LogRepository.Category.TRANSLATE,
                getString(R.string.log_msg_batch_translate_failed_format, settings.translatorEngine.name),
                t,
                elapsedMs = translateElapsedMs
            )
            // 整批失败：在所有 box 上显示失败标记
            withContext(Dispatchers.Main) {
                blocks.indices.forEach { idx ->
                    overlay?.updateBlockText(idx, "[!] " + (t.message ?: ""))
                }
            }
            return
        }
        val translateElapsedMs = elapsedSince(translateStartedAt)
        blocks.forEachIndexed { idx, block ->
            val src = block.text
            val display = resolveTranslationOutput(
                initialOutput = translated.getOrNull(idx),
                source = src,
                settings = settings,
                diagId = diagId,
                label = "batch#${idx + 1}",
            )
            val finalText = display.text
            diagId?.let {
                logVerticalDiag(
                    it,
                    "batchTranslate dst#${idx + 1} failed=${display.failed} ${finalText.toDiagText()}"
                )
            }
            withContext(Dispatchers.Main) { overlay?.updateBlockText(idx, finalText) }
            if (!display.failed) {
                logRepository.pair(
                    LogRepository.Category.TRANSLATE,
                    src,
                    finalText,
                    elapsedMs = translateElapsedMs
                )
            } else {
                logRepository.warn(
                    LogRepository.Category.TRANSLATE,
                    getString(R.string.log_msg_translate_failed_format, settings.translatorEngine.name),
                    elapsedMs = translateElapsedMs,
                )
            }
        }
    }

    /**
     * 悬浮窗口模式（[RenderMode.FLOATING_WINDOW]）：
     * - 批引擎（如 DeepL `prefersBatchFor=true`）：等批 HTTP 完成后整批 `showFullScreen`
     * - 流引擎 + `streamingTranslate=true`：先 `prepareFloatingWindow` 铺占位"…"，
     *   每段 `translateOne` 流式回调到 `updateFloatingWindowText`，与 BLOCKS 模式同等体验
     * - 流引擎但关了 streaming：逐段同步 `translate()`，最后整批显示
     */
    private suspend fun renderFloatingWindow(
        blocks: List<TextBlock>,
        settings: Settings,
        diagId: Long? = null
    ) {
        val routing = translator as? com.gameocr.app.translate.RoutingTranslator
        val useBatch = routing?.prefersBatchFor(settings) ?: translator.prefersBatch
        diagId?.let {
            logVerticalDiag(
                it,
                "renderFloatingWindow useBatch=$useBatch engine=${settings.translatorEngine.name} " +
                    "streaming=${settings.streamingTranslate} count=${blocks.size}"
            )
        }
        if (useBatch) {
            val loopSession = beginLoopTranslation(diagId)
            try {
                val pairs = withContext(Dispatchers.IO) {
                    val sources = blocks.map { it.text }
                    diagId?.let {
                        sources.forEachIndexed { idx, source ->
                            logVerticalDiag(it, "floatingBatch src#${idx + 1} ${source.toDiagText()}")
                        }
                    }
                    val translateStartedAt = System.currentTimeMillis()
                    val translated = runCatching { translator.translateBatch(sources, settings) }
                        .getOrElse { t ->
                            val translateElapsedMs = elapsedSince(translateStartedAt)
                            diagId?.let {
                                logVerticalDiag(
                                    t,
                                    it,
                                    "floatingBatch failed engine=${settings.translatorEngine.name}"
                                )
                            }
                            logRepository.error(
                                LogRepository.Category.TRANSLATE,
                                getString(R.string.log_msg_batch_translate_failed_format, settings.translatorEngine.name),
                                t,
                                elapsedMs = translateElapsedMs
                            )
                            List(sources.size) { "[!] " + (t.message ?: "") }
                        }
                    val translateElapsedMs = elapsedSince(translateStartedAt)
                    blocks.mapIndexed { i, b ->
                        val display = resolveTranslationOutput(
                            initialOutput = translated.getOrNull(i),
                            source = b.text,
                            settings = settings,
                            diagId = diagId,
                            label = "floatingBatch#${i + 1}",
                        )
                        val dst = display.text
                        diagId?.let {
                            logVerticalDiag(
                                it,
                                "floatingBatch dst#${i + 1} ${dst.toDiagText()}"
                            )
                        }
                        if (display.failed) {
                            logRepository.warn(
                                LogRepository.Category.TRANSLATE,
                                getString(R.string.log_msg_translate_failed_format, settings.translatorEngine.name),
                                elapsedMs = translateElapsedMs,
                            )
                        } else {
                            logRepository.pair(
                                LogRepository.Category.TRANSLATE,
                                b.text,
                                dst,
                                elapsedMs = translateElapsedMs
                            )
                        }
                        b.text to dst
                    }
                }
                withContext(Dispatchers.Main) { overlay?.showFullScreen(pairs) }
            } finally {
                finishLoopTranslation(diagId, loopSession)
            }
            return
        }
        // 流模式：先铺占位 → 边翻译边更新。streamingTranslate 关闭时 translateOne 内部走
        // 一次性 translate()，回调一次最终值，行为退化为"逐段同步显示"。
        withContext(Dispatchers.Main) {
            overlay?.prepareFloatingWindow(blocks.map { it.text })
        }
        val loopSession = beginLoopTranslation(diagId)
        scope.launch {
            try {
                blocks.mapIndexed { idx, block ->
                    async {
                        translateOne(block.text, settings, diagId, idx) { partial ->
                            withContext(Dispatchers.Main) {
                                overlay?.updateFloatingWindowText(idx, partial)
                            }
                        }
                    }
                }.awaitAll()
            } finally {
                finishLoopTranslation(diagId, loopSession)
            }
        }
    }

    private suspend fun translateOne(
        text: String,
        settings: Settings,
        diagId: Long? = null,
        blockIndex: Int? = null,
        onPartial: suspend (String) -> Unit
    ) {
        diagId?.let {
            logVerticalDiag(
                it,
                "translateOne begin ${blockIndex.toDiagBlockLabel()} engine=${settings.translatorEngine.name} " +
                    "streaming=${settings.streamingTranslate} ${settings.sourceLang}->${settings.targetLang} " +
                    "src=${text.toDiagText()}"
            )
        }
        val translateStartedAt = System.currentTimeMillis()
        try {
            if (settings.streamingTranslate) {
                // 流式：累计 partial 用于落日志（流末尾的 partial 才是完整译文）
                var lastPartial = ""
                var streamFailed = false
                translator.translateStream(text, settings)
                    .catch { e ->
                        streamFailed = true
                        diagId?.let {
                            logVerticalDiag(e, it, "translateStream failed ${blockIndex.toDiagBlockLabel()}")
                        }
                        onPartial("[!] " + (e.message ?: ""))
                        logRepository.error(
                            LogRepository.Category.TRANSLATE,
                            getString(R.string.log_msg_stream_translate_failed_format, settings.translatorEngine.name),
                            e,
                            elapsedMs = elapsedSince(translateStartedAt)
                        )
                    }
                    .onEach {
                        lastPartial = it
                        onPartial(it)
                    }
                    .collect()
                if (streamFailed) {
                    return
                }
                val display = resolveTranslationOutput(
                    initialOutput = lastPartial,
                    source = text,
                    settings = settings,
                    diagId = diagId,
                    label = blockIndex.toDiagBlockLabel(),
                )
                if (display.text != lastPartial) {
                    onPartial(display.text)
                }
                if (!display.failed) {
                    diagId?.let {
                        logVerticalDiag(
                            it,
                            "translateOne final ${blockIndex.toDiagBlockLabel()} ${display.text.toDiagText()}"
                        )
                    }
                    logRepository.pair(
                        LogRepository.Category.TRANSLATE,
                        text,
                        display.text,
                        elapsedMs = elapsedSince(translateStartedAt)
                    )
                } else {
                    diagId?.let {
                        logVerticalDiag(it, "translateOne final ${blockIndex.toDiagBlockLabel()} blank")
                    }
                    logRepository.warn(
                        LogRepository.Category.TRANSLATE,
                        getString(R.string.log_msg_translate_failed_format, settings.translatorEngine.name),
                        elapsedMs = elapsedSince(translateStartedAt),
                    )
                }
            } else {
                val display = resolveTranslationOutput(
                    initialOutput = translator.translate(text, settings),
                    source = text,
                    settings = settings,
                    diagId = diagId,
                    label = blockIndex.toDiagBlockLabel(),
                )
                val dst = display.text
                diagId?.let {
                    logVerticalDiag(
                        it,
                        "translateOne final ${blockIndex.toDiagBlockLabel()} ${dst.toDiagText()}"
                    )
                }
                onPartial(dst)
                if (display.failed) {
                    logRepository.warn(
                        LogRepository.Category.TRANSLATE,
                        getString(R.string.log_msg_translate_failed_format, settings.translatorEngine.name),
                        elapsedMs = elapsedSince(translateStartedAt),
                    )
                } else {
                    logRepository.pair(
                        LogRepository.Category.TRANSLATE,
                        text,
                        dst,
                        elapsedMs = elapsedSince(translateStartedAt)
                    )
                }
            }
        } catch (e: TranslationException) {
            diagId?.let {
                logVerticalDiag(e, it, "translateOne translation error ${blockIndex.toDiagBlockLabel()}")
            }
            onPartial("[!] " + (e.message ?: ""))
            logRepository.error(
                LogRepository.Category.TRANSLATE,
                getString(R.string.log_msg_translate_failed_format, settings.translatorEngine.name),
                e,
                elapsedMs = elapsedSince(translateStartedAt)
            )
        } catch (t: Throwable) {
            Timber.w(t, "Translate unexpected error")
            diagId?.let {
                logVerticalDiag(t, it, "translateOne unexpected error ${blockIndex.toDiagBlockLabel()}")
            }
            onPartial("[!]")
            logRepository.error(
                LogRepository.Category.TRANSLATE,
                getString(R.string.log_msg_translate_exception_format, settings.translatorEngine.name),
                t,
                elapsedMs = elapsedSince(translateStartedAt)
            )
        }
    }

    private suspend fun resolveTranslationOutput(
        initialOutput: String?,
        source: String,
        settings: Settings,
        diagId: Long?,
        label: String,
    ): TranslationOutputDecision {
        val failureText = "[!] " + getString(R.string.process_text_translate_failed)
        if (
            TranslationOutputPolicy.action(
                output = initialOutput,
                retryEnabled = settings.retryEmptyTranslation,
                attempt = 0,
            ) != EmptyTranslationAction.RETRY
        ) {
            return TranslationOutputPolicy.resolve(initialOutput, failureText)
        }

        diagId?.let { logVerticalDiag(it, "emptyTranslation retry begin $label") }
        val retryOutput = try {
            withContext(Dispatchers.IO) { translator.translate(source, settings) }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "Empty translation retry failed")
            diagId?.let { logVerticalDiag(t, it, "emptyTranslation retry error $label") }
            logRepository.error(
                LogRepository.Category.TRANSLATE,
                getString(R.string.log_msg_translate_failed_format, settings.translatorEngine.name),
                t,
            )
            null
        }
        val display = TranslationOutputPolicy.resolve(retryOutput, failureText)
        diagId?.let {
            logVerticalDiag(
                it,
                "emptyTranslation retry final $label failed=${display.failed} ${display.text.toDiagText()}"
            )
        }
        return display
    }

    private data class DisplayGeometrySnapshot(
        val overlayWidth: Int,
        val overlayHeight: Int,
        val currentBounds: String,
        val maximumBounds: String,
        val rotation: Int,
        val configurationOrientation: Int,
        val densityDpi: Int
    ) {
        fun toDiagString(): String =
            "resources=${overlayWidth}x$overlayHeight currentBounds=$currentBounds " +
                "maximumBounds=$maximumBounds rotation=${rotation.toDiagRotation()} " +
                "config=${configurationOrientation.toDiagOrientation()} densityDpi=$densityDpi"
    }

    private fun currentDisplayGeometry(): DisplayGeometrySnapshot {
        val dm = resources.displayMetrics
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val currentBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.currentWindowMetrics.bounds.toDiagString() }.getOrElse { "unavailable" }
        } else {
            "legacy"
        }
        val maximumBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.maximumWindowMetrics.bounds.toDiagString() }.getOrElse { "unavailable" }
        } else {
            "legacy"
        }
        return DisplayGeometrySnapshot(
            overlayWidth = dm.widthPixels,
            overlayHeight = dm.heightPixels,
            currentBounds = currentBounds,
            maximumBounds = maximumBounds,
            rotation = currentDisplayRotation(wm),
            configurationOrientation = resources.configuration.orientation,
            densityDpi = resources.configuration.densityDpi
        )
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(wm: WindowManager): Int = wm.defaultDisplay.rotation

    private fun projectionDiagnosticSummary(): String =
        (screenshotter as? MediaProjectionScreenshotter)?.diagnosticSummary()
            ?: "type=${screenshotter?.javaClass?.simpleName ?: "null"} ready=${screenshotter?.isReady ?: false}"

    @Suppress("DEPRECATION")
    private fun resizeProjectionForCurrentDisplay(reason: String) {
        val projectionScreenshotter = screenshotter as? MediaProjectionScreenshotter ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            Point().also { wm.defaultDisplay.getRealSize(it) }
        }
        projectionScreenshotter.resizeProjection(target.x, target.y, reason)
    }

    private fun logCaptureGeometry(diagId: Long, stage: String, bitmap: Bitmap) {
        val display = currentDisplayGeometry()
        val diagnostic = diagnoseCaptureGeometry(
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            overlayWidth = display.overlayWidth,
            overlayHeight = display.overlayHeight
        )
        val message =
            "capture#$diagId coordinateSpace stage=$stage ${diagnostic.toDiagString()} " +
                "display=${display.toDiagString()} projection=${projectionDiagnosticSummary()}"
        if (diagnostic.relation == CaptureCoordinateRelation.MATCH) {
            VerticalDiagnosticLog.i(message)
        } else {
            VerticalDiagnosticLog.w("$message COORDINATE_SPACE_WARNING")
        }
    }

    private fun logVerticalDiag(diagId: Long, message: String) {
        VerticalDiagnosticLog.i("capture#$diagId $message")
    }

    private fun logVerticalDiag(t: Throwable, diagId: Long, message: String) {
        VerticalDiagnosticLog.w(t, "capture#$diagId $message")
    }

    private fun logBlankLikeFrame(diagId: Long, label: String, stats: BitmapFrameStats) {
        if (!stats.blankLike) return
        logVerticalDiag(
            diagId,
            "$label blank-like frame; MediaProjection may be seeing a protected or empty surface"
        )
    }

    private fun logVerticalSettings(
        diagId: Long,
        settings: Settings,
        screenW: Int,
        screenH: Int
    ) {
        logVerticalDiag(
            diagId,
            "settings screen=${screenW}x${screenH} region=${settings.captureRegion.toDiagString()} " +
                "source=${settings.sourceLang} target=${settings.targetLang} " +
                "ocr=${settings.ocrEngine.name} translator=${settings.translatorEngine.name} " +
                "paddleVersion=${settings.paddleModelVersion.name} " +
                "baiduEndpoint=${settings.baiduOcrEndpoint.name} baiduLanguage=${settings.baiduOcrLanguage.name} " +
                "dbnet=prob:${settings.dbnetProbThresh.toDiagFloat()},box:${settings.dbnetBoxScoreThresh.toDiagFloat()},unclip:${settings.dbnetUnclipRatio.toDiagFloat()} " +
                "render=${settings.renderMode.name} streaming=${settings.streamingTranslate} " +
                "retryEmptyTranslation=${settings.retryEmptyTranslation} " +
                "loopTrigger=${settings.loopTriggerMode.name} loopIntervalMs=${settings.captureLoopIntervalMs} " +
                "loopPollMs=${LoopFrameStabilityPolicy.pollingIntervalMs(
                    settings.captureLoopIntervalMs,
                    settings.loopTriggerMode == LoopTriggerMode.WAIT_FOR_TEXT_COMPLETE,
                )} loopStableMs=${settings.loopTextStableDurationMs} " +
                "loopSkipSimilar=${settings.loopSkipSimilarFrames} " +
                "loopSimilarity=${settings.loopFrameSimilarityThreshold.toDiagFloat()} " +
                "loopTextRegion=${settings.loopTextRegionMode.name} " +
                "loopTranslateRegionOnly=${settings.loopTranslateRegionOnly} " +
                "autoOrient=${settings.textOrientationAutoDetect} manualOrient=${settings.manualTextOrientation?.name ?: "null"} " +
                "preprocess=${settings.preprocess.toDiagString()} merge=${settings.mergeAdjacentBlocks}/${settings.mergeStrength.name} " +
                "overlayPlacement=${settings.overlayPlacement.name} overlayTextSizeSp=${settings.overlayTextSizeSp} " +
                "allowWrap=${settings.overlayAllowWrap} avoidCollision=${settings.overlayAvoidCollision}"
        )
    }

    private fun logVerticalOrientation(
        diagId: Long,
        stage: String,
        result: OrientationResult
    ) {
        logVerticalDiag(
            diagId,
            "orientation-$stage orientation=${result.orientation.name} conf=${result.confidence.toDiagFloat()} " +
                "raw=${result.rawAngle} source=${result.source}"
        )
    }

    private fun logVerticalBlocks(
        diagId: Long,
        label: String,
        blocks: List<TextBlock>
    ) {
        logVerticalDiag(diagId, "$label count=${blocks.size}")
        blocks.forEachIndexed { index, block ->
            val r = block.boundingBox
            logVerticalDiag(
                diagId,
                "$label #${index + 1} box=${r.toDiagString()} size=${r.width()}x${r.height()} " +
                    "conf=${block.confidence.toDiagFloat()} lang=${block.recognizedLanguage ?: "null"} " +
                    "layout=${block.layoutOrientation?.name ?: "null"} ${block.text.toDiagText()}"
            )
        }
    }

    private fun logVerticalTranslatedBlocks(
        diagId: Long,
        label: String,
        items: List<Pair<TextBlock, String>>
    ) {
        logVerticalDiag(diagId, "$label translated count=${items.size}")
        items.forEachIndexed { index, (block, dst) ->
            logVerticalDiag(
                diagId,
                "$label #${index + 1} src=${block.text.toDiagText()} dst=${dst.toDiagText()} " +
                    "box=${block.boundingBox.toDiagString()} layout=${block.layoutOrientation?.name ?: "null"}"
            )
        }
    }

    private fun String.toDiagText(): String =
        "len=$length text=\"${VerticalDiagnosticLog.text(this)}\""

    private fun Int?.toDiagBlockLabel(): String =
        this?.let { "block#${it + 1}" } ?: "block#?"

    private fun Float.toDiagFloat(): String =
        String.format(Locale.US, "%.3f", this)

    private fun com.gameocr.app.data.PreprocessOptions.toDiagString(): String =
        "upscale2x=$upscale2x,invert=$invert,binarize=$binarize"

    private fun CaptureRegion?.toDiagString(): String =
        this?.let { "(${it.left},${it.top},${it.right},${it.bottom})" } ?: "full"

    private fun android.graphics.Rect.toDiagString(): String =
        "($left,$top,$right,$bottom)"

    private fun logOcrInfo(message: String) {
        Timber.i(message)
    }

    private fun verticalChineseNoBaiduMessage(
        hint: OrientationResult,
        settings: Settings,
        effectiveEngine: OcrEngineKind,
        baiduConfigured: Boolean
    ): String? {
        val lang = settings.sourceLang.trim().lowercase()
        val isChinese = lang == "zh" || lang.startsWith("zh-")
        if (hint.orientation != TextOrientation.VERTICAL_RTL || !isChinese) {
            return null
        }
        val reason = when {
            effectiveEngine == OcrEngineKind.PADDLE_ONNX && baiduConfigured ->
                "Baidu configured but cloud fallback disabled; explicit PaddleOCR stays on-device"
            effectiveEngine == OcrEngineKind.PADDLE_ONNX ->
                "explicit PaddleOCR stays on-device"
            effectiveEngine == OcrEngineKind.ML_KIT_CHINESE && baiduConfigured ->
                "Baidu configured but cloud fallback disabled; offline ML_KIT_CHINESE fallback is active"
            effectiveEngine == OcrEngineKind.ML_KIT_CHINESE ->
                "zh vertical no-key fallback is already ML_KIT_CHINESE"
            baiduConfigured ->
                "Baidu configured but cloud fallback disabled"
            else ->
                "zh vertical route will use ML_KIT_CHINESE no-key fallback"
        }
        return "[orient] %s conf=%.2f src=%s -> keep %s; reason=%s".format(
            hint.orientation.name,
            hint.confidence,
            hint.source,
            effectiveEngine.name,
            reason
        )
    }

    private suspend fun applyOverlayConfig(settings: Settings) {
        val typeface = overlayFontManager.typefaceFor(settings)
        val dockEdgeInsetPx = (settings.floatingButtonDockInsetDp * resources.displayMetrics.density).toInt()
        withContext(Dispatchers.Main) {
            overlay?.apply {
                textSizeSp = settings.overlayTextSizeSp
                alpha = settings.overlayAlpha
                regionOffset = settings.captureRegion?.let { Point(it.left, it.top) } ?: Point(0, 0)
                placement = settings.overlayPlacement
                offsetX = settings.overlayOffsetX
                offsetY = settings.overlayOffsetY
                theme = settings.overlayTheme
                customBg = settings.customBgColor
                customFg = settings.customFgColor
                customBorder = settings.customBorderColor
                customBorderWidthDp = settings.customBorderWidth
                allowWrap = settings.overlayAllowWrap
                avoidCollision = settings.overlayAvoidCollision
                translationBlockInteractionMode = settings.translationBlockInteractionMode
                floatingWindowContentMode = settings.floatingWindowContentMode
                customBorderStyle = settings.customBorderStyle
                overlayTypeface = typeface
                overlayTextStyle = settings.overlayTextStyle.normalized()
                ocrDebugRedBoxActive = DeveloperOcrDebugPolicy.redBoxActive(
                    settings.developerOptionsEnabled,
                    settings.ocrRedBoxModeEnabled,
                )
                ocrDebugShowSourceText = settings.ocrRedBoxShowSourceText
                ocrDebugShowTranslation = settings.ocrRedBoxShowTranslation
                syncFloatingWindowFromSettings(settings)
            }
            // Overlay / floating button both own Android Views; keep every visible update on main.
            floatingButton?.let {
                if (it.sizeDp != settings.floatingButtonSizeDp) {
                    it.sizeDp = settings.floatingButtonSizeDp
                    it.applyResize()
                }
                it.applySnapPreference(settings.floatingButtonSnapToEdge)
                it.autoDockEnabled = settings.floatingButtonAutoDock
                it.dockEdgeInsetPx = dockEdgeInsetPx
                it.menuItemOrder = settings.floatingMenuItemOrder
                it.arcMenuPageSize = settings.arcMenuPageSize
                if (it.skill != settings.floatingButtonSkill) {
                    it.skill = settings.floatingButtonSkill
                    it.applySkillIcon()
                }
            }
        }
    }

    /** 释放截屏相关资源（不停 Service），用于 handleStart 重入时去重 + onDestroy 兜底。 */
    private fun cleanupCapture() {
        loopMode = false
        loopJob?.cancel()
        loopJob = null
        resetLoopFrameHistory()
        resetLoopRuntimeState()
        settingsCollectJob?.cancel()
        settingsCollectJob = null
        overlay?.clear()
        overlay = null
        floatingButton?.hide()
        floatingButton = null
        regionPicker?.dismiss()
        regionPicker = null
        languageQuickSwitch?.dismiss()
        languageQuickSwitch = null
        presetQuickSwitch?.dismiss()
        presetQuickSwitch = null
        wordSelect?.dismiss()
        wordSelect = null
        translationCard?.dismiss()
        translationCard = null
        translationBlockCopyOverlay?.dismiss()
        translationBlockCopyOverlay = null
        screenshotter?.release()
        screenshotter = null
        projection?.stop()
        projection = null
    }

    override fun onDestroy() {
        cleanupCapture()
        // 释放端侧 LLM 权重。runBlocking 在 onDestroy 是可接受的——cleanUp 内部是同步 JNI 调用，
        // 几十毫秒级；不阻塞主线程没意义，等 Mutex 拿到锁就立刻返回。
        runCatching {
            kotlinx.coroutines.runBlocking { llamaEngineHolder.unload() }
        }.onFailure { Timber.w(it, "llamaEngineHolder.unload on destroy") }
        scope.cancel()
        mainScope.cancel()
        CaptureServiceState.setRunning(false)
        Timber.i("CaptureService destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gameocr.app.action.START"
        const val ACTION_STOP = "com.gameocr.app.action.STOP"
        const val ACTION_TRIGGER_ONCE = "com.gameocr.app.action.TRIGGER_ONCE"
        /** 主屏框选区域时走这条——floating window 模式，绕开 Activity 的横屏 long-edge cutout letterbox。 */
        const val ACTION_PICK_REGION = "com.gameocr.app.action.PICK_REGION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_USE_SHIZUKU = "extra_use_shizuku"
        private const val CAPTURE_CHROME_SETTLE_MS = 80L

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).apply { action = ACTION_STOP }
    }
}

private fun Int.toDiagRotation(): String = when (this) {
    Surface.ROTATION_0 -> "ROTATION_0"
    Surface.ROTATION_90 -> "ROTATION_90"
    Surface.ROTATION_180 -> "ROTATION_180"
    Surface.ROTATION_270 -> "ROTATION_270"
    else -> "UNKNOWN($this)"
}

private fun Int.toDiagOrientation(): String = when (this) {
    android.content.res.Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
    android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
    android.content.res.Configuration.ORIENTATION_UNDEFINED -> "UNDEFINED"
    else -> "UNKNOWN($this)"
}
