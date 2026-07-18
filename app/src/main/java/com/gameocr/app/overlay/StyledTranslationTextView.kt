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

internal data class AdaptiveHorizontalTextLayoutSnapshot(
    val phase: AdaptiveTextLayoutPhase,
    val textHash: Int,
    val textLength: Int,
    val finalTextSizePx: Float,
    val autoSizeMaxTextSizePx: Int,
    val measuredWidthPx: Int,
    val measuredHeightPx: Int,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val layoutHeightPx: Int,
    val lineCount: Int,
    val visibleTextEnd: Int,
    val ellipsized: Boolean,
    val overflowReasons: Set<AdaptiveTextOverflowReason>,
    val overflow: Boolean,
)

/** TextView that draws an optional outline before the normal filled translation text. */
@SuppressLint("AppCompatCustomView") // Platform TextView keeps the framework text-selection ActionMode.
class StyledTranslationTextView(context: Context) : TextView(context) {
    var horizontalRightToLeft: Boolean = false
    internal var adaptiveTextFitEnabled: Boolean = false
    internal var adaptiveTextLayoutPhase: AdaptiveTextLayoutPhase = AdaptiveTextLayoutPhase.FINAL
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }
    internal var onAdaptiveTextLayoutResolved: ((AdaptiveHorizontalTextLayoutSnapshot) -> Unit)? = null

    private var outlineWidthPx = 0f
    private var outlineColor = 0
    private var shadowEnabled = false
    private var shadowRadiusPx = 0f
    private var shadowDxPx = 0f
    private var shadowDyPx = 0f
    private var shadowColor = 0
    private var drawingPass = false
    private var lastAdaptiveTextLayoutSnapshot: AdaptiveHorizontalTextLayoutSnapshot? = null

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
            reportAdaptiveTextLayout()
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
        reportAdaptiveTextLayout()
    }

    private fun reportAdaptiveTextLayout() {
        if (!shouldReportAdaptiveTextLayout(adaptiveTextLayoutPhase)) return
        val callback = onAdaptiveTextLayoutResolved ?: return
        val textLayout = layout ?: return
        val resolvedLineCount = textLayout.lineCount
        val visibleTextEnd = if (resolvedLineCount > 0) {
            textLayout.getLineEnd(resolvedLineCount - 1)
        } else {
            0
        }
        var ellipsized = false
        for (line in 0 until resolvedLineCount) {
            if (textLayout.getEllipsisCount(line) > 0) {
                ellipsized = true
                break
            }
        }
        // Match framework TextView#autoSizeText available-space calculations. In particular,
        // total top/bottom padding includes the gravity offset and would hide vertical overflow.
        val contentWidth = (measuredWidth - totalPaddingLeft - totalPaddingRight).coerceAtLeast(0)
        val contentHeight = (measuredHeight - extendedPaddingTop - extendedPaddingBottom).coerceAtLeast(0)
        val overflowReasons = adaptiveTextLayoutOverflowReasons(
            textLength = text.length,
            visibleTextEnd = visibleTextEnd,
            layoutHeightPx = textLayout.height,
            contentHeightPx = contentHeight,
            lineCount = resolvedLineCount,
            maxLines = maxLines,
            ellipsized = ellipsized,
        )
        val snapshot = AdaptiveHorizontalTextLayoutSnapshot(
            phase = adaptiveTextLayoutPhase,
            textHash = text.toString().hashCode(),
            textLength = text.length,
            finalTextSizePx = textSize,
            autoSizeMaxTextSizePx = autoSizeMaxTextSize,
            measuredWidthPx = measuredWidth,
            measuredHeightPx = measuredHeight,
            contentWidthPx = contentWidth,
            contentHeightPx = contentHeight,
            layoutHeightPx = textLayout.height,
            lineCount = resolvedLineCount,
            visibleTextEnd = visibleTextEnd,
            ellipsized = ellipsized,
            overflowReasons = overflowReasons,
            overflow = overflowReasons.isNotEmpty(),
        )
        if (snapshot == lastAdaptiveTextLayoutSnapshot) return
        lastAdaptiveTextLayoutSnapshot = snapshot
        callback(snapshot)
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
