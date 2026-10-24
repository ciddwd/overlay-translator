package com.gameocr.app.asr

import android.content.Context
import com.gameocr.app.data.LlmMirrorChoice
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.download.ModelDownloadWorkPolicy
import com.gameocr.app.download.ModelFileDownloader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** One user-picked file for [AsrModelInstaller.import]; [size] is null when the provider omits it. */
internal class AsrImportSource(
    val name: String,
    val size: Long?,
    val open: () -> InputStream,
)

internal sealed interface AsrImportResult {
    data object Imported : AsrImportResult
    /** No picked file matches this manifest entry by name or exact size. */
    data class MissingFile(val name: String) : AsrImportResult
    /** A matched file has the wrong size or SHA-256. */
    data class Mismatch(val name: String, val reason: String) : AsrImportResult
    data class Failed(val error: Exception) : AsrImportResult
}

/**
 * Speech models live in `files/asr/<model>/<revision>/`, separate from GGUF and OCR models.
 * Every file is size- and SHA-256-checked before the shared downloader commits it, so a model is
 * ready exactly when all of its files exist with the pinned sizes.
 */
@Singleton
class AsrModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: ModelFileDownloader,
    private val settingsRepository: SettingsRepository,
) {
    data class Progress(
        val file: String,
        val downloaded: Long,
        val total: Long,
        val done: Boolean,
        val error: String? = null,
        val verifying: Boolean = false,
    )

    private val root: File get() = File(context.filesDir, "asr")
    /** Serializes import, delete and the final install step so a model dir is never half-replaced. */
    private val installLock = Mutex()
    private val releasers = CopyOnWriteArrayList<suspend (AsrModelId) -> Unit>()

    /**
     * A running speech session registers here; before a model's files are deleted or replaced,
     * every releaser is awaited so no native recognizer still maps them.
     */
    fun registerModelReleaser(releaser: suspend (AsrModelId) -> Unit) {
        releasers.addIfAbsent(releaser)
    }

    fun unregisterModelReleaser(releaser: suspend (AsrModelId) -> Unit) {
        releasers.remove(releaser)
    }

    private suspend fun releaseActive(model: AsrModelId) {
        releasers.forEach { it(model) }
    }

    fun modelDir(model: AsrModelId): File = File(File(root, model.name.lowercase()), model.revision)

    fun file(model: AsrModelId, name: String): File = File(modelDir(model), name)

    /** Returns the installed directory, or null while any pinned file is missing. */
    fun checkInstalled(model: AsrModelId): File? {
        val dir = modelDir(model)
        return dir.takeIf { model.files.all { File(dir, it.name).length() == it.bytes } }
    }

    fun installedBytes(model: AsrModelId): Long = if (checkInstalled(model) != null) model.totalBytes else 0L

    suspend fun downloadProbeUrl(model: AsrModelId): String =
        urlsFor(model, model.files.first(), settingsRepository.get().localLlmMirror).first()

    fun download(model: AsrModelId): Flow<Progress> = channelFlow {
        val choice = settingsRepository.get().localLlmMirror
        val dir = modelDir(model).apply { mkdirs() }
        for (spec in model.files) {
            send(Progress(spec.name, 0, spec.bytes, false))
            val dest = File(dir, spec.name)
            if (dest.length() == spec.bytes) {
                send(Progress(spec.name, spec.bytes, spec.bytes, true))
                continue
            }
            var lastError: Exception? = null
            var installed = false
            for (url in urlsFor(model, spec, choice)) {
                try {
                    downloader.download(url, dest, { file ->
                        trySend(Progress(spec.name, file.length(), spec.bytes, false, verifying = true))
                        validate(spec, file)
                    }) { downloaded, total ->
                        trySend(Progress(spec.name, downloaded, total, false))
                    }
                    installed = true
                    send(Progress(spec.name, dest.length(), dest.length(), true))
                    break
                } catch (t: Exception) {
                    if (t is CancellationException) throw t
                    lastError = t
                    if (!ModelDownloadWorkPolicy.mayTryAnotherSource(t)) throw t
                    Timber.w(t, "[asr-model] source failed model=%s url=%s", model.name, url)
                }
            }
            if (!installed) throw lastError ?: IllegalStateException("No download source for ${spec.name}")
        }
        removeOtherRevisions(model)
    }.flowOn(Dispatchers.IO)

    /** Releases an active recognizer first, then removes every revision of [model]. */
    suspend fun delete(model: AsrModelId): Boolean = installLock.withLock {
        releaseActive(model)
        withContext(Dispatchers.IO) { File(root, model.name.lowercase()).deleteRecursively() }
    }

    /**
     * Installs [model] from user-picked files. Each manifest entry is matched by file name, or by
     * its exact size when names were changed; every file is copied to a staging directory while
     * its size and SHA-256 are checked. Only a complete, verified set replaces the installed
     * model, after any active recognizer has released it. A failure leaves the old files intact.
     */
    internal suspend fun import(model: AsrModelId, sources: List<AsrImportSource>): AsrImportResult {
        val matched = when (val match = AsrImportPolicy.match(model, sources)) {
            is AsrImportPolicy.Match.Missing -> return AsrImportResult.MissingFile(match.name)
            is AsrImportPolicy.Match.Matched -> match.files
        }
        val staging = File(root, ".import-${model.name.lowercase()}-${UUID.randomUUID()}")
        try {
            AsrModelFiles.stageVerified(matched, staging)?.let { mismatch ->
                Timber.w("[asr-model] import rejected model=%s file=%s reason=%s", model.name, mismatch.name, mismatch.reason)
                return mismatch
            }
            installLock.withLock {
                releaseActive(model)
                withContext(Dispatchers.IO) {
                    AsrModelFiles.installStaged(staging, modelDir(model))
                    removeOtherRevisions(model)
                }
            }
            Timber.i("[asr-model] imported model=%s", model.name)
            return AsrImportResult.Imported
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "[asr-model] import failed model=%s", model.name)
            return AsrImportResult.Failed(error)
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { staging.deleteRecursively() }
        }
    }

    private fun removeOtherRevisions(model: AsrModelId) {
        File(root, model.name.lowercase()).listFiles()
            ?.filter { it.isDirectory && it.name != model.revision }
            ?.forEach { it.deleteRecursively() }
    }

    companion object {
        internal fun urlsFor(model: AsrModelId, file: AsrModelFile, choice: LlmMirrorChoice): List<String> =
            when (choice) {
                LlmMirrorChoice.HF_MIRROR -> listOf(model.mirrorUrl(file), model.officialUrl(file))
                LlmMirrorChoice.HF_OFFICIAL,
                LlmMirrorChoice.CUSTOM -> listOf(model.officialUrl(file), model.mirrorUrl(file))
            }

        internal fun validate(spec: AsrModelFile, file: File): String? {
            val length = file.length()
            if (length != spec.bytes) return "size $length, expected ${spec.bytes}"
            val hash = sha256(file)
            return if (hash == spec.sha256) null else "SHA-256 mismatch"
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
