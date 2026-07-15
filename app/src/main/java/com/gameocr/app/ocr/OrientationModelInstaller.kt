package com.gameocr.app.ocr

import android.content.Context
import com.gameocr.app.R
import com.gameocr.app.data.LlmMirrorChoice
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.util.HttpResumePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * PaddleOCR PP-LCNet orientation ONNX model installer.
 *
 * The verified official document orientation source is:
 * https://huggingface.co/PaddlePaddle/PP-LCNet_x1_0_doc_ori_onnx/resolve/main/inference.onnx
 *
 * The verified official text-line orientation source is:
 * https://huggingface.co/PaddlePaddle/PP-LCNet_x0_25_textline_ori_onnx/resolve/main/inference.onnx
 *
 * Files are stored locally with canonical names so runtime code is independent from upstream naming.
 */
@Singleton
class OrientationModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) {

    val modelsDir: File by lazy { File(context.filesDir, "models/doc_orientation").apply { mkdirs() } }
    private val bundledDisabledMarker: File
        get() = File(modelsDir, BUNDLED_DISABLED_MARKER)

    data class InstalledFiles(val doc: File, val textLine: File?) {
        val model: File get() = doc
        val allReady: Boolean get() = textLine != null
        val totalBytes: Long get() = doc.length() + (textLine?.length() ?: 0L)
    }

    fun checkInstalled(): InstalledFiles? {
        val model = File(modelsDir, FILE_MODEL)
        val textLine = File(modelsDir, FILE_TEXTLINE_MODEL)
        var validationError = validateModelFile(FILE_MODEL, model)
        if (validationError != null &&
            !bundledDisabledMarker.exists() &&
            installBundledModel(FILE_MODEL, BUNDLED_ASSET_MODEL, model)
        ) {
            validationError = validateModelFile(FILE_MODEL, model)
        }
        if (validationError != null) {
            if (model.exists()) Timber.w("Invalid orientation model: $validationError")
            return null
        }
        var textLineError = validateModelFile(FILE_TEXTLINE_MODEL, textLine)
        if (textLineError != null &&
            !bundledDisabledMarker.exists() &&
            installBundledModel(FILE_TEXTLINE_MODEL, BUNDLED_ASSET_TEXTLINE_MODEL, textLine)
        ) {
            textLineError = validateModelFile(FILE_TEXTLINE_MODEL, textLine)
        }
        if (textLineError != null && textLine.exists()) {
            Timber.w("Invalid text-line orientation model: $textLineError")
        }
        return InstalledFiles(model, textLine.takeIf { textLineError == null })
    }

    fun checkFullyInstalled(): InstalledFiles? = checkInstalled()?.takeIf { it.allReady }

    data class Progress(
        val file: String,
        val mirror: String,
        val downloaded: Long,
        val total: Long,
        val done: Boolean,
        val error: String? = null,
    )

    fun downloadAll(): Flow<Progress> = channelFlow {
        val settings = settingsRepository.get()
        val legacyMirror = settings.orientationModelMirrorUrl.trim().takeIf { it.isNotBlank() }
        val networkMirror = settings.localLlmMirrorUrl
            .trim()
            .takeIf { settings.localLlmMirror == LlmMirrorChoice.CUSTOM && it.isNotBlank() }
        for (spec in MODEL_SPECS) {
            val dest = File(modelsDir, spec.fileName)
            if (validateModelFile(spec.fileName, dest) == null) {
                send(Progress(spec.fileName, "(already installed)", dest.length(), dest.length(), true))
                continue
            }
            var ok = false
            var lastErr: String? = null

            for (url in urlsFor(spec.fileName, settings.localLlmMirror, networkMirror, legacyMirror)) {
                val mirror = url.substringAfter("//").substringBefore("/")
                try {
                    downloadOne(url, dest, channel, spec.fileName, mirror)
                    ok = true
                    send(Progress(spec.fileName, mirror, dest.length(), dest.length(), true))
                    break
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastErr = "${t.javaClass.simpleName}: ${t.message}"
                    Timber.w(t, "orientation model mirror failed: $url")
                    send(Progress(spec.fileName, mirror, 0, 0, false, error = lastErr))
                }
            }

            if (!ok) {
                send(Progress(spec.fileName, "(all failed)", 0, 0, false, error = lastErr ?: "unknown"))
                throw RuntimeException(
                    context.getString(
                        R.string.err_orientation_model_all_mirrors_failed_format,
                        spec.fileName,
                        lastErr ?: "",
                    )
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadOne(
        url: String,
        dest: File,
        channel: SendChannel<Progress>,
        name: String,
        mirror: String,
    ) = runInterruptible {
        modelsDir.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val resumeFrom = tmp.length().takeIf { tmp.exists() } ?: 0L
        Timber.i("orientation model trying: $url resumeFrom=$resumeFrom")
        var expectedTotal = -1L
        var downloaded = 0L
        val request = Request.Builder().url(url).apply {
            HttpResumePolicy.rangeHeader(resumeFrom)?.let { header("Range", it) }
        }.build()
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
        val validationError = validateModelFile(name, tmp)
        if (validationError != null) {
            tmp.delete()
            throw RuntimeException("invalid orientation model $name: $validationError")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            throw RuntimeException(
                context.getString(R.string.err_orientation_model_rename_failed_format, tmp.name, dest.name)
            )
        }
        bundledDisabledMarker.delete()
    }

    fun deleteAll() {
        modelsDir.mkdirs()
        modelsDir.listFiles()?.forEach { it.delete() }
        bundledDisabledMarker.createNewFile()
    }

    private fun installBundledModel(fileName: String, assetPath: String, dest: File): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".asset.tmp")
        return try {
            modelsDir.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            val validationError = validateModelFile(fileName, tmp)
            if (validationError != null) {
                Timber.w("Bundled orientation model $fileName invalid: $validationError")
                tmp.delete()
                false
            } else {
                if (dest.exists()) dest.delete()
                if (tmp.renameTo(dest)) {
                    bundledDisabledMarker.delete()
                    Timber.i("Installed bundled orientation model $fileName: ${dest.length()} bytes")
                    true
                } else {
                    tmp.delete()
                    Timber.w("Failed to install bundled orientation model $fileName")
                    false
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            Timber.d(t, "Bundled orientation model $fileName unavailable")
            false
        }
    }

    suspend fun importFromLocal(uris: List<android.net.Uri>): Int = withContext(Dispatchers.IO) {
        var imported = 0
        for (uri in uris) {
            val name = queryDisplayName(uri) ?: continue
            val target = localImportTargetFileName(name, uris.size) ?: continue
            val dest = File(modelsDir, target)
            val tmp = File(modelsDir, "$target.tmp")
            try {
                modelsDir.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                val validationError = validateModelFile(target, tmp)
                if (validationError != null) {
                    throw RuntimeException("invalid orientation model $name: $validationError")
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    throw RuntimeException(
                        context.getString(R.string.err_orientation_model_rename_failed_format, tmp.name, dest.name)
                    )
                }
                bundledDisabledMarker.delete()
                imported++
                Timber.i("orientation model imported $name -> $target")
            } catch (t: Throwable) {
                tmp.delete()
                Timber.w(t, "orientation model import failed: $name")
            }
        }
        imported
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    companion object {
        const val FILE_MODEL = "doc_ori.onnx"
        const val FILE_TEXTLINE_MODEL = "textline_ori.onnx"
        const val BUNDLED_DISABLED_MARKER = ".bundled_disabled"
        const val UPSTREAM_FILE_MODEL = "inference.onnx"
        const val BUNDLED_ASSET_MODEL = "models/doc_orientation/$FILE_MODEL"
        const val BUNDLED_ASSET_TEXTLINE_MODEL = "models/doc_orientation/$FILE_TEXTLINE_MODEL"
        const val HF_REPO = "PaddlePaddle/PP-LCNet_x1_0_doc_ori_onnx"
        const val TEXTLINE_HF_REPO = "PaddlePaddle/PP-LCNet_x0_25_textline_ori_onnx"
        const val DEFAULT_MODEL_URL = "https://huggingface.co/$HF_REPO/resolve/main/$UPSTREAM_FILE_MODEL"
        const val HF_MIRROR_MODEL_URL = "https://hf-mirror.com/$HF_REPO/resolve/main/$UPSTREAM_FILE_MODEL"
        const val DEFAULT_TEXTLINE_MODEL_URL =
            "https://huggingface.co/$TEXTLINE_HF_REPO/resolve/main/$UPSTREAM_FILE_MODEL"
        const val HF_MIRROR_TEXTLINE_MODEL_URL =
            "https://hf-mirror.com/$TEXTLINE_HF_REPO/resolve/main/$UPSTREAM_FILE_MODEL"

        private const val MIN_DOC_MODEL_BYTES = 1024 * 1024L
        private const val MIN_TEXTLINE_MODEL_BYTES = 256 * 1024L

        internal data class ModelSpec(
            val fileName: String,
            val defaultUrl: String,
            val mirrorUrl: String,
        )

        internal val MODEL_SPECS = listOf(
            ModelSpec(FILE_MODEL, DEFAULT_MODEL_URL, HF_MIRROR_MODEL_URL),
            ModelSpec(FILE_TEXTLINE_MODEL, DEFAULT_TEXTLINE_MODEL_URL, HF_MIRROR_TEXTLINE_MODEL_URL),
        )

        internal fun urlsFor(
            fileName: String,
            choice: LlmMirrorChoice,
            networkMirror: String?,
            legacyMirror: String?,
        ): List<String> {
            val spec = MODEL_SPECS.first { it.fileName == fileName }
            val urls = mutableListOf<String>()
            val userMirror = legacyMirror ?: networkMirror
            if (userMirror != null) {
                val lower = userMirror.lowercase()
                if (lower.endsWith(".onnx")) {
                    val looksTextLine = "textline" in lower || "text_line" in lower
                    if ((fileName == FILE_MODEL && !looksTextLine) ||
                        (fileName == FILE_TEXTLINE_MODEL && looksTextLine)
                    ) {
                        urls += userMirror
                    }
                } else {
                    urls += ensureSlash(userMirror) + fileName
                    if (fileName == FILE_MODEL) {
                        urls += ensureSlash(userMirror) + UPSTREAM_FILE_MODEL
                    }
                }
            }
            urls += when (choice) {
                LlmMirrorChoice.HF_MIRROR -> listOf(spec.mirrorUrl, spec.defaultUrl)
                LlmMirrorChoice.HF_OFFICIAL -> listOf(spec.defaultUrl, spec.mirrorUrl)
                LlmMirrorChoice.CUSTOM -> listOf(spec.defaultUrl)
            }
            return urls.distinct()
        }

        private fun ensureSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

        internal fun localImportTargetFileName(displayName: String, selectedFileCount: Int): String? {
            val lower = displayName.lowercase()
            if (!lower.endsWith(".onnx")) return null
            if ("textline" in lower || "text_line" in lower) return FILE_TEXTLINE_MODEL
            if (selectedFileCount == 1) return FILE_MODEL
            return when {
                "doc_ori" in lower -> FILE_MODEL
                "orientation" in lower -> FILE_MODEL
                "pp-lcnet" in lower -> FILE_MODEL
                "pplcnet" in lower -> FILE_MODEL
                lower == UPSTREAM_FILE_MODEL -> FILE_MODEL
                else -> null
            }
        }

        internal fun validateModelFile(name: String, file: File): String? {
            val minBytes = when (name) {
                FILE_MODEL -> MIN_DOC_MODEL_BYTES
                FILE_TEXTLINE_MODEL -> MIN_TEXTLINE_MODEL_BYTES
                else -> return "unexpected file: $name"
            }
            if (!file.exists() || !file.isFile) return "missing"
            val length = file.length()
            if (length < minBytes) {
                return "too small: $length bytes, expected at least $minBytes bytes"
            }
            return if (looksLikeTextError(file)) "looks like error response" else null
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
