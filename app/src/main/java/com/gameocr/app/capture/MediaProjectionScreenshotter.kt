package com.gameocr.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import com.gameocr.app.util.VerticalDiagnosticLog
import com.gameocr.app.util.physicalDisplaySize
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/** MediaProjection + VirtualDisplay + ImageReader screenshot implementation. */
class MediaProjectionScreenshotter(
    private val context: Context,
    private val projection: MediaProjection,
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
                "mediaProjection callback resize target=${width}x$height " +
                    "current=${this@MediaProjectionScreenshotter.width}x${this@MediaProjectionScreenshotter.height}"
            )
            resizeProjection(width, height, "capturedContentResize")
        }
    }

    init {
        // Keep MediaProjection and overlay windows in the same full-display coordinate space.
        val physicalSize = physicalDisplaySize(context)
        width = physicalSize.width
        height = physicalSize.height
        density = context.resources.configuration.densityDpi

        projection.registerCallback(projectionCallback, handler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "屏译截屏",
            width,
            height,
            density,
            MediaProjectionCaptureConfig.virtualDisplayFlags,
            imageReader!!.surface,
            null,
            handler,
        )
        Timber.i(
            "MediaProjection ready: ${width}x$height @ $density dpi " +
                "flags=${MediaProjectionCaptureConfig.virtualDisplayFlags}"
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
            closeUndelivered = { it.close() },
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
        val output = if (rowPadding == 0) {
            raw
        } else {
            Bitmap.createBitmap(raw, 0, 0, frameWidth, frameHeight).also {
                if (it !== raw) raw.recycle()
            }
        }
        VerticalDiagnosticLog.i(
            "mediaProjection frame image=${image.width}x${image.height} configured=${width}x$height " +
                "pixelStride=$pixelStride rowStride=$rowStride rowPadding=$rowPadding " +
                "bufferBytes=$bufferBytes raw=${bmpWidth}x$frameHeight " +
                "output=${output.width}x${output.height}"
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
