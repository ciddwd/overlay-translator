package com.gameocr.app.overlay

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

internal class AdaptiveEraseDrawable(
    color: Int,
    private val eraseRects: List<OverlayIntRect>,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        eraseRects.forEach { rect ->
            canvas.drawRect(
                (bounds.left + rect.left).toFloat(),
                (bounds.top + rect.top).toFloat(),
                (bounds.left + rect.right).toFloat(),
                (bounds.top + rect.bottom).toFloat(),
                paint,
            )
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha.coerceIn(0, 255)
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
