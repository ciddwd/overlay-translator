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
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

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

    private val width: Int
    private val height: Int
    private val density: Int

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val released = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Timber.i("MediaProjection stopped by system / user")
            release()
        }
    }

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 用 currentWindowMetrics 跟随当前旋转：横屏拿到 3200x1440，竖屏拿到 1440x3200。
            // 这样 OCR 出的 boundingBox 和悬浮窗坐标系才能对齐。
            val bounds = wm.currentWindowMetrics.bounds
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
    }

    override val isReady: Boolean
        get() = !released.get() && virtualDisplay != null

    override suspend fun capture(): Bitmap? {
        if (!isReady) return null
        val reader = imageReader ?: return null
        // 等到下一帧到达；如果已经有最新帧，直接取
        val image: Image = reader.acquireLatestImage() ?: awaitNextImage(reader) ?: return null
        return try {
            imageToBitmap(image)
        } finally {
            image.close()
        }
    }

    private suspend fun awaitNextImage(reader: ImageReader): Image? =
        suspendCancellableCoroutine { cont ->
            val listener = ImageReader.OnImageAvailableListener { r ->
                val img = r.acquireLatestImage()
                if (img != null && cont.isActive) {
                    r.setOnImageAvailableListener(null, null)
                    cont.resume(img)
                }
            }
            reader.setOnImageAvailableListener(listener, handler)
            cont.invokeOnCancellation {
                reader.setOnImageAvailableListener(null, null)
            }
        }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bmpWidth = width + rowPadding / pixelStride
        val raw = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        // 切掉行尾 padding
        return if (rowPadding == 0) raw else Bitmap.createBitmap(raw, 0, 0, width, height).also {
            if (it !== raw) raw.recycle()
        }
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
}
