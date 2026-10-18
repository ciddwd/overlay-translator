package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.*
import com.gameocr.app.data.Settings
import kotlin.math.min

/** A narrow, optional entry target. The original pointer stream stays here until it lifts. */
internal class GestureEdgeOverlay(
    private val context: Context,
    private val onEnter: () -> Boolean,
    private val onInput: (Int, Float, Float) -> Unit,
) {
    private val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val wm by lazy {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val windowContext = if (Build.VERSION.SDK_INT >= 31 && display != null) context.createWindowContext(display, type, null) else context
        windowContext.getSystemService(WindowManager::class.java)
    }
    private val density get() = context.resources.displayMetrics.density
    private var root: View? = null
    private var right = false
    private var heightPercent = 22
    private var entered = false
    private var startX = 0f
    private var strokeActive = false
    private var suppressed = false

    fun configure(settings: Settings, enabled: Boolean) {
        if (!enabled) { dismiss(); return }
        if (root != null && right == settings.gestureEdgeRight && heightPercent == settings.gestureEdgeHeightPercent) return
        dismiss()
        right = settings.gestureEdgeRight
        heightPercent = settings.gestureEdgeHeightPercent.coerceIn(10, 40)
        val bounds = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        val height = (bounds.height() * heightPercent / 100f).toInt()
        val view = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x590F172A }
            override fun onDraw(canvas: Canvas) {
                if (suppressed) return
                val x = if (this@GestureEdgeOverlay.right) width - 7 * density else 3 * density
                canvas.drawRoundRect(x, 0f, x + 4 * density, height.toFloat(), 2 * density, 2 * density, paint)
            }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                if (Build.VERSION.SDK_INT >= 29) {
                    val half = min(h / 2, (100 * density).toInt())
                    systemGestureExclusionRects = listOf(Rect(0, h / 2 - half, w, h / 2 + half))
                }
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (suppressed) return false
                        startX = event.rawX; entered = false; strokeActive = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!strokeActive) return true
                        if (!entered) {
                            val inward = (event.rawX - startX) * if (this@GestureEdgeOverlay.right) -1 else 1
                            if (inward >= min(80 * density, bounds.width() * .24f)) {
                                entered = onEnter()
                                if (entered) {
                                    invalidate()
                                    onInput(MotionEvent.ACTION_DOWN, event.rawX, event.rawY)
                                }
                            }
                        } else onInput(MotionEvent.ACTION_MOVE, event.rawX, event.rawY)
                    }
                    MotionEvent.ACTION_UP -> {
                        if (entered) onInput(MotionEvent.ACTION_UP, event.rawX, event.rawY)
                        entered = false; strokeActive = false; invalidate()
                        applySuppression()
                        performClick()
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                        if (entered) onInput(MotionEvent.ACTION_CANCEL, event.rawX, event.rawY)
                        entered = false; strokeActive = false; invalidate()
                        applySuppression()
                    }
                }
                return true
            }
            override fun performClick(): Boolean { super.performClick(); return true }
        }
        val params = WindowManager.LayoutParams((20 * density).toInt(), height, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or if (right) Gravity.RIGHT else Gravity.LEFT
            y = (bounds.height() * .30f - height / 2f).toInt()
        }
        try { wm.addView(view, params); root = view; applySuppression() }
        catch (error: RuntimeException) { android.util.Log.e("GestureEdge", "Cannot open edge trigger", error) }
    }

    fun isShown() = root?.visibility == View.VISIBLE

    fun setSuppressed(value: Boolean) {
        suppressed = value
        root?.invalidate()
        applySuppression()
    }
    private fun applySuppression() {
        // Do not remove the original touch window mid-stream when the drawing layer opens.
        root?.visibility = if (suppressed && !strokeActive) View.GONE else View.VISIBLE
    }
    fun dismiss() {
        root?.let { wm.removeViewImmediate(it) }
        root = null; entered = false; strokeActive = false
    }
}
