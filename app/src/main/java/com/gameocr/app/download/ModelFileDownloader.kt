package com.gameocr.app.download

import com.gameocr.app.network.HttpBodyLogPolicy
import com.gameocr.app.util.HttpResumePolicy
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber

internal class ModelDownloadHttpException(val code: Int) : IOException("HTTP $code")
internal class ModelDownloadFileException(message: String, cause: Throwable? = null) : IOException(message, cause)
internal class ModelDownloadResumeException(message: String) : IOException(message)

/** One bounded-memory transfer/verification/commit path for every HTTP model installer. */
@Singleton
class ModelFileDownloader @Inject constructor(baseClient: OkHttpClient) {
    // Large models may take hours; retain the client's connection/read (idle) timeout.
    private val client = baseClient.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
    suspend fun download(
        url: String,
        destination: File,
        validate: (File) -> String?,
        onProgress: (Long, Long) -> Unit,
    ) = locks.getOrPut(destination.canonicalPath) { Mutex() }.withLock {
        val tmp = File(destination.parentFile, destination.name + ".tmp")
        val sidecar = File(tmp.path + ".metadata")
        val key = digestBytes(url.toByteArray()) // Never persist credentials in a URL.
        var saved = readMetadata(sidecar)
        var remote: Metadata? = null
        if (tmp.length() > 0) {
            remote = withResponse(request(url).head().build()) { response ->
                when (response.code) {
                    200 -> metadata(response, key)
                    405, 501 -> null // HEAD unsupported: a conditional GET can still resume.
                    else -> throw ModelDownloadHttpException(response.code)
                }
            }
            if (verifiedComplete(tmp, remote, saved, validate)) {
                runInterruptible(Dispatchers.IO) { commit(tmp, destination, sidecar) }
                onProgress(destination.length(), destination.length())
                Timber.i("[model-download] recovered complete file=%s bytes=%d", destination.name, destination.length())
                return@withLock
            }
        }

        val knownTotal = (remote?.total ?: saved?.total)?.takeIf { it > 0 } ?: Long.MAX_VALUE
        var offset = if (canResume(saved, remote, key) && tmp.length() < knownTotal) tmp.length() else 0L
        var validator = remote?.etag ?: saved?.etag
        // One protocol recovery only. Worker retries must not replay a permanently invalid offset.
        for (recovery in 0..1) {
            val builder = request(url)
            HttpResumePolicy.rangeHeader(offset)?.let { builder.header("Range", it) }
            if (offset > 0) validator?.let { builder.header("If-Range", it) }
            try {
                var rangeFailure: Metadata? = null
                val complete = withResponse(builder.build()) { response ->
                    if (response.code == 416) {
                        rangeFailure = metadata(response, key).copy(
                            total = HttpResumePolicy.unsatisfiedTotal(response.header("Content-Range")) ?: -1,
                        )
                        return@withResponse false
                    }
                    if (response.code != 200 && response.code != 206) throw ModelDownloadHttpException(response.code)
                    if (response.header("Content-Encoding")?.let { !it.equals("identity", ignoreCase = true) } == true) {
                        throw ModelDownloadResumeException("model response ignored identity encoding")
                    }
                    val body = response.body ?: throw IOException("empty body")
                    val plan = HttpResumePolicy.checkedResponsePlan(offset, response.code, body.contentLength(), response.header("Content-Range"))
                        ?: throw ModelDownloadResumeException("HTTP ${response.code}: invalid Content-Range")
                    val current = metadata(response, key).copy(total = plan.expectedTotal)
                    if (plan.append && !sameRepresentation(saved, current) && !sameRepresentation(remote, current)) {
                        throw ModelDownloadResumeException("HTTP 206: model version changed")
                    }
                    val record = current.copy(sha256 = current.sha256 ?: remote?.takeIf { sameRepresentation(it, current) }?.sha256)
                    if (response.code == 206 && record.total < 0 && record.sha256 == null) {
                        throw ModelDownloadResumeException("HTTP 206: unknown complete length")
                    }
                    // Truncate before updating identity. A cancelled full restart cannot label old bytes as new.
                    localIo { RandomAccessFile(tmp, "rw").use { if (!plan.append) it.setLength(0) } }
                    writeMetadata(sidecar, record)
                    var downloaded = plan.initialDownloaded
                    var reported = downloaded
                    onProgress(downloaded, plan.expectedTotal)
                    body.byteStream().use { input ->
                        localIo { RandomAccessFile(tmp, "rw") }.use { output ->
                            localIo { output.seek(downloaded) }
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                checkInterrupted()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                if (plan.expectedTotal >= 0 && downloaded > plan.expectedTotal - count) {
                                    throw ModelDownloadResumeException("download exceeds expected length")
                                }
                                localIo { output.write(buffer, 0, count) }
                                downloaded += count
                                if (downloaded - reported >= 200 * 1024) {
                                    reported = downloaded
                                    onProgress(downloaded, plan.expectedTotal)
                                }
                            }
                        }
                    }
                    if (plan.expectedTotal >= 0 && downloaded != plan.expectedTotal) {
                        throw IOException("download truncated: got $downloaded of ${plan.expectedTotal} bytes")
                    }
                    if (downloaded == 0L) throw ModelDownloadFileException("empty model file")
                    val hash = localIo { sha256(tmp) }
                    if (record.sha256 != null && record.sha256 != hash) {
                        throw ModelDownloadResumeException("invalid model ${destination.name}: SHA-256 mismatch")
                    }
                    localIo { validate(tmp) }?.let { throw ModelDownloadFileException("invalid model ${destination.name}: $it") }
                    // A crash between validation and rename can recover using this verified local checkpoint.
                    writeMetadata(sidecar, record.copy(total = downloaded, completedHash = hash))
                    true
                }
                if (complete) {
                    runInterruptible(Dispatchers.IO) { commit(tmp, destination, sidecar) }
                    onProgress(destination.length(), destination.length())
                    return@withLock
                }
                if (verifiedComplete(tmp, rangeFailure, readMetadata(sidecar), validate)) {
                    runInterruptible(Dispatchers.IO) { commit(tmp, destination, sidecar) }
                    onProgress(destination.length(), destination.length())
                    return@withLock
                }
                // Some CDNs omit Content-Range on 416. Re-read HEAD instead of assuming corruption.
                remote = withResponse(request(url).head().build()) { response ->
                    if (response.code == 200) metadata(response, key) else null
                }
                saved = readMetadata(sidecar)
                if (verifiedComplete(tmp, remote, saved, validate)) {
                    runInterruptible(Dispatchers.IO) { commit(tmp, destination, sidecar) }
                    onProgress(destination.length(), destination.length())
                    return@withLock
                }
                if (recovery == 1 || offset == 0L) throw ModelDownloadHttpException(416)
            } catch (error: ModelDownloadResumeException) {
                if (recovery == 1) throw error
            }
            // Invalid resume only: preserve installed destination; discard only the unusable partial/checkpoint.
            runInterruptible(Dispatchers.IO) {
                localIo { RandomAccessFile(tmp, "rw").use { it.setLength(0) } }
                sidecar.delete()
            }
            offset = 0
            validator = null
            saved = null
            remote = null
        }
    }

    private fun request(url: String): Request.Builder = Request.Builder().url(url)
        .header("Accept-Encoding", "identity")
        .tag(HttpBodyLogPolicy::class.java, HttpBodyLogPolicy.METADATA_ONLY)

    private suspend fun <T> withResponse(request: Request, block: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            runInterruptible(Dispatchers.IO) { call.execute().use(block) }
        } catch (error: Exception) {
            // Socket cancellation can surface as IOException before runInterruptible observes it.
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            cancelCall.cancel()
        }
    }

    private suspend fun verifiedComplete(file: File, remote: Metadata?, saved: Metadata?, validate: (File) -> String?): Boolean =
        runInterruptible(Dispatchers.IO) {
            if (remote == null || remote.total <= 0 || file.length() != remote.total) return@runInterruptible false
            val expected = remote.sha256 ?: saved?.takeIf { sameRepresentation(it, remote) }?.completedHash
                ?: return@runInterruptible false
            localIo { sha256(file) == expected && validate(file) == null }
        }

    internal data class Metadata(
        val key: String,
        val etag: String?,
        val total: Long,
        val sha256: String?,
        val completedHash: String? = null,
    )

    private fun metadata(response: Response, key: String): Metadata {
        val chain = generateSequence(response) { it.priorResponse }.toList()
        val linked = chain.firstOrNull {
            it.request.url.host in setOf("huggingface.co", "hf-mirror.com") && it.header("X-Linked-ETag") != null
        }
        val sha = linked?.header("X-Linked-ETag")?.trim('"')?.lowercase()?.takeIf { SHA256.matches(it) }
        return Metadata(key, strongEtag(response.header("ETag")),
            response.header("Content-Length")?.toLongOrNull() ?: linked?.header("X-Linked-Size")?.toLongOrNull() ?: -1,
            sha)
    }

    private fun readMetadata(file: File): Metadata? = try {
        if (!file.isFile || file.length() > 16 * 1024) null else {
            val p = Properties().apply { file.inputStream().use(::load) }
            p.getProperty("key")?.let { key -> Metadata(key, strongEtag(p.getProperty("etag")),
                p.getProperty("total")?.toLongOrNull() ?: -1,
                p.getProperty("sha256")?.takeIf(SHA256::matches),
                p.getProperty("completedHash")?.takeIf(SHA256::matches)) }
        }
    } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }

    private fun writeMetadata(file: File, data: Metadata) = localIo {
        val staging = File(file.path + ".new")
        val p = Properties().apply {
            setProperty("key", data.key)
            setProperty("total", data.total.toString())
            data.etag?.let { setProperty("etag", it) }
            data.sha256?.let { setProperty("sha256", it) }
            data.completedHash?.let { setProperty("completedHash", it) }
        }
        staging.outputStream().use { p.store(it, null) }
        move(staging, file)
    }

    private fun commit(tmp: File, destination: File, sidecar: File) = localIo {
        checkInterrupted()
        move(tmp, destination)
        sidecar.delete()
        Unit
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private inline fun <T> localIo(action: () -> T): T = try { action() } catch (error: IOException) {
        if (error is InterruptedIOException) throw error
        throw ModelDownloadFileException(error.message ?: "model file I/O failed", error)
    }

    companion object {
        internal const val BUFFER_BYTES = 64 * 1024
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val SHA256 = Regex("[0-9a-f]{64}")
        internal fun strongEtag(value: String?): String? = value?.takeIf {
            it.startsWith('"') && it.endsWith('"') && it.length > 2 && it.length <= 1024
        }
        internal fun sameRepresentation(a: Metadata?, b: Metadata?): Boolean = a != null && b != null && when {
            a.sha256 != null && b.sha256 != null -> a.sha256 == b.sha256
            else -> a.key == b.key && a.etag != null && a.etag == b.etag
        }
        private fun canResume(saved: Metadata?, remote: Metadata?, key: String): Boolean =
            if (remote != null) sameRepresentation(saved, remote) else saved?.key == key && saved.etag != null

        internal fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun digestBytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        private fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("model download cancelled")
        }
    }
}
