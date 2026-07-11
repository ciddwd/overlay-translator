package com.gameocr.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import com.gameocr.app.util.VerticalDiagnosticLog
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProjection + VirtualDisplay + ImageReader 的标准实现。
 *
 * 注意：MediaProjection 必须由调用方在已启动 mediaProjection 类型前台服务的进程中通过
 * [MediaProjectionManager.getMediaProjection] 拿到后再传进来；否则 Android 14+ 会抛
 * SecurityException。本类只负责"用 token 拉流 + 取最新一帧"。
 */
class MediaProjectionScreenshotter(
    private val context: Context,
    private val projection: MediaProjection
) : Screenshotter {

    private val handlerThread = HandlerThread("MediaProjection-Capture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    @Volatile private var width: Int
    @Volatile private var height: Int
    private val density: Int

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val released = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Timber.i("MediaProjection stopped by system / user")
            release()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            VerticalDiagnosticLog.i(
                "mediaProjection callback resize target=${width}x$height current=${this@MediaProjectionScreenshotter.width}x${this@MediaProjectionScreenshotter.height}"
            )
            resizeProjection(width, height, "capturedContentResize")
        }
    }

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 全屏 MediaProjection 用 maximumWindowMetrics；旋转后由 callback / Service 配置变化
            // resize 现有 VirtualDisplay，不能在 Android 14+ 上用同一个 token 再创建一次。
            val bounds = wm.maximumWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val p = Point()
            @Suppress("DEPRECATION") display.getRealSize(p)
            width = p.x
            height = p.y
        }
        density = context.resources.configuration.densityDpi

        projection.registerCallback(projectionCallback, handler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "屏译截屏",
            width, height, density,
            MediaProjectionCaptureConfig.virtualDisplayFlags,
            imageReader!!.surface,
            null, handler
        )
        Timber.i(
            "MediaProjection ready: ${width}x$height @ $density dpi flags=${MediaProjectionCaptureConfig.virtualDisplayFlags}"
        )
        VerticalDiagnosticLog.i(
            "mediaProjection ready configured=${width}x$height densityDpi=$density " +
                "flags=${MediaProjectionCaptureConfig.virtualDisplayFlags}"
        )
    }

    override val isReady: Boolean
        get() = !released.get() && virtualDisplay != null

    internal fun diagnosticSummary(): String =
        "configured=${width}x$height densityDpi=$density ready=$isReady"

    internal fun resizeProjection(targetWidth: Int, targetHeight: Int, reason: String) {
        if (released.get()) return
        VerticalDiagnosticLog.i(
            "mediaProjection resize requested reason=$reason current=${width}x$height " +
                "target=${targetWidth}x$targetHeight"
        )
        handler.post { resizeProjectionOnHandler(targetWidth, targetHeight, reason) }
    }

    private fun resizeProjectionOnHandler(targetWidth: Int, targetHeight: Int, reason: String) {
        if (released.get() || !shouldResizeProjection(width, height, targetWidth, targetHeight)) return
        val display = virtualDisplay ?: return
        val oldReader = imageReader
        val newReader = runCatching {
            ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
        }.onFailure {
            VerticalDiagnosticLog.w(it, "mediaProjection resize reader create failed reason=$reason")
        }.getOrNull() ?: return

        val resized = runCatching {
            display.setSurface(null)
            display.resize(targetWidth, targetHeight, density)
            display.setSurface(newReader.surface)
        }.onFailure {
            runCatching { display.setSurface(oldReader?.surface) }
            VerticalDiagnosticLog.w(
                it,
                "mediaProjection resize failed reason=$reason current=${width}x$height " +
                    "target=${targetWidth}x$targetHeight"
            )
        }.isSuccess
        if (!resized) {
            newReader.close()
            return
        }

        imageReader = newReader
        width = targetWidth
        height = targetHeight
        runCatching { oldReader?.setOnImageAvailableListener(null, null) }
        runCatching { oldReader?.close() }
        VerticalDiagnosticLog.i(
            "mediaProjection resized reason=$reason configured=${width}x$height densityDpi=$density"
        )
    }

    override suspend fun capture(): Bitmap? {
        if (!isReady) return null
        val reader = imageReader ?: return null
        // 等到下一帧到达；如果已经有最新帧，直接取
        val image: Image = awaitNextImage(reader) ?: return null
        return try {
            imageToBitmap(image)
        } finally {
            image.close()
        }
    }

    private suspend fun awaitNextImage(reader: ImageReader): Image? {
        val image = awaitLatestFrame(
            source = object : LatestFrameSource<Image> {
                override fun acquireLatest(): Image? = acquireLatestImageOrNull(reader)

                override fun setOnFrameAvailableListener(listener: (() -> Unit)?) {
                    val callback = listener
                    val imageListener = callback?.let {
                        ImageReader.OnImageAvailableListener { callback() }
                    }
                    reader.setOnImageAvailableListener(imageListener, handler)
                }
            },
            timeoutMs = NEXT_FRAME_TIMEOUT_MS,
            closeUndelivered = { it.close() }
        )
        if (image == null) {
            Timber.w("Timed out waiting for next MediaProjection frame")
        }
        return image
    }

    private fun acquireLatestImageOrNull(reader: ImageReader): Image? =
        runCatching { reader.acquireLatestImage() }
            .onFailure { Timber.w(it, "Failed to acquire latest MediaProjection frame") }
            .getOrNull()

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val frameWidth = image.width
        val frameHeight = image.height
        val rowPadding = rowStride - pixelStride * frameWidth
        val bmpWidth = frameWidth + rowPadding / pixelStride
        val bufferBytes = buffer.remaining()
        val raw = Bitmap.createBitmap(bmpWidth, frameHeight, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        // 切掉行尾 padding
        val output = if (rowPadding == 0) raw else Bitmap.createBitmap(raw, 0, 0, frameWidth, frameHeight).also {
            if (it !== raw) raw.recycle()
        }
        VerticalDiagnosticLog.i(
            "mediaProjection frame image=${image.width}x${image.height} configured=${width}x$height " +
                "pixelStride=$pixelStride rowStride=$rowStride rowPadding=$rowPadding " +
                "bufferBytes=$bufferBytes raw=${bmpWidth}x$frameHeight output=${output.width}x${output.height}"
        )
        return output
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Timber.w(t, "VirtualDisplay release failed")
        }
        try {
            imageReader?.close()
        } catch (t: Throwable) {
            Timber.w(t, "ImageReader close failed")
        }
        try {
            projection.unregisterCallback(projectionCallback)
            projection.stop()
        } catch (t: Throwable) {
            Timber.w(t, "MediaProjection stop failed")
        }
        handlerThread.quitSafely()
        virtualDisplay = null
        imageReader = null
        Timber.i("MediaProjectionScreenshotter released")
    }

    private companion object {
        const val NEXT_FRAME_TIMEOUT_MS = 2_000L
    }
}
