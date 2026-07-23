package com.gameocr.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

internal class LongPressGuideView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * density
    }
    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    fun start() {
        animate(durationMs = 1_000L, repeat = true)
    }

    fun beginPress(durationMs: Long) {
        animate(durationMs = durationMs, repeat = false)
    }

    private fun animate(durationMs: Long, repeat: Boolean) {
        animator?.cancel()
        visibility = VISIBLE
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            repeatCount = if (repeat) ValueAnimator.INFINITE else 0
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        progress = 0f
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 3.5f * density
        val oval = RectF(inset, inset, width - inset, height - inset)
        canvas.drawOval(oval, trackPaint)
        canvas.drawArc(oval, -90f, 360f * progress, false, progressPaint)
    }
}
