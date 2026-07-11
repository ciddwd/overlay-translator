package com.gameocr.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 Shizuku 的截屏实现（experimental）：通过反射调用 Shizuku 的 hidden `newProcess`
 * API 在 shell uid 下执行 raw `screencap`，不兼容时回退到 `screencap -p` PNG。
 *
 * 优势：
 * - 免 MediaProjection 每次系统授权窗（Android 14+ 强制弹）
 *
 * 代价：
 * - 反射 hidden API，未来 Shizuku 大版本变动可能失效
 * - 每次截屏 ~150-300ms，帧率上限 ~5 FPS，仅适合"按需触发"
 *
 * 生产路径建议改为 ShizukuUserService + aidl，本类作为最小可用 PoC。
 */
class ShizukuScreenshotter : Screenshotter {

    private val released = AtomicBoolean(false)

    override val isReady: Boolean
        get() = !released.get() && runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    override suspend fun capture(): Bitmap? = withContext(Dispatchers.IO) {
        if (!isReady) {
            Timber.w("[shizuku-cap] skip: not ready (released=%s, pingBinder=%s)",
                released.get(),
                runCatching { Shizuku.pingBinder() }.getOrDefault(false))
            return@withContext null
        }
        try {
            // Raw screencap avoids vendor-specific PNG stream corruption. Keep PNG as a
            // compatibility fallback for devices whose raw header or pixel format is unknown.
            val rawBitmap = executeScreencap(arrayOf("screencap"), "raw")?.let { bytes ->
                runCatching { ShizukuRawScreencapDecoder.decode(bytes) }
                    .onFailure { Timber.w(it, "[shizuku-cap] raw decode threw") }
                    .getOrNull()
            }
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
                val error = runCatching {
                    val stream = process.javaClass.getMethod("getErrorStream").invoke(process) as java.io.InputStream
                    stream.use { String(it.readBytes()).take(300) }
                }.getOrElse { "<no stderr>" }
                Timber.w(
                    "[shizuku-cap] %s exit=%d, stdoutBytes=%d, stderr=%s",
                    format,
                    exitCode,
                    bytes.size,
                    error
                )
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
    }
}
