package com.gameocr.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.gameocr.app.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest

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
    private const val MAX_EXIT_TRACE_CHARS = 16 * 1024
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
        fun customUrl(v: String) = if (v.isBlank()) "<default>" else "<custom>"
        fun brief(v: String, limit: Int = 60): String {
            if (v.isBlank()) return "<empty> (0 chars)"
            val normalized = v.replace('\n', ' ').replace('\r', ' ')
            val head = normalized.take(limit)
            val suffix = if (normalized.length > limit) "..." else ""
            return "$head$suffix (${v.length} chars)"
        }
        fun fontListSummary(fonts: List<OverlayFontEntry>): String =
            if (fonts.isEmpty()) {
                "<empty>"
            } else {
                val preview = fonts.take(3).joinToString(",") { font ->
                    font.displayName.ifBlank { font.fileName }.take(32)
                }
                val suffix = if (fonts.size > 3) ",..." else ""
                "${fonts.size} item(s): $preview$suffix"
            }
        fun presetListSummary(presets: List<TranslationPreset>): String =
            if (presets.isEmpty()) {
                "<empty>"
            } else {
                val preview = presets.take(3).joinToString(",") { preset ->
                    "${preset.id}:${preset.name}".take(48)
                }
                val suffix = if (presets.size > 3) ",..." else ""
                "${presets.size} item(s): $preview$suffix"
            }

        line("baseUrl", mask(s.baseUrl))
        line("apiKey", mask(s.apiKey))
        line("model", s.model)
        line("sourceLang", s.sourceLang)
        line("targetLang", s.targetLang)
        line("promptTemplate", brief(s.promptTemplate))
        line("ocrEngine", s.ocrEngine)
        line("captureLoopIntervalMs", s.captureLoopIntervalMs)
        line("captureRegion", s.captureRegion?.let {
            "${it.right - it.left}x${it.bottom - it.top}@(${it.left},${it.top})"
        } ?: "<full screen>")
        line("captureRegionSavedScreenW", s.captureRegionSavedScreenW)
        line("captureRegionSavedScreenH", s.captureRegionSavedScreenH)
        line("overlayTextSizeSp", s.overlayTextSizeSp)
        line("overlayAlpha", s.overlayAlpha)
        line("overlayFontFileName", s.overlayFontFileName.ifBlank { "<unset>" })
        line("overlayFontDisplayName", s.overlayFontDisplayName.ifBlank { "<unset>" })
        line("overlayFonts", fontListSummary(s.overlayFonts))
        line("streamingTranslate", s.streamingTranslate)
        line("renderMode", s.renderMode)
        line("overlayPlacement", s.overlayPlacement)
        line("overlayTheme", s.overlayTheme)
        line("customBgColor", "0x${"%08X".format(s.customBgColor)}")
        line("customFgColor", "0x${"%08X".format(s.customFgColor)}")
        line("customBorderColor", "0x${"%08X".format(s.customBorderColor)}")
        line("customBorderWidth", s.customBorderWidth)
        line("overlayOffsetX", s.overlayOffsetX)
        line("overlayOffsetY", s.overlayOffsetY)
        line(
            "preprocess",
            "upscale2x=${s.preprocess.upscale2x},invert=${s.preprocess.invert},binarize=${s.preprocess.binarize}"
        )
        line("textOrientationAutoDetect", s.textOrientationAutoDetect)
        line("manualTextOrientation", s.manualTextOrientation?.name ?: "<auto>")
        line("baiduOcrApiKey", mask(s.baiduOcrApiKey))
        line("baiduOcrSecretKey", mask(s.baiduOcrSecretKey))
        line("baiduOcrEndpoint", s.baiduOcrEndpoint)
        line("baiduOcrLanguage", s.baiduOcrLanguage)
        line("umiOcrBaseUrl", customUrl(s.umiOcrBaseUrl))
        line("lunaOcrBaseUrl", customUrl(s.lunaOcrBaseUrl))
        line("tencentSecretId", mask(s.tencentSecretId))
        line("tencentSecretKey", mask(s.tencentSecretKey))
        line("tencentRegion", s.tencentRegion)
        line("tencentOcrEndpoint", s.tencentOcrEndpoint)
        line("tencentOcrLanguage", s.tencentOcrLanguage)
        line("paddleModelVersion", s.paddleModelVersion)
        line("paddleModelMirrorUrl", customUrl(s.paddleModelMirrorUrl))
        line("mangaOcrModelMirrorUrl", customUrl(s.mangaOcrModelMirrorUrl))
        line("orientationModelMirrorUrl", customUrl(s.orientationModelMirrorUrl))
        line("preferShizukuCapture", s.preferShizukuCapture)
        line("a11yVolumeTrigger", s.a11yVolumeTrigger)
        line("translatorEngine", s.translatorEngine)
        line("deeplApiKey", mask(s.deeplApiKey))
        line("deeplPro", s.deeplPro)
        line("deeplProtocol", s.deeplProtocol)
        line("deeplBaseUrl", customUrl(s.deeplBaseUrl))
        line("deeplBearerAuth", s.deeplBearerAuth)
        line("deeplCustomToken", mask(s.deeplCustomToken))
        line("youdaoAppKey", mask(s.youdaoAppKey))
        line("youdaoAppSecret", mask(s.youdaoAppSecret))
        line("volcAccessKeyId", mask(s.volcAccessKeyId))
        line("volcSecretAccessKey", mask(s.volcSecretAccessKey))
        line("volcRegion", s.volcRegion)
        line("baiduFanyiAppId", mask(s.baiduFanyiAppId))
        line("baiduFanyiSecretKey", mask(s.baiduFanyiSecretKey))
        line("floatingButtonSizeDp", s.floatingButtonSizeDp)
        line("floatingButtonX", s.floatingButtonX)
        line("floatingButtonY", s.floatingButtonY)
        line("floatingButtonSnapToEdge", s.floatingButtonSnapToEdge)
        line("floatingButtonAutoDock", s.floatingButtonAutoDock)
        line("floatingButtonDockInsetDp", s.floatingButtonDockInsetDp)
        line("floatingWindowX", s.floatingWindowX)
        line("floatingWindowY", s.floatingWindowY)
        line("floatingWindowWidthDp", s.floatingWindowWidthDp)
        line("floatingWindowHeightDp", s.floatingWindowHeightDp)
        line("floatingWindowContentMode", s.floatingWindowContentMode)
        line("floatingWindowLocked", s.floatingWindowLocked)
        line("customBorderStyle", s.customBorderStyle)
        line("overlayAllowWrap", s.overlayAllowWrap)
        line("overlayAvoidCollision", s.overlayAvoidCollision)
        line("apiTimeoutSeconds", s.apiTimeoutSeconds)
        line("mergeAdjacentBlocks", s.mergeAdjacentBlocks)
        line("mergeStrength", s.mergeStrength)
        line("pinnedLanguages", s.pinnedLanguages.joinToString(",").ifEmpty { "<empty>" })
        line("cleartextAllowedHosts", s.cleartextAllowedHosts.joinToString(",").ifEmpty { "<empty>" })
        line("floatingMenuItemOrder", s.floatingMenuItemOrder.joinToString(",") { it.name }.ifEmpty { "<empty>" })
        line("arcMenuPageSize", s.arcMenuPageSize)
        line("floatingButtonSkill", s.floatingButtonSkill)
        line("dictionaryPrompt", brief(s.dictionaryPrompt))
        line("localLlmTemperature", s.localLlmTemperature)
        line("localLlmTopP", s.localLlmTopP)
        line("localLlmTopK", s.localLlmTopK)
        line("localLlmRepetitionPenalty", s.localLlmRepetitionPenalty)
        line("localLlmContextSize", s.localLlmContextSize)
        line("localLlmMaxNewTokens", s.localLlmMaxNewTokens)
        line("dbnetProbThresh", s.dbnetProbThresh)
        line("dbnetBoxScoreThresh", s.dbnetBoxScoreThresh)
        line("dbnetUnclipRatio", s.dbnetUnclipRatio)
        line("mangaOcrDbnetUnclipRatio", s.mangaOcrDbnetUnclipRatio)
        line("bubbleClusterGap", s.bubbleClusterGap)
        line("localLlmMirror", s.localLlmMirror)
        line("localLlmMirrorUrl", customUrl(s.localLlmMirrorUrl))
        line("translationPresets", presetListSummary(s.translationPresets))
        line("activeTranslationPresetId", s.activeTranslationPresetId.ifBlank { "<none>" })
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
                    formatExitTraceForLog(info.traceInputStream)
                }.getOrNull()
                val msg = buildString {
                    append("[CRASH-EXIT] ").append(reasonName(info.reason))
                    append(" status=").append(info.status)
                    append(" pid=").append(info.pid)
                    append(" process=").append(info.processName)
                    append(" importance=").append(info.importance)
                    append(" pssKb=").append(info.pss)
                    append(" rssKb=").append(info.rss)
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

    internal fun formatExitTraceForLog(input: InputStream?): String? =
        input?.use { formatExitTraceForLog(it.readBytes()) }

    internal fun formatExitTraceForLog(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        if (!isLikelyText(bytes)) {
            return "<binary trace omitted: ${bytes.size} bytes, sha256=${sha256(bytes)}, head=${headHex(bytes)}. " +
                "Native tombstone may be protobuf; capture adb logcat -b crash for the C/C++ backtrace.>"
        }

        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) return null
        return if (text.length <= MAX_EXIT_TRACE_CHARS) {
            text
        } else {
            text.take(MAX_EXIT_TRACE_CHARS) +
                "\n<truncated ${text.length - MAX_EXIT_TRACE_CHARS} chars>"
        }
    }

    private fun isLikelyText(bytes: ByteArray): Boolean {
        var control = 0
        bytes.forEach { raw ->
            val b = raw.toInt() and 0xff
            if (b == 0) return false
            if (b < 0x20 && b != '\n'.code && b != '\r'.code && b != '\t'.code) {
                control++
            }
        }
        return control * 20 <= bytes.size
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun headHex(bytes: ByteArray): String =
        bytes.take(16).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

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
