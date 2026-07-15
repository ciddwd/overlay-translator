package com.gameocr.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 悬浮球外圈循环进度环。叠加在 [FloatingButtonManager] 的 ImageView 上层，仅在循环模式下绘制。
 *
 * 周期跟随 `captureLoopIntervalMs`：每 interval 转一圈，视觉暗示"下一次截屏倒计时"。
 * 不与实际 OCR/翻译耗时严格同步——OCR 慢于 interval 时 sweep 已转完进入下一圈仍能继续暗示。
 */
class LoopProgressView(context: Context) : View(context) {

    private enum class Mode {
        COUNTDOWN,
        INDETERMINATE,
    }

    private val density = context.resources.displayMetrics.density
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        // 醒目色，与白色外框区分。半透明避免太抢眼。
        color = 0xCC4CAF50.toInt() // 半透明绿色
    }
    private var progress: Float = 0f
    private var mode: Mode = Mode.COUNTDOWN
    private var animator: ValueAnimator? = null
    private val arcRect = RectF()

    fun start(durationMs: Long) {
        stop()
        mode = Mode.COUNTDOWN
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.coerceAtLeast(200L)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun startIndeterminate(periodMs: Long) {
        stop()
        mode = Mode.INDETERMINATE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs.coerceAtLeast(800L)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
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
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // view 从 WindowManager remove 时确保动画停掉，避免持续 invalidate detached view
        stop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (animator == null) return
        val inset = paint.strokeWidth / 2f
        arcRect.set(inset, inset, width - inset, height - inset)
        when (mode) {
            Mode.COUNTDOWN -> canvas.drawArc(arcRect, -90f, 360f * progress, false, paint)
            Mode.INDETERMINATE -> canvas.drawArc(
                arcRect,
                -90f + 360f * progress,
                INDETERMINATE_SWEEP_DEGREES,
                false,
                paint,
            )
        }
    }

    private companion object {
        const val INDETERMINATE_SWEEP_DEGREES = 96f
    }
}
