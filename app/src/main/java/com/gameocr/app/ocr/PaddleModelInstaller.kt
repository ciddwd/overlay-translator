package com.gameocr.app.ocr

import android.content.Context
import com.gameocr.app.R
import com.gameocr.app.data.LlmMirrorChoice
import com.gameocr.app.data.PaddleModelVersion
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

/**
 * PaddleOCR PP-OCRv5 mobile 模型安装器。
 *
 * 三个文件 ([FILE_DET]/[FILE_REC]/[FILE_KEYS_YAML]) 按下载源列表依次尝试：
 * - 1. 用户在 settings 自定义的镜像 URL（如果填了）
 * - 2. PaddlePaddle 官方 Hugging Face 仓库及其 hf-mirror 镜像
 *
 * 每个文件独立 fallback，第一个 HTTP 200 的源就用。所有源都挂才报错。
 *
 * 这样用户开箱即用，不需要手动填 URL；只有所有公开源都挂掉时才需要自架镜像。
 */
@Singleton
class PaddleModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {

    val modelsDir: File by lazy { File(context.filesDir, "models/paddle").apply { mkdirs() } }

    /** 每个版本使用独立子目录；v5 迁入子目录以避免继续复用旧社区模型。 */
    private fun dirFor(version: PaddleModelVersion): File =
        File(modelsDir, modelDirectoryName(version)).apply { mkdirs() }

    data class InstalledFiles(val det: File, val rec: File, val keys: File) {
        val allReady: Boolean get() = det.exists() && rec.exists() && keys.exists()
        val totalBytes: Long get() = det.length() + rec.length() + keys.length()
    }

    fun checkInstalled(): InstalledFiles? =
        PaddleModelVersion.entries.firstNotNullOfOrNull { version -> checkInstalled(version) }

    fun checkInstalled(version: PaddleModelVersion): InstalledFiles? {
        val dir = dirFor(version)
        val det = File(dir, FILE_DET)
        val rec = File(dir, FILE_REC)
        val keys = File(dir, PaddleModelInstaller.keysFileName(version))
        val files = InstalledFiles(det, rec, keys)
        return files.takeIf { it.allReady }
    }

    data class Progress(
        val file: String,
        val mirror: String,
        val downloaded: Long,
        val total: Long,
        val done: Boolean,
        val error: String? = null
    )

    fun downloadAll(): Flow<Progress> = downloadAll(PaddleModelVersion.V5_MOBILE)

    fun downloadAll(version: PaddleModelVersion): Flow<Progress> = channelFlow {
        val settings = settingsRepository.get()
        val legacyMirror = settings.paddleModelMirrorUrl.trim().takeIf { it.isNotBlank() }
        val networkMirror = settings.localLlmMirrorUrl
            .trim()
            .takeIf { settings.localLlmMirror == LlmMirrorChoice.CUSTOM && it.isNotBlank() }
        val userMirror = legacyMirror ?: networkMirror
        val dir = dirFor(version)

        val modelUrls = PaddleModelInstaller.defaultModelUrls(version)
        val keysFile = PaddleModelInstaller.keysFileName(version)

        val plans: List<Triple<String, File, List<String>>> = listOf(
            Triple(FILE_DET, File(dir, FILE_DET), urlsFor(userMirror, FILE_DET, modelUrls.det, settings.localLlmMirror)),
            Triple(FILE_REC, File(dir, FILE_REC), urlsFor(userMirror, FILE_REC, modelUrls.rec, settings.localLlmMirror)),
            Triple(keysFile, File(dir, keysFile), urlsFor(userMirror, keysFile, modelUrls.keys, settings.localLlmMirror))
        )

        for ((name, dest, urls) in plans) {
            if (dest.exists() && dest.length() > 0) {
                send(Progress(name, "(already installed)", dest.length(), dest.length(), true))
                continue
            }
            var ok = false
            var lastErr: String? = null
            for (url in urls) {
                val mirror = url.substringAfter("//").substringBefore("/")
                try {
                    downloadOne(url, dest, channel, name, mirror)
                    ok = true
                    send(Progress(name, mirror, dest.length(), dest.length(), true))
                    break
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastErr = "${t.javaClass.simpleName}: ${t.message}"
                    Timber.w(t, "镜像失败: $url")
                    send(Progress(name, mirror, 0, 0, false, error = lastErr))
                }
            }
            if (!ok) {
                send(Progress(name, "(all failed)", 0, 0, false, error = lastErr ?: "unknown"))
                throw RuntimeException(
                    context.getString(R.string.err_paddle_all_mirrors_failed_format, name, lastErr ?: "")
                )
            }
        }
        if (version == PaddleModelVersion.V5_MOBILE) deleteLegacyV5Files()
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadOne(
        url: String,
        dest: File,
        channel: SendChannel<Progress>,
        name: String,
        mirror: String
    ) = runInterruptible {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val resumeFrom = tmp.length().takeIf { tmp.exists() } ?: 0L
        Timber.i("Trying: $url resumeFrom=$resumeFrom")
        val request = Request.Builder().url(url).apply {
            HttpResumePolicy.rangeHeader(resumeFrom)?.let { header("Range", it) }
        }.build()
        var expectedTotal = -1L
        var downloaded = 0L
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val body = r.body ?: throw RuntimeException("empty body")
            val contentLength = body.contentLength().takeIf { it > 0 } ?: -1L
            val resumePlan = HttpResumePolicy.responsePlan(resumeFrom, r.code, contentLength)
            expectedTotal = resumePlan.expectedTotal
            downloaded = resumePlan.initialDownloaded
            var lastReported = downloaded
            val output = RandomAccessFile(tmp, "rw")
            body.byteStream().use { input ->
                output.use {
                    if (resumePlan.append) output.seek(resumeFrom) else output.setLength(0)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        // 节流：每 200KB 报一次，避免 UI 刷新过频
                        if (downloaded - lastReported >= 200 * 1024) {
                            lastReported = downloaded
                            channel.trySend(Progress(name, mirror, downloaded, expectedTotal, false))
                        }
                    }
                }
            }
        }
        if (expectedTotal > 0 && downloaded != expectedTotal) {
            throw RuntimeException("download truncated: got $downloaded of $expectedTotal bytes")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            throw RuntimeException(
                context.getString(R.string.err_paddle_rename_failed_format, tmp.name, dest.name)
            )
        }
    }

    fun deleteAll() {
        modelsDir.mkdirs()
        modelsDir.listFiles()?.forEach { file ->
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }

    private fun deleteLegacyV5Files() {
        listOf(FILE_DET, FILE_REC, LEGACY_FILE_KEYS).forEach { name ->
            File(modelsDir, name).delete()
            File(modelsDir, "$name.tmp").delete()
        }
    }

    /**
     * 从用户选的本地文件 Uri 导入模型。按文件名自动识别：
     * - 名字含 `det` + 扩展名 .onnx → 检测模型
     * - 名字含 `rec` + 扩展名 .onnx → 识别模型
     * - 所有版本的 .yml/.yaml → keys.yml（官方 inference.yml 内嵌 character_dict）
     *
     * 识别不出来就跳过。返回已成功导入的文件数。
     */
    suspend fun importFromLocal(
        uris: List<android.net.Uri>,
        version: PaddleModelVersion = PaddleModelVersion.V5_MOBILE,
    ): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val dir = dirFor(version)
        var imported = 0
        for (uri in uris) {
            val name = queryDisplayName(uri) ?: continue
            val target = localImportTargetFileName(name, version) ?: continue
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(File(dir, target)).use { output ->
                        input.copyTo(output)
                    }
                }
                imported++
                Timber.i("Imported $name → $target")
            } catch (t: Throwable) {
                Timber.w(t, "Import failed: $name")
            }
        }
        if (version == PaddleModelVersion.V5_MOBILE && checkInstalled(version) != null) {
            deleteLegacyV5Files()
        }
        imported
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val FILE_DET = "det.onnx"
        const val FILE_REC = "rec.onnx"
        /** PaddleOCR 官方字典内嵌在 inference.yml，下载后统一保存为 keys.yml。 */
        const val FILE_KEYS_YAML = "keys.yml"
        private const val LEGACY_FILE_KEYS = "keys.txt"

        internal fun localImportTargetFileName(displayName: String, version: PaddleModelVersion): String? {
            val lower = displayName.lowercase()
            return when {
                lower.endsWith(".onnx") && "det" in lower -> FILE_DET
                lower.endsWith(".onnx") && "rec" in lower -> FILE_REC
                lower.endsWith(".yml") || lower.endsWith(".yaml") -> FILE_KEYS_YAML
                else -> null
            }
        }

        /**
         * PaddlePaddle 官方 PP-OCRv5 mobile ONNX 下载源，固定到已验证的仓库提交：
         * - det: PaddlePaddle/PP-OCRv5_mobile_det_onnx
         * - rec + keys: PaddlePaddle/PP-OCRv5_mobile_rec_onnx
         */
        private const val V5_DET_REVISION = "e6f4fa85f00e168c862bc462aebca69eef9b3d3d"
        private const val V5_REC_REVISION = "ed152b8b495f84de93cda5709d768548a9127622"
        private val DEFAULT_DET_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/$V5_DET_REVISION/inference.onnx",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/$V5_DET_REVISION/inference.onnx"
        )
        private val DEFAULT_REC_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/resolve/$V5_REC_REVISION/inference.onnx",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/resolve/$V5_REC_REVISION/inference.onnx"
        )
        private val DEFAULT_KEYS_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/resolve/$V5_REC_REVISION/inference.yml",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/resolve/$V5_REC_REVISION/inference.yml"
        )

        /**
         * PP-OCRv6 tiny ONNX 模型下载源（PaddlePaddle 官方 HuggingFace 仓库）。
         * - det: PaddlePaddle/PP-OCRv6_tiny_det_onnx (inference.onnx → 重命名为 det.onnx)
         * - rec: PaddlePaddle/PP-OCRv6_tiny_rec_onnx (inference.onnx → 重命名为 rec.onnx)
         * - keys: PP-OCRv6 rec inference.yml（内嵌 character_dict，保存为 keys.yml）
         *
         * v6 tiny 用 PPLCNetV4 backbone + CTC+NRTR 多头解码器，比 v5 mobile 更轻量、更快。
         */
        private val V6_TINY_DET_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/resolve/main/inference.onnx",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_tiny_det_onnx/resolve/main/inference.onnx"
        )
        private val V6_TINY_REC_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.onnx",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.onnx"
        )
        private val V6_TINY_YML_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.yml",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.yml"
        )

        private val V6_SMALL_DET_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx"
        )
        private val V6_SMALL_REC_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx"
        )
        private val V6_SMALL_YML_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml"
        )

        private val V6_MEDIUM_DET_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx"
        )
        private val V6_MEDIUM_REC_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.onnx",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.onnx"
        )
        private val V6_MEDIUM_YML_URLS = listOf(
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.yml",
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.yml"
        )

        internal data class ModelUrls(
            val det: List<String>,
            val rec: List<String>,
            val keys: List<String>,
        )

        internal fun keysFileName(@Suppress("UNUSED_PARAMETER") version: PaddleModelVersion): String = FILE_KEYS_YAML

        internal fun modelDirectoryName(version: PaddleModelVersion): String = version.dirName

        internal fun defaultModelUrls(version: PaddleModelVersion): ModelUrls = when (version) {
            PaddleModelVersion.V5_MOBILE -> ModelUrls(DEFAULT_DET_URLS, DEFAULT_REC_URLS, DEFAULT_KEYS_URLS)
            PaddleModelVersion.V6_TINY -> ModelUrls(V6_TINY_DET_URLS, V6_TINY_REC_URLS, V6_TINY_YML_URLS)
            PaddleModelVersion.V6_SMALL -> ModelUrls(V6_SMALL_DET_URLS, V6_SMALL_REC_URLS, V6_SMALL_YML_URLS)
            PaddleModelVersion.V6_MEDIUM -> ModelUrls(V6_MEDIUM_DET_URLS, V6_MEDIUM_REC_URLS, V6_MEDIUM_YML_URLS)
        }

        internal fun urlsFor(
            userMirror: String?,
            file: String,
            defaults: List<String>,
            choice: LlmMirrorChoice,
        ): List<String> {
            val list = mutableListOf<String>()
            if (userMirror != null) list += ensureSlash(userMirror) + file
            list += orderedDefaults(defaults, choice)
            return list.distinct()
        }

        private fun orderedDefaults(defaults: List<String>, choice: LlmMirrorChoice): List<String> {
            val hfMirror = defaults.filter { it.startsWith(HF_MIRROR_PREFIX) }
            val official = defaults.filterNot { it.startsWith(HF_MIRROR_PREFIX) }
            return when (choice) {
                LlmMirrorChoice.HF_MIRROR -> hfMirror + official
                LlmMirrorChoice.HF_OFFICIAL -> official + hfMirror
                LlmMirrorChoice.CUSTOM -> defaults
            }
        }

        private fun ensureSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

        private const val HF_MIRROR_PREFIX = "https://hf-mirror.com/"
    }
}
