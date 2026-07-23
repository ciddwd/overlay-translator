package com.gameocr.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.gameocr.app.R

internal class FloatingLongPressCueOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val overlayType: Int,
) {
    private var view: View? = null
    private var animator: ValueAnimator? = null

    fun show(ballBounds: Rect) {
        dismiss()
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val screenBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            Rect().also { windowManager.defaultDisplay.getRectSize(it) }
        }
        val ballOnLeft = ballBounds.centerX() < screenBounds.width() / 2
        val cue = TextView(context).apply {
            text = if (ballOnLeft) {
                "\u2190 ${context.getString(R.string.floating_tour_hold_cue)}"
            } else {
                "${context.getString(R.string.floating_tour_hold_cue)} \u2192"
            }
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(24, 92, 190))
            }
            elevation = dp(8).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or if (ballOnLeft) Gravity.START else Gravity.END
            x = if (ballOnLeft) {
                ballBounds.right + dp(10)
            } else {
                screenBounds.width() - ballBounds.left + dp(10)
            }
            y = (ballBounds.centerY() - dp(22))
                .coerceIn(dp(24), (screenBounds.height() - dp(72)).coerceAtLeast(dp(24)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching {
            windowManager.addView(cue, params)
            view = cue
            val direction = if (ballOnLeft) -1f else 1f
            animator = ValueAnimator.ofFloat(0f, direction * dp(7)).apply {
                duration = 550L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener {
                    cue.translationX = it.animatedValue as Float
                }
                start()
            }
        }
    }

    fun dismiss() {
        animator?.cancel()
        animator = null
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }
}
