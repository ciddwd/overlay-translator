package com.gameocr.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Root state for this app session. DENIED is never retried automatically until the app restarts. */
enum class RootAccess { UNKNOWN, NOT_INSTALLED, GRANTED, DENIED }

/** Common `su` locations for Magisk, KernelSU, APatch and classic SuperSU builds. */
internal val ROOT_SU_PATHS = listOf(
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/su/bin/su",
    "/system/sbin/su",
    "/vendor/bin/su",
    "/debug_ramdisk/su",
    "/data/adb/ksu/bin/su",
    "/data/adb/ap/bin/su",
)

/** Looks for a `su` binary without running it, so checking never shows a root prompt. */
internal fun rootBinaryPresent(exists: (String) -> Boolean): Boolean = ROOT_SU_PATHS.any(exists)

/** Auto may try root when it is installed and the user has not refused it this session. */
internal fun rootUsableForAuto(access: RootAccess): Boolean =
    access == RootAccess.UNKNOWN || access == RootAccess.GRANTED

/**
 * Marker printed after each `screencap` in the persistent shell. The frame length comes from the
 * raw header; the marker tells a 12-byte legacy header apart from the current 16-byte one.
 */
internal const val ROOT_FRAME_MARKER = "STENDMRK"

/**
 * Raw frame framing for the persistent root shell: after reading the legacy length (12 + pixels),
 * the next 4 bytes are either the start of [ROOT_FRAME_MARKER] (legacy header) or the last
 * 4 pixel bytes of a current 16-byte-header frame.
 */
internal fun rootFrameIsLegacy(nextFour: ByteArray): Boolean =
    nextFour.size == 4 && String(nextFour, Charsets.US_ASCII) == ROOT_FRAME_MARKER.substring(0, 4)

/**
 * One long-lived `su` shell per session, so each screenshot does not spawn (and maybe re-prompt) a
 * new root process. All access is serialized; a stuck read is broken by killing the shell.
 */
@Singleton
class RootShell @Inject constructor() {
    private val mutex = Mutex()
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(
        if (rootBinaryPresent { File(it).exists() }) RootAccess.UNKNOWN else RootAccess.NOT_INSTALLED
    )
    val access: StateFlow<RootAccess> = state.asStateFlow()

    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var stdout: InputStream? = null

    /** Re-checks the binary (for example after installing Magisk) without prompting. */
    fun refreshInstalled() {
        val present = rootBinaryPresent { File(it).exists() }
        state.value = when {
            !present -> RootAccess.NOT_INSTALLED
            state.value == RootAccess.NOT_INSTALLED -> RootAccess.UNKNOWN
            else -> state.value
        }
    }

    val isGranted: Boolean get() = state.value == RootAccess.GRANTED

    /**
     * Opens the shell and asks for root once. The root manager may show its own prompt, so this
     * waits up to [timeoutMs]. A refusal is remembered for the session.
     */
    suspend fun ensureGranted(timeoutMs: Long = GRANT_TIMEOUT_MS): Boolean = mutex.withLock {
        when (state.value) {
            RootAccess.NOT_INSTALLED, RootAccess.DENIED -> return@withLock false
            RootAccess.GRANTED -> if (process?.isAliveCompat() == true) return@withLock true
            RootAccess.UNKNOWN -> Unit
        }
        val granted = withContext(Dispatchers.IO) { openShell(timeoutMs) }
        state.value = if (granted) RootAccess.GRANTED else RootAccess.DENIED
        Timber.i("[root] access=%s", state.value)
        granted
    }

    /** Runs a short command as root and returns whether it exited with status 0. */
    suspend fun run(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): Boolean {
        if (!ensureGranted()) return false
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val input = stdout ?: return@withContext false
                val output = stdin ?: return@withContext false
                guarded(timeoutMs) {
                    output.write("$command >/dev/null 2>&1; echo \"__ST_RC=\$?\"\n".toByteArray())
                    output.flush()
                    var line: String
                    do {
                        line = readLine(input) ?: return@guarded false
                    } while (!line.startsWith("__ST_RC="))
                    line.removePrefix("__ST_RC=").trim() == "0"
                } ?: false
            }
        }
    }

    /** Grabs one raw frame through the persistent shell. Null on any failure; the next call reopens the shell. */
    suspend fun captureRaw(): Bitmap? {
        if (!ensureGranted()) return null
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val input = stdout ?: return@withContext null
                val output = stdin ?: return@withContext null
                guarded(CAPTURE_TIMEOUT_MS) {
                    // A failed screencap prints the marker twice, which fills the 16-byte header read.
                    output.write(
                        ("screencap 2>/dev/null || printf '$ROOT_FRAME_MARKER'; " +
                            "printf '$ROOT_FRAME_MARKER'\n").toByteArray()
                    )
                    output.flush()
                    readFrame(input)
                }
            }
        }
    }

    /** One-off PNG fallback for builds whose raw format the decoder does not know. */
    suspend fun capturePng(): Bitmap? = withContext(Dispatchers.IO) {
        if (!isGranted) return@withContext null
        runCatching {
            val png = ProcessBuilder("su", "-c", "screencap -p").start()
            val bytes = png.inputStream.use { it.readBytes() }
            png.waitFor(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            png.destroy()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.onFailure { Timber.w(it, "[root] png fallback failed") }.getOrNull()
    }

    fun close() {
        runCatching { stdin?.write("exit\n".toByteArray()); stdin?.flush() }
        process?.destroy()
        process = null
        stdin = null
        stdout = null
    }

    private fun openShell(timeoutMs: Long): Boolean {
        close()
        return runCatching {
            val started = ProcessBuilder("su").redirectErrorStream(false).start()
            process = started
            stdin = started.outputStream
            stdout = started.inputStream
            val answered = AtomicBoolean(false)
            val watchdog = watchdogScope.launch {
                delay(timeoutMs)
                if (!answered.get()) started.destroy()
            }
            try {
                started.outputStream.write("id -u\n".toByteArray())
                started.outputStream.flush()
                val uid = readLine(started.inputStream)
                answered.set(true)
                uid?.trim() == "0"
            } finally {
                watchdog.cancel()
            }
        }.getOrElse {
            Timber.i(it, "[root] su unavailable")
            false
        }.also { granted -> if (!granted) close() }
    }

    /** Runs [block]; if it takes longer than [timeoutMs] the shell is killed so the blocking read returns. */
    private inline fun <T> guarded(timeoutMs: Long, block: () -> T?): T? {
        val shell = process ?: return null
        val finished = AtomicBoolean(false)
        val watchdog = watchdogScope.launch {
            delay(timeoutMs)
            if (!finished.get()) {
                Timber.w("[root] command timed out; restarting shell")
                shell.destroy()
            }
        }
        return try {
            block().also { finished.set(true) }
        } catch (error: Exception) {
            Timber.w(error, "[root] shell failed; it will be reopened")
            close()
            if (state.value == RootAccess.GRANTED) state.value = RootAccess.UNKNOWN
            null
        } finally {
            watchdog.cancel()
        }
    }

    private fun readFrame(input: InputStream): Bitmap? {
        val header = readExactly(input, CURRENT_HEADER_BYTES) ?: return null
        if (String(header, 0, 8, Charsets.US_ASCII) == ROOT_FRAME_MARKER) {
            Timber.w("[root] screencap produced no frame")
            return null
        }
        val probe = ShizukuRawScreencapDecoder.probeHeader(header) ?: run {
            drainToMarker(input)
            return null
        }
        // Read up to the legacy length, then peek 4 bytes to learn which header this build uses.
        val legacyRest = LEGACY_HEADER_BYTES + probe.pixelByteCount - CURRENT_HEADER_BYTES
        val body = readExactly(input, legacyRest) ?: return null
        val nextFour = readExactly(input, 4) ?: return null
        val frame: ByteArray
        if (rootFrameIsLegacy(nextFour)) {
            readExactly(input, ROOT_FRAME_MARKER.length - 4) ?: return null
            frame = header + body
        } else {
            readExactly(input, ROOT_FRAME_MARKER.length) ?: return null
            frame = header + body + nextFour
        }
        return runCatching { ShizukuRawScreencapDecoder.decode(frame) }
            .onFailure { Timber.w(it, "[root] raw decode failed") }
            .getOrNull()
    }

    private fun drainToMarker(input: InputStream) {
        val marker = ROOT_FRAME_MARKER.toByteArray()
        var matched = 0
        while (matched < marker.size) {
            val next = input.read()
            if (next < 0) return
            matched = if (next.toByte() == marker[matched]) matched + 1 else if (next.toByte() == marker[0]) 1 else 0
        }
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var total = 0
        while (total < count) {
            val read = input.read(buffer, total, count - total)
            if (read < 0) return null
            total += read
        }
        return buffer
    }

    private fun readLine(input: InputStream): String? {
        val bytes = StringBuilder()
        while (true) {
            val next = input.read()
            if (next < 0) return if (bytes.isEmpty()) null else bytes.toString()
            if (next == '\n'.code) return bytes.toString()
            bytes.append(next.toChar())
        }
    }

    private fun Process.isAliveCompat(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)

    private companion object {
        const val GRANT_TIMEOUT_MS = 20_000L
        const val COMMAND_TIMEOUT_MS = 5_000L
        const val CAPTURE_TIMEOUT_MS = 6_000L
        const val LEGACY_HEADER_BYTES = 12
        const val CURRENT_HEADER_BYTES = 16
    }
}

/** Screenshots through the persistent root shell: raw frames first, PNG as the compatibility fallback. */
class RootScreenshotter(private val shell: RootShell) : Screenshotter {
    private val released = AtomicBoolean(false)

    override val isReady: Boolean
        get() = !released.get() && shell.isGranted

    override val minimumLoopObservationIntervalMs: Long
        get() = MIN_LOOP_OBSERVATION_INTERVAL_MS

    override suspend fun capture(): Bitmap? {
        if (released.get()) return null
        shell.captureRaw()?.let { return it }
        Timber.w("[root] raw capture failed; trying PNG")
        return shell.capturePng()
    }

    override fun release() {
        released.set(true)
    }

    private companion object {
        const val MIN_LOOP_OBSERVATION_INTERVAL_MS = 250L
    }
}
