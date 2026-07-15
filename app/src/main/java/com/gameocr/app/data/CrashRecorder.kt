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
    private const val MAX_EXIT_TRACE_BYTES = 64 * 1024
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
    fun formatSettings(s: Settings): String = SettingsFieldPolicy.formatDiagnostics(s)
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
            logRepository.error(
                category = LogRepository.Category.CRASH,
                message = "[CRASH] $content",
                timestamp = crashTimestamp(f.name, f.lastModified()),
            )
            if (!runCatching { f.delete() }.getOrDefault(false)) {
                Timber.w("Failed to consume pending crash file: %s", f.name)
            }
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
                logRepository.error(
                    category = LogRepository.Category.CRASH,
                    message = msg,
                    timestamp = info.timestamp,
                )
            }
        prefs.edit().putLong(KEY_LAST_EXIT_CHECK, System.currentTimeMillis()).apply()
    }

    internal fun formatExitTraceForLog(input: InputStream?): String? = input?.use { source ->
        val buffer = ByteArray(8 * 1024)
        val output = java.io.ByteArrayOutputStream(MAX_EXIT_TRACE_BYTES)
        var truncated = false
        while (output.size() < MAX_EXIT_TRACE_BYTES) {
            val remaining = MAX_EXIT_TRACE_BYTES - output.size()
            val count = source.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        if (output.size() == MAX_EXIT_TRACE_BYTES && source.read() >= 0) {
            truncated = true
        }
        formatExitTraceForLog(output.toByteArray())?.let { formatted ->
            if (truncated) {
                "$formatted\n<trace input truncated after $MAX_EXIT_TRACE_BYTES bytes>"
            } else {
                formatted
            }
        }
    }

    internal fun crashTimestamp(
        fileName: String,
        lastModified: Long,
        fallbackNow: Long = System.currentTimeMillis(),
    ): Long =
        fileName.substringBeforeLast(".crash")
            .toLongOrNull()
            ?.takeIf { it > 0L }
            ?: lastModified.takeIf { it > 0L }
            ?: fallbackNow

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
