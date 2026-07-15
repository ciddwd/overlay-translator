package com.gameocr.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.ActionMode
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.gameocr.app.data.OverlayTextAlignment
import com.gameocr.app.data.OverlayTextStyle
import com.gameocr.app.util.VerticalDiagnosticLog

/** TextView that draws an optional outline before the normal filled translation text. */
@SuppressLint("AppCompatCustomView") // Platform TextView keeps the framework text-selection ActionMode.
class StyledTranslationTextView(context: Context) : TextView(context) {
    var horizontalRightToLeft: Boolean = false

    private var outlineWidthPx = 0f
    private var outlineColor = 0
    private var shadowEnabled = false
    private var shadowRadiusPx = 0f
    private var shadowDxPx = 0f
    private var shadowDyPx = 0f
    private var shadowColor = 0
    private var drawingPass = false

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        val mode = super.startActionMode(callback, type)
        VerticalDiagnosticLog.i(
            "overlay selection actionMode type=$type started=${mode != null} " +
                "windowFocus=${hasWindowFocus()} focus=${hasFocus()}"
        )
        return mode
    }

    fun configureOutline(widthPx: Float, color: Int) {
        outlineWidthPx = widthPx.coerceAtLeast(0f)
        outlineColor = color
        invalidate()
    }

    fun configureShadow(enabled: Boolean, radiusPx: Float, dxPx: Float, dyPx: Float, color: Int) {
        shadowEnabled = enabled
        shadowRadiusPx = radiusPx.coerceAtLeast(0f)
        shadowDxPx = dxPx
        shadowDyPx = dyPx
        shadowColor = color
        applyFillShadow()
        invalidate()
    }

    override fun invalidate() {
        if (!drawingPass) super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (outlineWidthPx <= 0f) {
            super.onDraw(canvas)
            return
        }

        val fillColor = currentTextColor
        val originalStyle = paint.style
        val originalStrokeWidth = paint.strokeWidth
        val originalStrokeJoin = paint.strokeJoin
        drawingPass = true
        try {
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineWidthPx
            paint.strokeJoin = Paint.Join.ROUND
            super.setTextColor(outlineColor)
            super.onDraw(canvas)

            paint.style = Paint.Style.FILL
            super.setTextColor(fillColor)
            applyFillShadow()
            super.onDraw(canvas)
        } finally {
            paint.style = originalStyle
            paint.strokeWidth = originalStrokeWidth
            paint.strokeJoin = originalStrokeJoin
            super.setTextColor(fillColor)
            applyFillShadow()
            drawingPass = false
        }
    }

    private fun applyFillShadow() {
        if (shadowEnabled) {
            setShadowLayer(shadowRadiusPx, shadowDxPx, shadowDyPx, shadowColor)
        } else {
            paint.clearShadowLayer()
        }
    }
}

fun StyledTranslationTextView.applyOverlayTextStyle(
    style: OverlayTextStyle,
    baseTypeface: Typeface?,
    baseLineSpacingMultiplier: Float = 1f
) {
    val normalized = style.normalized()
    val typefaceStyle = when {
        normalized.bold && normalized.italic -> Typeface.BOLD_ITALIC
        normalized.bold -> Typeface.BOLD
        normalized.italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    typeface = Typeface.create(baseTypeface ?: Typeface.DEFAULT, typefaceStyle)
    paintFlags = if (normalized.underline) {
        paintFlags or Paint.UNDERLINE_TEXT_FLAG
    } else {
        paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
    }
    letterSpacing = normalized.letterSpacingEm
    setLineSpacing(0f, baseLineSpacingMultiplier * normalized.lineSpacingMultiplier)
    val verticalGravity = (gravity and Gravity.VERTICAL_GRAVITY_MASK).let {
        if (it == 0) Gravity.TOP else it
    }
    gravity = verticalGravity or when (normalized.alignment) {
        OverlayTextAlignment.START -> Gravity.START
        OverlayTextAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
        OverlayTextAlignment.END -> Gravity.END
    }
    textAlignment = View.TEXT_ALIGNMENT_GRAVITY

    val density = resources.displayMetrics.density
    configureOutline(
        widthPx = if (normalized.strokeEnabled) normalized.strokeWidthDp * density else 0f,
        color = normalized.strokeColor
    )
    configureShadow(
        enabled = normalized.shadowEnabled,
        radiusPx = normalized.shadowRadiusDp * density,
        dxPx = normalized.shadowOffsetXDp * density,
        dyPx = normalized.shadowOffsetYDp * density,
        color = normalized.shadowColor
    )
}
