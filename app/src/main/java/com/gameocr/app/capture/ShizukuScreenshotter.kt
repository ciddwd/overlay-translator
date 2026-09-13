package com.gameocr.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ShizukuCaptureRoute { ACCESSIBILITY, SHELL, UNAVAILABLE }

internal fun resolveShizukuCaptureRoute(
    accessibilityReady: Boolean,
    shizukuReady: Boolean,
): ShizukuCaptureRoute = when {
    accessibilityReady -> ShizukuCaptureRoute.ACCESSIBILITY
    shizukuReady -> ShizukuCaptureRoute.SHELL
    else -> ShizukuCaptureRoute.UNAVAILABLE
}

/**
 * Uses the direct accessibility screenshot API whenever the Shizuku-assisted service is ready.
 * Raw shell `screencap` remains the compatibility fallback, with PNG as its final fallback.
 *
 * 优势：
 * - 免 MediaProjection 每次系统授权窗（Android 14+ 强制弹）
 *
 * 代价：
 * - 反射 hidden API，未来 Shizuku 大版本变动可能失效
 * - shell fallback 每次截屏 ~150-300ms，帧率上限 ~5 FPS，仅适合"按需触发"
 */
class ShizukuScreenshotter : Screenshotter {

    private val released = AtomicBoolean(false)
    private val accessibilityFastPath = AccessibilityScreenshotter()

    private fun shizukuReady(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    override val isReady: Boolean
        get() = !released.get() && resolveShizukuCaptureRoute(
            accessibilityReady = accessibilityFastPath.isReady,
            shizukuReady = shizukuReady(),
        ) != ShizukuCaptureRoute.UNAVAILABLE

    override val minimumLoopObservationIntervalMs: Long
        get() = MIN_LOOP_OBSERVATION_INTERVAL_MS

    override suspend fun capture(): Bitmap? = withContext(Dispatchers.IO) {
        val shellReady = shizukuReady()
        val route = if (released.get()) ShizukuCaptureRoute.UNAVAILABLE else {
            resolveShizukuCaptureRoute(accessibilityFastPath.isReady, shellReady)
        }
        if (route == ShizukuCaptureRoute.UNAVAILABLE) {
            Timber.w(
                "[shizuku-cap] skip: not ready (released=%s, accessibility=%s, pingBinder=%s)",
                released.get(),
                accessibilityFastPath.isReady,
                shellReady,
            )
            return@withContext null
        }
        if (route == ShizukuCaptureRoute.ACCESSIBILITY) {
            accessibilityFastPath.capture()?.let { bitmap ->
                Timber.d("[shizuku-cap] accessibility fast path ok %dx%d", bitmap.width, bitmap.height)
                return@withContext bitmap
            }
            Timber.w("[shizuku-cap] accessibility fast path failed; trying shell fallback")
            if (!shellReady) return@withContext null
        }
        try {
            // Raw screencap avoids vendor-specific PNG stream corruption. Keep PNG as a
            // compatibility fallback for devices whose raw header or pixel format is unknown.
            val rawBitmap = executeRawScreencap()
            if (rawBitmap != null) {
                Timber.d("[shizuku-cap] raw ok %dx%d", rawBitmap.width, rawBitmap.height)
                return@withContext rawBitmap
            }

            Timber.w("[shizuku-cap] raw capture/decode failed; retrying PNG")
            val pngBytes = executeScreencap(arrayOf("screencap", "-p"), "png")
                ?: return@withContext null
            val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            if (bitmap == null) {
                Timber.w(
                    "[shizuku-cap] decode PNG failed, bytes=%d, head=%s",
                    pngBytes.size,
                    pngBytes.take(8).joinToString { "%02x".format(it) }
                )
            } else {
                Timber.d("[shizuku-cap] png ok %dx%d", bitmap.width, bitmap.height)
            }
            bitmap
        } catch (t: Throwable) {
            Timber.w(t, "[shizuku-cap] capture threw")
            null
        }
    }

    private fun executeScreencap(command: Array<String>, format: String): ByteArray? {
        val process = invokeNewProcess(command)
        if (process == null) {
            Timber.w(
                "[shizuku-cap] newProcess returned null — reflection target missing " +
                    "(R8 stripped Shizuku.newProcess?)"
            )
            return null
        }
        return try {
            val out = ByteArrayOutputStream(16 * 1024 * 1024)
            val input = process.javaClass.getMethod("getInputStream").invoke(process) as java.io.InputStream
            input.use { it.copyTo(out) }
            val exitCode = runCatching {
                process.javaClass.getMethod("waitFor").invoke(process) as Int
            }.getOrElse { -1 }
            val bytes = out.toByteArray()
            if (exitCode != 0) {
                logProcessFailure(process, format, exitCode, bytes.size)
                null
            } else if (bytes.isEmpty()) {
                Timber.w("[shizuku-cap] %s exit=0 but empty payload", format)
                null
            } else {
                bytes
            }
        } finally {
            runCatching { process.javaClass.getMethod("destroy").invoke(process) }
        }
    }

    /**
     * 反射调用 `Shizuku.newProcess(String[], String[]?, String?)`，13.x 被标 @hide。
     *
     * 失败兜底：先按"声明方法 + 三参签名"精确查；找不到再扫所有同名方法。Release 包靠
     * proguard-rules.pro 里 `-keep class rikka.shizuku.Shizuku` 防止 R8 重命名 / 移除。
     */
    private fun invokeNewProcess(cmd: Array<String>): Any? {
        val cls = Shizuku::class.java
        val direct = runCatching {
            cls.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        }.getOrNull()
        val method = direct ?: cls.declaredMethods.firstOrNull { it.name == "newProcess" }
        if (method == null) {
            val available = cls.declaredMethods.joinToString { it.name }
            Timber.w("[shizuku-cap] no newProcess method found. declaredMethods=%s", available.take(500))
            return null
        }
        method.isAccessible = true
        return method.invoke(null, cmd, null, null)
    }

    override fun release() {
        released.set(true)
        accessibilityFastPath.release()
    }

    /**
     * Raw screencap is a full uncompressed frame. Allocate its final buffer from the header once
     * instead of growing a ByteArrayOutputStream and copying it again with toByteArray().
     */
    private fun executeRawScreencap(): Bitmap? {
        val process = invokeNewProcess(arrayOf("screencap")) ?: return null
        return try {
            val input = process.javaClass.getMethod("getInputStream").invoke(process) as java.io.InputStream
            val header = ByteArray(RAW_CURRENT_HEADER_BYTES)
            var total = 0
            input.use { stream ->
                while (total < header.size) {
                    val count = stream.read(header, total, header.size - total)
                    if (count < 0) break
                    total += count
                }
                val probe = ShizukuRawScreencapDecoder.probeHeader(header.copyOf(total))
                    ?: return null
                val currentFrameBytes = RAW_CURRENT_HEADER_BYTES + probe.pixelByteCount
                val frame = ByteArray(currentFrameBytes)
                header.copyInto(frame, endIndex = total)
                while (total < frame.size) {
                    val count = stream.read(frame, total, frame.size - total)
                    if (count < 0) break
                    total += count
                }
                val discard = ByteArray(1024)
                while (stream.read(discard) >= 0) Unit

                val exitCode = runCatching {
                    process.javaClass.getMethod("waitFor").invoke(process) as Int
                }.getOrElse { -1 }
                if (exitCode != 0) {
                    logProcessFailure(process, "raw", exitCode, total)
                    return null
                }
                val legacyFrameBytes = RAW_LEGACY_HEADER_BYTES + probe.pixelByteCount
                val exactFrame = when (total) {
                    currentFrameBytes -> frame
                    legacyFrameBytes -> frame.copyOf(total)
                    else -> {
                        Timber.w(
                            "[shizuku-cap] raw size mismatch bytes=%d expected=%d or %d",
                            total,
                            currentFrameBytes,
                            legacyFrameBytes,
                        )
                        return null
                    }
                }
                runCatching { ShizukuRawScreencapDecoder.decode(exactFrame) }
                    .onFailure { Timber.w(it, "[shizuku-cap] raw decode threw") }
                    .getOrNull()
            }
        } finally {
            runCatching { process.javaClass.getMethod("destroy").invoke(process) }
        }
    }

    private fun logProcessFailure(process: Any, format: String, exitCode: Int, stdoutBytes: Int) {
        val error = runCatching {
            val stream = process.javaClass.getMethod("getErrorStream").invoke(process) as java.io.InputStream
            stream.use { String(it.readBytes()).take(300) }
        }.getOrElse { "<no stderr>" }
        Timber.w(
            "[shizuku-cap] %s exit=%d, stdoutBytes=%d, stderr=%s",
            format,
            exitCode,
            stdoutBytes,
            error,
        )
    }

    private companion object {
        const val MIN_LOOP_OBSERVATION_INTERVAL_MS = 350L
        const val RAW_LEGACY_HEADER_BYTES = 12
        const val RAW_CURRENT_HEADER_BYTES = 16
    }
}
