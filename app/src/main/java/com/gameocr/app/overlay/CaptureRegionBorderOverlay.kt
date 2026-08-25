package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.gameocr.app.capture.CaptureRegion
import com.gameocr.app.capture.CaptureRegionBorderStyle
import com.gameocr.app.capture.captureRegionBorderRect
import com.gameocr.app.capture.normalizedCaptureRegionBorderWidthDp
import com.gameocr.app.capture.shouldShowCaptureRegionBorder
import com.gameocr.app.data.Settings

/** Touch-through overlay that outlines the active custom screenshot region. */
internal class CaptureRegionBorderOverlay(private val context: Context) {

    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private val wm: WindowManager by lazy {
        val defaultWm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@lazy defaultWm
        runCatching {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?: return@runCatching defaultWm
            context.createWindowContext(display, overlayType, null)
                .getSystemService(WindowManager::class.java) ?: defaultWm
        }.getOrElse { defaultWm }
    }

    private var borderView: CaptureRegionBorderView? = null
    private var hiddenForCapture: Boolean = false
    private var hiddenForEditor: Boolean = false

    fun applySettings(settings: Settings) {
        val region = settings.captureRegion
        if (!shouldShowCaptureRegionBorder(settings.captureRegionBorderEnabled, region)) {
            hide()
            return
        }

        val view = borderView ?: CaptureRegionBorderView(context)
        view.configure(
            region = checkNotNull(region),
            color = settings.captureRegionBorderColor,
            widthDp = settings.captureRegionBorderWidthDp,
            style = settings.captureRegionBorderStyle,
        )
        val (screenWidth, screenHeight) = physicalScreenSize()
        val params = layoutParams(screenWidth, screenHeight)
        if (borderView == null) {
            if (runCatching { wm.addView(view, params) }.isSuccess) {
                borderView = view
            }
        } else {
            runCatching { wm.updateViewLayout(view, params) }
        }
        syncVisibility()
    }

    fun setHiddenForCapture(hidden: Boolean): Boolean {
        if (hiddenForCapture == hidden) return false
        val wasVisible = borderView?.visibility == View.VISIBLE
        hiddenForCapture = hidden
        syncVisibility()
        return hidden && wasVisible
    }

    fun setHiddenForEditor(hidden: Boolean) {
        if (hiddenForEditor == hidden) return
        hiddenForEditor = hidden
        syncVisibility()
    }

    fun hide() {
        borderView?.let { runCatching { wm.removeView(it) } }
        borderView = null
    }

    private fun syncVisibility() {
        borderView?.visibility = if (hiddenForCapture || hiddenForEditor) {
            View.INVISIBLE
        } else {
            View.VISIBLE
        }
    }

    private fun layoutParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = MAX_TOUCH_THROUGH_ALPHA
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }

    private fun physicalScreenSize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private companion object {
        const val MAX_TOUCH_THROUGH_ALPHA: Float = 0.8f
    }
}

private class CaptureRegionBorderView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
    }
    private var region: CaptureRegion? = null

    fun configure(
        region: CaptureRegion,
        color: Int,
        widthDp: Int,
        style: CaptureRegionBorderStyle,
    ) {
        this.region = region
        paint.color = color
        paint.strokeWidth = normalizedCaptureRegionBorderWidthDp(widthDp) * density
        paint.strokeCap = if (style == CaptureRegionBorderStyle.DOTTED) {
            Paint.Cap.ROUND
        } else {
            Paint.Cap.BUTT
        }
        paint.pathEffect = when (style) {
            CaptureRegionBorderStyle.SOLID -> null
            CaptureRegionBorderStyle.DASHED -> DashPathEffect(
                floatArrayOf(10f * density, 6f * density),
                0f,
            )
            CaptureRegionBorderStyle.DOTTED -> DashPathEffect(
                floatArrayOf(0.1f, paint.strokeWidth * 2f),
                0f,
            )
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentRegion = region ?: return
        val rect = captureRegionBorderRect(
            region = currentRegion,
            viewportWidth = width,
            viewportHeight = height,
            strokeWidthPx = paint.strokeWidth,
        ) ?: return
        canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)
    }
}
