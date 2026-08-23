package com.gameocr.app.translate

import android.content.Context
import com.gameocr.app.BuildConfig
import com.gameocr.app.data.RuntimeTranslationVisualContext
import timber.log.Timber
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

internal data class TranslationVisualDebugArtifact(
    val absolutePath: String,
    val byteCount: Int,
    val sha256: String,
)

/** Stores the exact encoded image attached to a visual translation request in debug builds. */
internal class TranslationVisualDebugStore(
    private val directory: File,
    private val enabled: Boolean,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val sequence = AtomicLong()

    @Synchronized
    fun save(
        context: RuntimeTranslationVisualContext,
        origin: String,
    ): TranslationVisualDebugArtifact? {
        if (!enabled || maxFiles <= 0) return null
        return runCatching {
            check(directory.exists() || directory.mkdirs()) {
                "Unable to create visual debug directory: ${directory.absolutePath}"
            }
            val bytes = Base64.getDecoder().decode(context.base64Data)
            check(bytes.size == context.byteCount) {
                "Visual debug payload size mismatch: expected=${context.byteCount} actual=${bytes.size}"
            }
            val timestamp = nowMs()
            val safeOrigin = origin.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
                .ifBlank { "translation" }
            val file = File(
                directory,
                "%013d_%04d_%s_%s.jpg".format(
                    timestamp,
                    sequence.incrementAndGet(),
                    safeOrigin,
                    context.sha256.take(12),
                ),
            )
            file.writeBytes(bytes)
            file.setLastModified(timestamp)
            prune()
            TranslationVisualDebugArtifact(
                absolutePath = file.absolutePath,
                byteCount = bytes.size,
                sha256 = context.sha256,
            ).also { artifact ->
                Timber.tag(LOG_TAG).i(
                    "saved origin=%s path=%s bytes=%d sha256=%s",
                    origin,
                    artifact.absolutePath,
                    artifact.byteCount,
                    artifact.sha256,
                )
            }
        }.getOrElse { error ->
            Timber.tag(LOG_TAG).w(error, "save failed origin=%s", origin)
            null
        }
    }

    private fun prune() {
        val images = directory.listFiles { file ->
            file.isFile && file.extension.equals("jpg", ignoreCase = true)
        }.orEmpty().sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
        images.take((images.size - maxFiles).coerceAtLeast(0)).forEach { file ->
            if (!file.delete()) {
                Timber.tag(LOG_TAG).w("Unable to delete stale visual debug image: %s", file.absolutePath)
            }
        }
    }

    companion object {
        internal const val DEFAULT_MAX_FILES = 20
        private const val DIRECTORY_NAME = "translation-visual-debug"
        private const val LOG_TAG = "TranslationVisualDebug"

        fun forContext(context: Context): TranslationVisualDebugStore {
            val root = context.externalCacheDir ?: context.cacheDir
            return TranslationVisualDebugStore(
                directory = File(root, DIRECTORY_NAME),
                enabled = BuildConfig.DEBUG,
            )
        }
    }
}
