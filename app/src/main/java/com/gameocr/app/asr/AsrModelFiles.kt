package com.gameocr.app.asr

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** File steps of a model import, kept free of Android types so they are tested on the JVM. */
internal object AsrModelFiles {
    /** Copies every matched file into a new [staging] dir; returns the first size or hash mismatch. */
    suspend fun stageVerified(
        matched: List<Pair<AsrModelFile, AsrImportSource>>,
        staging: File,
    ): AsrImportResult.Mismatch? = withContext(Dispatchers.IO) {
        if (!staging.mkdirs()) throw IOException("cannot create staging directory")
        for ((spec, source) in matched) {
            val (length, hash) = source.open().use { input -> copyAndHash(input, File(staging, spec.name), spec.bytes) }
            if (length != spec.bytes) return@withContext AsrImportResult.Mismatch(spec.name, "size $length, expected ${spec.bytes}")
            if (hash != spec.sha256) return@withContext AsrImportResult.Mismatch(spec.name, "SHA-256 mismatch")
        }
        null
    }

    /**
     * Swaps the verified [staging] directory in at [target] with renames. The previous copy is
     * moved aside first and restored if the swap fails, so a model is never half-replaced.
     */
    fun installStaged(staging: File, target: File) {
        target.parentFile?.mkdirs()
        val trash = File(target.parentFile, ".trash-${UUID.randomUUID()}")
        val hadOld = target.exists()
        if (hadOld && !target.renameTo(trash)) throw IOException("cannot move the installed model aside")
        if (!staging.renameTo(target)) {
            if (hadOld) trash.renameTo(target)
            throw IOException("cannot install the imported model")
        }
        trash.deleteRecursively()
    }

    private suspend fun copyAndHash(input: InputStream, dest: File, limit: Long): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        dest.outputStream().use { output ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                // A file larger than the manifest entry can never match; stop copying it.
                if (total > limit) return total to ""
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }
}
