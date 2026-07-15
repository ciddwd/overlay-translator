package com.gameocr.app.llm

import android.content.Context
import com.gameocr.app.R
import com.gameocr.app.data.LlmMirrorChoice
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.util.HttpResumePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 端侧 LLM GGUF 模型安装器。每个 [LlmModelKind] 对应一个 .gguf 文件，下载到
 * [modelsDir]（`<filesDir>/models/llm/`）。
 *
 * 设计对标 [com.gameocr.app.ocr.PaddleModelInstaller]，差异：
 * - GGUF 单文件（数百 MB），支持 HTTP Range 断点续传：网络中断后重启不需要从 0 开始；
 * - 落盘到 `<filesDir>/models/llm/<fileName>`；下载中临时文件 `.tmp` 占位；
 * - 默认走所选镜像源，支持 Hugging Face 官方、hf-mirror 和用户自定义 CDN。
 *
 * 限流：每 1 MB emit 一次进度（GGUF 文件大，按 200KB emit 太频）。
 */
@Singleton
class LlmModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    baseClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) {

    val modelsDir: File by lazy { File(context.filesDir, "models/llm").apply { mkdirs() } }

    /**
     * 端侧 LLM 模型动辄数百 MB ~ 数 GB，read/call 超时必须放开：
     * - readTimeout = 0：单次 read 之间无上限（OkHttp 默认是 10s，全局 client 设了 60s，
     *   网络稍抖就报 SocketTimeoutException 让用户误以为是错）。
     * - callTimeout = 0：整次调用无上限（默认就是 0，显式声明强调"长跑"语义）。
     * - 复用 [baseClient] 的拦截器（PrivateCleartextInterceptor / 日志）。
     */
    private val client: OkHttpClient = baseClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun checkInstalled(kind: LlmModelKind): File? {
        val file = File(modelsDir, kind.fileName)
        val validationError = validateGgufFile(file, minimumSizeBytes(kind))
        if (validationError != null) {
            if (file.exists()) Timber.w("Invalid LLM model ${file.name}: $validationError")
            return null
        }
        return file
    }

    data class Progress(
        val kind: LlmModelKind,
        val mirror: String,
        val downloaded: Long,
        val total: Long,
        val done: Boolean,
        val error: String? = null,
    )

    /** 下载 [kind] 的模型；HTTP Range 续传；emit 进度。已下载完整文件时直接 short-circuit。 */
    fun download(kind: LlmModelKind): Flow<Progress> = channelFlow {
        val dest = File(modelsDir, kind.fileName)
        val tmp = File(modelsDir, kind.fileName + ".tmp")

        // 已就位（文件存在且大小达到 [MIN_VALID_SIZE_BYTES]）→ 跳过下载。
        // 避免用户重复点 Download 重新拉一遍 GB 级文件，也避免 LFS 重定向 Range 边界条件触发误报错。
        val installedError = validateGgufFile(dest, minimumSizeBytes(kind))
        if (installedError == null) {
            send(Progress(kind, "(already installed)", dest.length(), dest.length(), done = true))
            return@channelFlow
        } else if (dest.exists()) {
            Timber.w("Existing LLM model ${dest.name} is invalid: $installedError")
        }

        val s = settingsRepository.get()
        val urls = mirrorsFor(kind, s.localLlmMirror, s.localLlmMirrorUrl.trim().takeIf { it.isNotBlank() })
        var lastErr: String? = null
        for (url in urls) {
            val mirror = url.substringAfter("//").substringBefore("/")
            try {
                downloadOne(url, tmp, dest, channel, kind, mirror)
                send(Progress(kind, mirror, dest.length(), dest.length(), done = true))
                return@channelFlow
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastErr = "${t.javaClass.simpleName}: ${t.message}"
                Timber.w(t, "LLM 模型镜像失败: $url")
                send(Progress(kind, mirror, 0, 0, done = false, error = lastErr))
            }
        }
        throw RuntimeException(
            context.getString(R.string.err_llm_all_mirrors_failed_format, kind.displayName, lastErr ?: "")
        )
    }.flowOn(Dispatchers.IO)

    /**
     * 按用户在设置里选的 [LlmMirrorChoice] 返回该 kind 的下载源 URL 列表。
     *
     * - [LlmMirrorChoice.HF_OFFICIAL]：huggingface.co 原站；
     * - [LlmMirrorChoice.HF_MIRROR]：国内可直连镜像（当前走 hf-mirror）；
     * - [LlmMirrorChoice.CUSTOM]：用户自定义 base URL 拼 fileName。
     *
     * 每个选项只返回一个 URL—失败时不自动 fallback 到另一镜像，让用户清楚是哪个源挂了。
     */
    private fun mirrorsFor(
        kind: LlmModelKind,
        choice: LlmMirrorChoice,
        customBase: String?,
    ): List<String> = buildList {
        when (choice) {
            LlmMirrorChoice.HF_OFFICIAL -> when (kind) {
                LlmModelKind.SAKURA_1_5B_Q4 ->
                    add("https://huggingface.co/shing3232/Sakura-1.5B-Qwen2.5-v1.0-GGUF-IMX/resolve/main/${kind.fileName}")
                LlmModelKind.HY_MT2_1_8B_Q4_K_M ->
                    add("https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/${kind.fileName}")
            }
            LlmMirrorChoice.HF_MIRROR -> when (kind) {
                LlmModelKind.SAKURA_1_5B_Q4 ->
                    add("https://hf-mirror.com/shing3232/Sakura-1.5B-Qwen2.5-v1.0-GGUF-IMX/resolve/main/${kind.fileName}")
                LlmModelKind.HY_MT2_1_8B_Q4_K_M ->
                    add("https://hf-mirror.com/tencent/Hy-MT2-1.8B-GGUF/resolve/main/${kind.fileName}")
            }
            LlmMirrorChoice.CUSTOM -> {
                if (!customBase.isNullOrBlank()) add(ensureSlash(customBase) + kind.fileName)
            }
        }
    }

    private fun ensureSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    /** 单镜像下载，含 Range 续传。 */
    private suspend fun downloadOne(
        url: String,
        tmp: File,
        dest: File,
        channel: SendChannel<Progress>,
        kind: LlmModelKind,
        mirror: String,
    ) = runInterruptible(Dispatchers.IO) {
        val resumeFrom = if (tmp.exists()) tmp.length() else 0L
        Timber.i("LLM download try url=$url resumeFrom=$resumeFrom")

        val req = Request.Builder().url(url).apply {
            HttpResumePolicy.rangeHeader(resumeFrom)?.let { header("Range", it) }
        }.build()

        var expectedTotal = -1L
        var downloaded = 0L

        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val body = r.body ?: throw RuntimeException("empty body")

            // 部分 CDN 收到 Range 但仍返回 200 + 完整文件，要从 0 开始写。
            val contentLen = body.contentLength().takeIf { it > 0 } ?: -1
            val resumePlan = HttpResumePolicy.responsePlan(resumeFrom, r.code, contentLen)
            expectedTotal = resumePlan.expectedTotal

            downloaded = resumePlan.initialDownloaded
            var lastReported = downloaded

            val raf = RandomAccessFile(tmp, "rw")
            try {
                if (resumePlan.append) raf.seek(resumeFrom) else raf.setLength(0)
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastReported >= REPORT_EVERY_BYTES) {
                            lastReported = downloaded
                            channel.trySend(Progress(kind, mirror, downloaded, expectedTotal, false))
                        }
                    }
                }
            } finally {
                raf.close()
            }
        }

        if (expectedTotal > 0 && downloaded != expectedTotal) {
            throw RuntimeException("download truncated: got $downloaded of $expectedTotal bytes")
        }
        val validationError = validateGgufFile(tmp, minimumSizeBytes(kind))
        if (validationError != null) {
            tmp.delete()
            throw RuntimeException("invalid LLM model ${tmp.name}: $validationError")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            throw RuntimeException(
                context.getString(R.string.err_llm_rename_failed_format, tmp.name, dest.name)
            )
        }
    }

    /** 取消进行中的下载只需 cancel collect 协程；.tmp 留盘允许下次续传。 */

    fun delete(kind: LlmModelKind): Boolean {
        val file = File(modelsDir, kind.fileName)
        val tmp = File(modelsDir, kind.fileName + ".tmp")
        tmp.delete()
        return file.delete()
    }

    /**
     * 从用户选的本地文件 Uri 列表导入 GGUF。按文件名识别归属：
     * - 含 "sakura" → Sakura
     * - 否则按当前选中 [defaultKind] 落盘
     * 返回成功导入数量。
     */
    suspend fun importFromLocal(
        uris: List<android.net.Uri>,
        defaultKind: LlmModelKind,
    ): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        var imported = 0
        for (uri in uris) {
            val name = queryDisplayName(uri) ?: continue
            val lower = name.lowercase()
            val kind = when {
                "sakura" in lower -> LlmModelKind.SAKURA_1_5B_Q4
                "hy-mt2" in lower || "hymt2" in lower -> LlmModelKind.HY_MT2_1_8B_Q4_K_M
                else -> defaultKind
            }
            val dest = File(modelsDir, kind.fileName)
            val tmp = File(modelsDir, kind.fileName + ".tmp")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    }
                } ?: continue
                val validationError = validateGgufFile(tmp, minimumSizeBytes(kind))
                if (validationError != null) {
                    throw RuntimeException("invalid LLM model $name: $validationError")
                }
                if (dest.exists()) dest.delete()
                if (tmp.renameTo(dest)) {
                    imported++
                    Timber.i("LLM imported $name → ${kind.fileName} (${dest.length() / 1024 / 1024}MB)")
                }
            } catch (t: Throwable) {
                tmp.delete()
                Timber.w(t, "Import LLM model failed: $uri")
            }
        }
        imported
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    companion object {
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

        /** 进度 emit 节流：每 1 MB 报一次。 */
        private const val REPORT_EVERY_BYTES = 1024 * 1024L

        /** GGUF 头部 + 元数据至少 ~1 MB；少于此值认定文件损坏。 */
        private const val MIN_VALID_SIZE_BYTES = 4 * 1024 * 1024L

        private const val MIN_EXPECTED_SIZE_PERCENT = 80L

        internal fun minimumSizeBytes(kind: LlmModelKind): Long {
            val expected = kind.approxSizeMb.toLong() * 1024L * 1024L * MIN_EXPECTED_SIZE_PERCENT / 100L
            return maxOf(MIN_VALID_SIZE_BYTES, expected)
        }

        internal fun validateGgufFile(file: File, minSizeBytes: Long): String? {
            if (!file.exists() || !file.isFile) return "missing"
            val length = file.length()
            if (length < minSizeBytes) {
                return "too small: $length bytes, expected at least $minSizeBytes bytes"
            }
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    if (raf.length() < GGUF_MAGIC.size) return "too small: ${raf.length()} bytes"
                    val magic = ByteArray(GGUF_MAGIC.size)
                    raf.readFully(magic)
                    if (!magic.contentEquals(GGUF_MAGIC)) return "invalid GGUF magic"
                }
                null
            }.getOrElse { "unreadable: ${it.message}" }
        }
    }
}
