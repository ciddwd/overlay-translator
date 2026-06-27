package com.gameocr.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.gameocr.app.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 闪退记录器：挂全局 [Thread.UncaughtExceptionHandler]，把 stacktrace 写到磁盘；
 * 下次启动 [loadPendingCrashes] 加载到 [LogRepository] 让用户在日志页看 + 导出。
 *
 * - 不联网、不上报；用户复现 → 重启 → 在日志页看到 / 导出后发给维护者
 * - 仅 Java/Kotlin 层未捕获异常能记到；native crash / ANR / OOM kill 由 [loadExitReasons]
 *   通过 [ActivityManager.getHistoricalProcessExitReasons]（API 30+）补
 * - 保留最近 [MAX_FILES] 个 crash，超出删最旧
 *
 * crash 消息统一用 [LogRepository.Category.CAPTURE] + ERROR 级别 + `[CRASH]` 前缀，
 * 这样 LogScreen 的 "Errors" filter 能直接抓到，不引入新 category 避免改 UI。
 */
object CrashRecorder {

    private const val MAX_FILES = 5
    private const val CRASH_DIR_NAME = "crash"
    private const val PREFS_NAME = "crash_recorder"
    private const val KEY_LAST_EXIT_CHECK = "last_exit_check"

    /**
     * 由 [com.gameocr.app.GameOcrApp] collect settings 时持续更新的脱敏快照字符串。
     * crash handler 直接读这个内存值，避免在异常路径走 DataStore IO 二次 crash。
     */
    @Volatile private var settingsSummary: String? = null

    /** 由 App 层调用：每次 settings 变化把脱敏后的快照塞进来。 */
    fun updateSettingsSummary(summary: String) {
        settingsSummary = summary
    }

    /**
     * 把 [com.gameocr.app.data.Settings] 转成多行 key=value 字符串。**敏感字段**（API key /
     * Secret / base URL / prompt 全文）替换为 `<set>` / `<unset>` 或截断，避免泄露给反馈接收方。
     */
    fun formatSettings(s: Settings): String = buildString {
        fun line(k: String, v: Any?) { append("  ").append(k).append(": ").append(v).append('\n') }
        fun mask(v: String) = if (v.isBlank()) "<unset>" else "<set>"

        // OCR
        line("ocrEngine", s.ocrEngine)
        line("baiduOcrEndpoint", s.baiduOcrEndpoint)
        line("baiduOcrLanguage", s.baiduOcrLanguage)
        line("baiduOcrApiKey", mask(s.baiduOcrApiKey))
        line("baiduOcrSecretKey", mask(s.baiduOcrSecretKey))
        line("tencentOcrEndpoint", s.tencentOcrEndpoint)
        line("tencentOcrLanguage", s.tencentOcrLanguage)
        line("tencentSecretId", mask(s.tencentSecretId))
        line("tencentSecretKey", mask(s.tencentSecretKey))
        line("tencentRegion", s.tencentRegion)
        line("paddleModelMirrorUrl", if (s.paddleModelMirrorUrl.isBlank()) "<default>" else "<custom>")

        // 翻译：通用 + LLM
        line("translatorEngine", s.translatorEngine)
        line("baseUrl", mask(s.baseUrl))           // 可能含自架地址，脱敏
        line("apiKey", mask(s.apiKey))
        line("model", s.model)                      // 模型名不敏感，照实
        line("sourceLang", s.sourceLang)
        line("targetLang", s.targetLang)
        // prompt 可能含用户自定义指令；只显示前 60 字 + 总长度，便于判断"是否默认 prompt"
        val promptHead = s.promptTemplate.take(60).replace("\n", "↵")
        line("promptTemplate", "$promptHead... (${s.promptTemplate.length} chars)")
        line("streamingTranslate", s.streamingTranslate)

        // 翻译：DeepL
        line("deeplApiKey", mask(s.deeplApiKey))
        line("deeplPro", s.deeplPro)
        line("deeplProtocol", s.deeplProtocol)
        line("deeplBaseUrl", if (s.deeplBaseUrl.isBlank()) "<unset>" else "<custom>")
        line("deeplBearerAuth", s.deeplBearerAuth)
        line("deeplCustomToken", mask(s.deeplCustomToken))

        // 翻译：有道智云（OCR + 图片翻译共用）
        line("youdaoAppKey", mask(s.youdaoAppKey))
        line("youdaoAppSecret", mask(s.youdaoAppSecret))

        // 翻译：火山引擎
        line("volcAccessKeyId", mask(s.volcAccessKeyId))
        line("volcSecretAccessKey", mask(s.volcSecretAccessKey))
        line("volcRegion", s.volcRegion)

        // 翻译：百度翻译开放平台（fanyi-api，与百度 OCR 不同账号）
        line("baiduFanyiAppId", mask(s.baiduFanyiAppId))
        line("baiduFanyiSecretKey", mask(s.baiduFanyiSecretKey))

        // 截图 + 触发
        line("captureRegion", s.captureRegion?.let {
            "${it.right - it.left}x${it.bottom - it.top}@(${it.left},${it.top})"
        } ?: "<full screen>")
        // region 保存时的屏幕物理尺寸——排查"竖→横屏后框选错位"必备
        line("captureRegionSavedScreen",
            if (s.captureRegionSavedScreenW == 0 && s.captureRegionSavedScreenH == 0) "<legacy>"
            else "${s.captureRegionSavedScreenW}x${s.captureRegionSavedScreenH}"
        )
        line("captureLoopIntervalMs", s.captureLoopIntervalMs)
        line("preferShizukuCapture", s.preferShizukuCapture)
        line("a11yVolumeTrigger", s.a11yVolumeTrigger)

        // 预处理
        line("preprocess.upscale2x", s.preprocess.upscale2x)
        line("preprocess.invert", s.preprocess.invert)
        line("preprocess.binarize", s.preprocess.binarize)

        // 显示 / 渲染
        line("renderMode", s.renderMode)
        line("overlayPlacement", s.overlayPlacement)
        line("overlayTheme", s.overlayTheme)
        line("overlayTextSizeSp", s.overlayTextSizeSp)
        line("overlayAlpha", s.overlayAlpha)
        line("overlayAllowWrap", s.overlayAllowWrap)
        line("overlayAvoidCollision", s.overlayAvoidCollision)
        line("overlayOffsetX", s.overlayOffsetX)
        line("overlayOffsetY", s.overlayOffsetY)
        // CUSTOM 主题颜色（int ARGB → hex 字符串便于人肉对比；非敏感不脱敏）
        line("customBgColor", "0x${"%08X".format(s.customBgColor)}")
        line("customFgColor", "0x${"%08X".format(s.customFgColor)}")
        line("customBorderColor", "0x${"%08X".format(s.customBorderColor)}")
        line("customBorderWidth", s.customBorderWidth)
        line("customBorderStyle", s.customBorderStyle)

        // 悬浮球
        line("floatingButtonSizeDp", s.floatingButtonSizeDp)
        line("floatingButtonX", s.floatingButtonX)
        line("floatingButtonY", s.floatingButtonY)
        line("floatingButtonSnapToEdge", s.floatingButtonSnapToEdge)
        line("floatingButtonAutoDock", s.floatingButtonAutoDock)
        line("floatingButtonDockInsetDp", s.floatingButtonDockInsetDp)

        // 悬浮窗口（FLOATING_WINDOW 模式）
        line("floatingWindowX", s.floatingWindowX)
        line("floatingWindowY", s.floatingWindowY)
        line("floatingWindowWidthDp", s.floatingWindowWidthDp)
        line("floatingWindowHeightDp", s.floatingWindowHeightDp)
        line("floatingWindowContentMode", s.floatingWindowContentMode)
        line("floatingWindowLocked", s.floatingWindowLocked)

        // 高级
        line("apiTimeoutSeconds", s.apiTimeoutSeconds)
        line("mergeAdjacentBlocks", s.mergeAdjacentBlocks)
        line("mergeStrength", s.mergeStrength)
        line("pinnedLanguages", s.pinnedLanguages.joinToString(",").ifEmpty { "<empty>" })
        // 明文 HTTP 白名单（hostname 不算敏感，可看是否用户开了哪些自架站点）
        line("cleartextAllowedHosts", s.cleartextAllowedHosts.joinToString(",").ifEmpty { "<empty>" })
    }

    /** 设备 + 屏幕信息（不会变，理论上 install 时算一次就够，简化成每次写文件时取）。 */
    private fun formatEnvironment(context: Context): String = buildString {
        val dm = context.resources.displayMetrics
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" (brand=").append(Build.BRAND)
        append(", device=").append(Build.DEVICE).append(")\n")
        append("OS: Android ").append(Build.VERSION.RELEASE)
        append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
        append(" / build ").append(Build.DISPLAY).append('\n')
        append("Screen: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
        append(" density=").append(dm.density)
        append(" densityDpi=").append(dm.densityDpi).append('\n')
        append("App: ").append(BuildConfig.VERSION_NAME)
        append(" (versionCode ").append(BuildConfig.VERSION_CODE)
        append(", debug=").append(BuildConfig.DEBUG).append(")\n")
    }

    /** 安装全局未捕获异常 handler。在 [android.app.Application.onCreate] 调一次。 */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(appContext, thread, throwable)
            } catch (t: Throwable) {
                Timber.e(t, "Failed to write crash file")
            }
            // 调用原 handler 让系统继续 crash 流程（保留 logcat / 系统对话框 / 进程退出）
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 加载磁盘上已有的 crash 文件到 [LogRepository]。 */
    fun loadPendingCrashes(context: Context, logRepository: LogRepository) {
        val dir = crashDir(context.applicationContext)
        if (!dir.exists()) return
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".crash") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        // 超过 MAX_FILES 的旧文件删掉，避免无限增长
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
        files.take(MAX_FILES).sortedBy { it.lastModified() }.forEach { f ->
            val content = runCatching { f.readText() }.getOrNull() ?: return@forEach
            logRepository.error(LogRepository.Category.CAPTURE, "[CRASH] $content")
        }
    }

    /**
     * API 30+：补充 native crash / ANR / OOM kill / SIGNALED 等 Java handler 抓不到的退出原因。
     * 用 SharedPreferences 记上次检查时间戳过滤已读，避免重复展示。
     */
    fun loadExitReasons(context: Context, logRepository: LogRepository) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        loadExitReasonsApi30(context.applicationContext, logRepository)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun loadExitReasonsApi30(context: Context, logRepository: LogRepository) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val infos = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        }.getOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_EXIT_CHECK, 0L)
        val abnormal = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_SIGNALED
        )
        infos
            .filter { it.timestamp > lastCheck && it.reason in abnormal }
            .sortedBy { it.timestamp }
            .forEach { info ->
                val trace = runCatching {
                    info.traceInputStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                val msg = buildString {
                    append("[CRASH-EXIT] ").append(reasonName(info.reason))
                    append(" status=").append(info.status)
                    append(" pid=").append(info.pid)
                    if (!info.description.isNullOrBlank()) {
                        append('\n').append(info.description)
                    }
                    if (!trace.isNullOrBlank()) {
                        append("\nTrace:\n").append(trace)
                    }
                }
                logRepository.error(LogRepository.Category.CAPTURE, msg)
            }
        prefs.edit().putLong(KEY_LAST_EXIT_CHECK, System.currentTimeMillis()).apply()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH(Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        else -> "REASON_$reason"
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context)
        if (!dir.exists()) dir.mkdirs()
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val content = buildString {
            append("Time: ").append(System.currentTimeMillis()).append('\n')
            append(formatEnvironment(context))
            append("Thread: ").append(thread.name).append('\n')
            // 脱敏后的设置快照。订阅在 App.onCreate 启动，理论上 onCreate 跑完一拍就有值；
            // 极早期 crash（init 期间）可能还没第一次 emit，那时显示提示。
            append("Settings:\n")
            append(settingsSummary ?: "  <not captured yet>\n")
            append("Stacktrace:\n").append(sw.toString())
        }
        File(dir, "${System.currentTimeMillis()}.crash").writeText(content)
    }

    private fun crashDir(context: Context): File =
        File(context.filesDir, CRASH_DIR_NAME)
}
