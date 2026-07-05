package com.gameocr.app.ocr

import android.content.Context
import com.gameocr.app.R
import com.gameocr.app.data.LlmMirrorChoice
import com.gameocr.app.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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
import java.nio.charset.StandardCharsets

/**
 * l0wgear/manga-ocr-2025-onnx 模型按需下载器。
 *
 * 7 个文件下载到 `<filesDir>/models/manga_ocr/`：
 *  - encoder_model.onnx  (~22MB)
 *  - decoder_model.onnx  (~118MB)
 *  - vocab.txt           (~24KB)  6144 行，行号即 token id
 *  - config.json / generation_config.json / preprocessor_config.json / special_tokens_map.json
 *
 * **下载源策略**：仅 huggingface.co 原站 + 用户自定义 mirror 字段。**与 [PaddleModelInstaller] 不同**——
 * 实测 hf-mirror.com 对此 repo **不代理**（HTTP/1.1 308 Permanent Redirect 回 huggingface.co/...，
 * 见 plan 文档 Phase 1 调研结论）。所以这里不带社区镜像兜底，失败提示文案强制提醒用户开代理。
 *
 * 模式跟 [PaddleModelInstaller] 一致：OkHttp + .tmp + rename + Flow<Progress>。
 */
@Singleton
class MangaOcrModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {

    val modelsDir: File by lazy { File(context.filesDir, "models/manga_ocr").apply { mkdirs() } }

    data class InstalledFiles(
        val encoder: File,
        val decoder: File,
        val vocab: File,
        val config: File,
        val generationConfig: File,
        val preprocessorConfig: File,
        val specialTokensMap: File
    )

    fun checkInstalled(): InstalledFiles? {
        val files = REQUIRED_FILES.map { File(modelsDir, it) }
        REQUIRED_FILES.zip(files).forEach { (name, file) ->
            val validationError = validateModelFile(name, file)
            if (validationError != null) {
                if (file.exists()) Timber.w("Invalid manga-ocr model $name: $validationError")
                return null
            }
        }
        return InstalledFiles(
            encoder = files[0],
            decoder = files[1],
            vocab = files[2],
            config = files[3],
            generationConfig = files[4],
            preprocessorConfig = files[5],
            specialTokensMap = files[6]
        )
    }

    data class Progress(
        val file: String,
        val mirror: String,
        val downloaded: Long,
        val total: Long,
        val done: Boolean,
        val error: String? = null
    )

    /**
     * 下载 7 个文件，单文件失败 → 整体抛错（与 paddle 一致）。
     *
     * 用户自定义 mirror 优先，原站兜底；两者都失败抛 [RuntimeException]，message 明示
     * `huggingface.co 当前不可达，请检查代理 / VPN`，让用户走「本地导入」按钮。
     */
    fun downloadAll(): Flow<Progress> = channelFlow {
        val settings = settingsRepository.get()
        val legacyMirror = settings.mangaOcrModelMirrorUrl.trim().takeIf { it.isNotBlank() }
        val networkMirror = settings.localLlmMirrorUrl
            .trim()
            .takeIf { settings.localLlmMirror == LlmMirrorChoice.CUSTOM && it.isNotBlank() }
        val userMirror = legacyMirror ?: networkMirror

        for (name in REQUIRED_FILES) {
            val dest = File(modelsDir, name)
            val urls = urlsFor(userMirror, name, settings.localLlmMirror)
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
                    lastErr = "${t.javaClass.simpleName}: ${t.message}"
                    Timber.w(t, "manga-ocr 镜像失败: $url")
                    send(Progress(name, mirror, 0, 0, false, error = lastErr))
                }
            }
            if (!ok) {
                send(Progress(name, "(all failed)", 0, 0, false, error = lastErr ?: "unknown"))
                throw RuntimeException(
                    context.getString(R.string.err_manga_ocr_all_mirrors_failed_format, name, lastErr ?: "")
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun urlsFor(userMirror: String?, file: String): List<String> {
        val list = mutableListOf<String>()
        if (userMirror != null) list += ensureSlash(userMirror) + file
        // 唯一公开源：huggingface.co 原站。hf-mirror 实测 308 不代理。
        list += "https://huggingface.co/$HF_REPO/resolve/main/$file"
        return list
    }

    private suspend fun downloadOne(
        url: String,
        dest: File,
        channel: SendChannel<Progress>,
        name: String,
        mirror: String
    ) = runInterruptible {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        Timber.i("manga-ocr trying: $url")
        var expectedTotal = -1L
        var downloaded = 0L
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val body = r.body ?: throw RuntimeException("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1
            expectedTotal = total
            downloaded = 0L
            var lastReported = 0L
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        // 节流：每 200KB 报一次（与 paddle 一致）
                        if (downloaded - lastReported >= 200 * 1024) {
                            lastReported = downloaded
                            channel.trySend(Progress(name, mirror, downloaded, total, false))
                        }
                    }
                }
            }
        }
        if (expectedTotal > 0 && downloaded != expectedTotal) {
            tmp.delete()
            throw RuntimeException("download truncated: got $downloaded of $expectedTotal bytes")
        }
        val validationError = validateModelFile(name, tmp)
        if (validationError != null) {
            tmp.delete()
            throw RuntimeException("invalid manga-ocr model $name: $validationError")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            throw RuntimeException(
                context.getString(R.string.err_manga_ocr_rename_failed_format, tmp.name, dest.name)
            )
        }
    }

    fun deleteAll() {
        modelsDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 从用户选的本地文件 Uri 导入模型。按文件名精确匹配 7 个目标——manga-ocr 的文件命名
     * 都是标准 HF transformer 导出，没有歧义（不像 paddle 要 fuzzy match det/rec/keys）。
     *
     * 返回成功导入的文件数。未识别的文件名直接跳过（用户多选无害）。
     */
    suspend fun importFromLocal(uris: List<android.net.Uri>): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        var imported = 0
        for (uri in uris) {
            val name = queryDisplayName(uri) ?: continue
            val target = REQUIRED_FILES.firstOrNull { it == name } ?: continue
            val dest = File(modelsDir, target)
            val tmp = File(modelsDir, "$target.tmp")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output ->
                        input.copyTo(output)
                    }
                }
                val validationError = validateModelFile(target, tmp)
                if (validationError != null) {
                    throw RuntimeException("invalid manga-ocr model $name: $validationError")
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    throw RuntimeException(
                        context.getString(R.string.err_manga_ocr_rename_failed_format, tmp.name, dest.name)
                    )
                }
                imported++
                Timber.i("manga-ocr imported $name → $target")
            } catch (t: Throwable) {
                tmp.delete()
                Timber.w(t, "manga-ocr import failed: $name")
            }
        }
        imported
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun ensureSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    companion object {
        const val HF_REPO = "l0wgear/manga-ocr-2025-onnx"

        const val FILE_ENCODER = "encoder_model.onnx"
        const val FILE_DECODER = "decoder_model.onnx"
        const val FILE_VOCAB = "vocab.txt"
        const val FILE_CONFIG = "config.json"
        const val FILE_GEN_CONFIG = "generation_config.json"
        const val FILE_PREPROC_CONFIG = "preprocessor_config.json"
        const val FILE_SPECIAL_TOKENS = "special_tokens_map.json"

        /** 7 个必装文件，按"重要性/大小"排序，下载顺序也是这个——失败时大文件已下完不浪费。 */
        val REQUIRED_FILES = listOf(
            FILE_ENCODER, FILE_DECODER, FILE_VOCAB,
            FILE_CONFIG, FILE_GEN_CONFIG, FILE_PREPROC_CONFIG, FILE_SPECIAL_TOKENS
        )

        internal fun urlsFor(userMirror: String?, file: String, choice: LlmMirrorChoice): List<String> {
            val list = mutableListOf<String>()
            if (userMirror != null) list += ensureSlash(userMirror) + file
            list += when (choice) {
                LlmMirrorChoice.HF_MIRROR -> listOf(hfMirrorUrl(file), officialUrl(file))
                LlmMirrorChoice.HF_OFFICIAL -> listOf(officialUrl(file), hfMirrorUrl(file))
                LlmMirrorChoice.CUSTOM -> listOf(officialUrl(file))
            }
            return list.distinct()
        }

        private fun officialUrl(file: String): String =
            "https://huggingface.co/$HF_REPO/resolve/main/$file"

        private fun hfMirrorUrl(file: String): String =
            "https://hf-mirror.com/$HF_REPO/resolve/main/$file"

        private fun ensureSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

        private const val MIN_ENCODER_BYTES = 10 * 1024 * 1024L
        private const val MIN_DECODER_BYTES = 50 * 1024 * 1024L
        private const val MIN_VOCAB_BYTES = 1024L
        private const val MIN_JSON_BYTES = 2L

        private enum class ModelFileKind { ONNX, VOCAB, JSON }

        private data class ModelFileSpec(
            val minBytes: Long,
            val kind: ModelFileKind,
        )

        private val FILE_SPECS = mapOf(
            FILE_ENCODER to ModelFileSpec(MIN_ENCODER_BYTES, ModelFileKind.ONNX),
            FILE_DECODER to ModelFileSpec(MIN_DECODER_BYTES, ModelFileKind.ONNX),
            FILE_VOCAB to ModelFileSpec(MIN_VOCAB_BYTES, ModelFileKind.VOCAB),
            FILE_CONFIG to ModelFileSpec(MIN_JSON_BYTES, ModelFileKind.JSON),
            FILE_GEN_CONFIG to ModelFileSpec(MIN_JSON_BYTES, ModelFileKind.JSON),
            FILE_PREPROC_CONFIG to ModelFileSpec(MIN_JSON_BYTES, ModelFileKind.JSON),
            FILE_SPECIAL_TOKENS to ModelFileSpec(MIN_JSON_BYTES, ModelFileKind.JSON),
        )

        internal fun validateModelFile(name: String, file: File): String? {
            val spec = FILE_SPECS[name] ?: return "unexpected file: $name"
            if (!file.exists() || !file.isFile) return "missing"
            val length = file.length()
            if (length < spec.minBytes) {
                return "too small: $length bytes, expected at least ${spec.minBytes} bytes"
            }
            return when (spec.kind) {
                ModelFileKind.ONNX -> validateOnnxFile(file)
                ModelFileKind.VOCAB -> validateVocabFile(file)
                ModelFileKind.JSON -> validateJsonFile(file)
            }
        }

        private fun validateOnnxFile(file: File): String? {
            return if (looksLikeTextError(file)) "looks like error response" else null
        }

        private fun validateVocabFile(file: File): String? {
            val lines = runCatching { file.useLines { it.take(4).toList() } }
                .getOrElse { return "unreadable: ${it.message}" }
            if (lines.size < 4) return "vocab too short"
            if (lines[0] != "[PAD]" || lines[2] != "[CLS]" || lines[3] != "[SEP]") {
                return "vocab special tokens mismatch"
            }
            return null
        }

        private fun validateJsonFile(file: File): String? {
            val text = runCatching { file.readText(Charsets.UTF_8).trim() }
                .getOrElse { return "unreadable: ${it.message}" }
            return if (text.startsWith("{") && text.endsWith("}")) null else "invalid json object"
        }

        private fun looksLikeTextError(file: File): Boolean {
            val header = ByteArray(256)
            val read = RandomAccessFile(file, "r").use { it.read(header) }
            if (read <= 0) return true
            val text = String(header, 0, read, StandardCharsets.UTF_8).trimStart().lowercase()
            return text.startsWith("<!doctype") ||
                text.startsWith("<html") ||
                text.startsWith("{") ||
                text.startsWith("<?xml") ||
                text.startsWith("version https://git-lfs")
        }
    }
}
