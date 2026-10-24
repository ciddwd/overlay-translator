package com.gameocr.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.gameocr.app.data.PresetGesture
import kotlin.math.ceil

/** Pastel ribbon following the finger; recognition always uses the original input points. */
internal class GestureTrailView(
    context: Context,
    private val onResult: (PresetGesture?) -> Unit,
    private val onCancel: () -> Unit,
    private val onPreview: (PresetGesture?) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val points = mutableListOf<GesturePoint>()
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val palette = intArrayOf(
        Color.rgb(249, 179, 205), Color.rgb(158, 245, 238), Color.rgb(180, 176, 250),
        Color.rgb(215, 174, 251), Color.rgb(245, 183, 217), Color.rgb(186, 244, 208),
        Color.rgb(244, 246, 161),
    )
    private var positions = FloatArray(0)
    private var geometryDirty = true
    private var releaseProgress = 0f
    private var release: ValueAnimator? = null
    private val haloWidths = floatArrayOf(3.4f, 2.6f, 1.8f, 1f)
    private val haloAlphas = intArrayOf(12, 20, 38, 245)
    private var marker: GesturePoint? = null
    private var markerReady = false

    fun showMarker(point: GesturePoint?, ready: Boolean = false) {
        marker = point
        markerReady = ready
        invalidate()
    }

    fun beginStroke(x: Float, y: Float) {
        showMarker(null)
        points.clear()
        positions = FloatArray(0)
        path.reset()
        releaseProgress = 0f
        path.moveTo(x, y)
        points.add(GesturePoint(x, y))
        geometryDirty = true
        onPreview(null)
        invalidate()
    }

    fun appendStroke(x: Float, y: Float) {
        addPoint(x, y)
        onPreview(PresetGestureRecognizer.recognize(points, 40 * density))
        invalidate()
    }

    fun finishStroke(x: Float, y: Float) {
        if (release != null || points.isEmpty()) return
        appendStroke(x, y)
        path.lineTo(x, y)
        geometryDirty = true
        val result = PresetGestureRecognizer.recognize(points, 40 * density)
        release = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener {
                releaseProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    release = null
                    onResult(result)
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        marker?.let {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(37, 154, 240)
            paint.alpha = if (markerReady) 32 else 14
            canvas.drawCircle(it.x, it.y, 12 * density, paint)
            paint.alpha = if (markerReady) 255 else 115
            canvas.drawCircle(it.x, it.y, 6 * density, paint)
            if (markerReady) {
                val phase = (android.os.SystemClock.uptimeMillis() % 800) / 800f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f * density
                paint.alpha = ((1 - phase) * 230).toInt()
                canvas.drawCircle(it.x, it.y, (9 + 11 * phase) * density, paint)
                postInvalidateOnAnimation()
            }
            paint.style = Paint.Style.STROKE
        }
        if (geometryDirty) {
            val measure = PathMeasure(path, false)
            if (measure.length <= 0f) return
            val count = ceil(measure.length / (3 * density)).toInt().coerceIn(2, 512)
            positions = FloatArray(count * 2)
            val point = FloatArray(2)
            for (i in 0 until count) {
                measure.getPosTan(measure.length * i / (count - 1), point, null)
                positions[i * 2] = point[0]
                positions[i * 2 + 1] = point[1]
            }
            geometryDirty = false
        }
        val count = positions.size / 2
        if (count < 2) return
        // Layered translucent strokes give a soft halo without a software blur layer.
        for (layer in 0..3) {
            val widthScale = haloWidths[layer]
            val alpha = haloAlphas[layer]
            for (i in 1 until count) {
                val fraction = i.toFloat() / (count - 1)
                if (fraction <= releaseProgress) continue
                val colorPosition = fraction * (palette.size - 1)
                val colorIndex = colorPosition.toInt().coerceAtMost(palette.lastIndex - 1)
                paint.color = ColorUtils.blendARGB(palette[colorIndex], palette[colorIndex + 1], colorPosition - colorIndex)
                paint.alpha = (alpha * (1 - releaseProgress * 0.65f)).toInt()
                paint.strokeWidth = 7 * density * widthScale * (0.65f + 0.35f * fraction) * (1 - releaseProgress * 0.4f)
                val previous = (i - 1).toFloat() / (count - 1)
                val trim = ((releaseProgress - previous) * (count - 1)).coerceIn(0f, 1f)
                val x0 = positions[(i - 1) * 2]
                val y0 = positions[(i - 1) * 2 + 1]
                val x1 = positions[i * 2]
                val y1 = positions[i * 2 + 1]
                canvas.drawLine(x0 + (x1 - x0) * trim, y0 + (y1 - y0) * trim, x1, y1, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (release != null) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginStroke(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                for (i in 0 until event.historySize) {
                    addPoint(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                appendStroke(event.x, event.y)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    finishStroke(event.x, event.y)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> onCancel()
        }
        invalidate()
        return true
    }

    private fun addPoint(x: Float, y: Float) {
        val previous = points.lastOrNull() ?: return
        points.add(GesturePoint(x, y))
        path.quadTo(previous.x, previous.y, (previous.x + x) / 2, (previous.y + y) / 2)
        geometryDirty = true
    }

    override fun onDetachedFromWindow() {
        release?.removeAllListeners()
        release?.cancel()
        release = null
        super.onDetachedFromWindow()
    }
}
