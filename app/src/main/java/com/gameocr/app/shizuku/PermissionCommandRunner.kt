package com.gameocr.app.shizuku

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.ShizukuRemoteProcess

internal data class PermissionCommandResult(val exitCode: Int, val output: String)

/** Do not use Process.waitFor(timeout): its exitValue polling is unsafe over Shizuku Binder. */
internal fun waitForPermissionProcess(process: Process, timeoutMillis: Long): Boolean {
    require(process is ShizukuRemoteProcess)
    return process.waitForTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
}

/** Owns the process, drains both pipes, and cleans up before joining the reader coroutines. */
internal suspend fun runPermissionCommand(
    process: Process,
    timeoutMillis: Long = 5_000,
    waitForProcess: (Process, Long) -> Boolean = ::waitForPermissionProcess,
): PermissionCommandResult? {
    try {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMillis) {
                coroutineScope {
                    var stdout: InputStream? = null
                    var stderr: InputStream? = null
                    try {
                        stdout = process.inputStream
                        stderr = process.errorStream
                        val outputStream = stdout
                        val errorStream = stderr
                        val output = async(Dispatchers.IO) { readPermissionOutput(outputStream) }
                        val errors = async(Dispatchers.IO) { readPermissionOutput(errorStream) }
                        // Short remote waits keep cancellation responsive without polling exitValue.
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (waitForProcess(process, minOf(100L, timeoutMillis))) break
                        }
                        currentCoroutineContext().ensureActive()
                        val exitCode = process.exitValue()
                        errors.await() // Drain errors, but never log service lists or command contents.
                        PermissionCommandResult(exitCode, output.await().trim())
                    } finally {
                        // Must happen inside coroutineScope: blocked pipe readers otherwise prevent exit.
                        runCatching { process.destroy() }
                        runCatching { stdout?.close() }
                        runCatching { stderr?.close() }
                        runCatching { process.outputStream.close() }
                    }
                }
            }
        }
    } finally {
        // Also covers cancellation before the IO dispatcher starts executing the block.
        runCatching { process.destroy() }
    }
}

private fun readPermissionOutput(stream: InputStream): String {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        // Never truncate a settings list and then write it back, which could remove other services.
        if (bytes.size() + count > 64 * 1024) throw IOException("Permission command output exceeds limit")
        bytes.write(buffer, 0, count)
    }
    return bytes.toString(Charsets.UTF_8.name())
}
