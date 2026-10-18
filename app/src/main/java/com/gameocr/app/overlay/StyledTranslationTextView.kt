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
import android.util.TypedValue
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.Layout
import android.text.TextPaint
import android.text.method.ScrollingMovementMethod
import com.gameocr.app.data.OverlayTextAlignment
import com.gameocr.app.data.OverlayTextStyle
import com.gameocr.app.util.VerticalDiagnosticLog

internal data class AdaptiveHorizontalTextLayoutSnapshot(
    val phase: AdaptiveTextLayoutPhase,
    val textHash: Int,
    val textLength: Int,
    val finalTextSizePx: Float,
    val autoSizeMinTextSizePx: Int,
    val autoSizeMaxTextSizePx: Int,
    val measuredWidthPx: Int,
    val measuredHeightPx: Int,
    val leftPx: Int,
    val topPx: Int,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val layoutHeightPx: Int,
    val targetLayoutHeightPx: Int,
    val targetLayoutWidthPx: Int,
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
    internal var reflowOcrParagraph: Boolean = false
    internal var adaptiveTextFitEnabled: Boolean = false
    internal var adaptiveBoundsConstrained: Boolean = false
    private var sourceGlyphHeightPx = 0f
    private var sourceLineBudget = 1
    private var adaptiveGlyphRange: TranslationGlyphSizeRange? = null
    internal var adaptiveBackgroundOffsetYPx = 0
    internal var adaptiveBackgroundOffsetXPx = 0
    private var overflowScrolling = false

    internal fun setAdaptiveOverflowScrolling(enabled: Boolean) {
        if (overflowScrolling == enabled) return
        overflowScrolling = enabled
        isVerticalScrollBarEnabled = enabled
        if (!isTextSelectable) {
            movementMethod = if (enabled) ScrollingMovementMethod.getInstance() else null
        }
        if (!enabled) scrollTo(0, 0)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        val display = if (sourceGlyphHeightPx > 0f && !text.isNullOrEmpty()) {
            SpannableString(text).apply {
                getSpans(0, length, TranslationInkLineHeight::class.java).forEach(::removeSpan)
                setSpan(TranslationInkLineHeight(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else text
        super.setText(display, type)
    }

    internal fun configureAdaptiveGlyphHeight(heightPx: Float, lineBudget: Int = 1) {
        sourceGlyphHeightPx = heightPx
        sourceLineBudget = lineBudget.coerceAtLeast(1)
        includeFontPadding = false
        // Initial text was assigned before the adaptive target became known.
        setText(text, BufferType.SPANNABLE)
        updateAdaptiveGlyphRange(text)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        // TextView may call this during construction, before a glyph target has been configured.
        if (sourceGlyphHeightPx > 0f) updateAdaptiveGlyphRange(text ?: "")
    }

    private fun updateAdaptiveGlyphRange(value: CharSequence) {
        val range = measureTranslationGlyphRange(
            paint, value, sourceGlyphHeightPx, adaptiveBoundsConstrained,
        ) ?: return
        val contentWidth = width - compoundPaddingLeft - compoundPaddingRight
        val maximum = if (!adaptiveBoundsConstrained && contentWidth > 0 && value.isNotEmpty()) {
            translationWrapLimitedMaxFontPx(range, sourceLineBudget) { candidate ->
                val probe = TextPaint(paint).apply { textSize = candidate.toFloat() }
                StaticLayout.Builder.obtain(value, 0, value.length, probe, contentWidth)
                    .setIncludePad(false)
                    .setBreakStrategy(breakStrategy)
                    .setHyphenationFrequency(hyphenationFrequency)
                    .build().lineCount
            }
        } else range.targetPx
        val resolved = range.copy(targetPx = maximum)
        if (adaptiveGlyphRange == resolved) return
        adaptiveGlyphRange = resolved
        setAutoSizeTextTypeWithDefaults(AUTO_SIZE_TEXT_TYPE_NONE)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!adaptiveTextFitEnabled) return
        val range = adaptiveGlyphRange ?: return
        val contentWidth = measuredWidth - compoundPaddingLeft - compoundPaddingRight
        val contentHeight = measuredHeight - compoundPaddingTop - compoundPaddingBottom
        if (contentWidth <= 0 || contentHeight <= 0) return
        fun fits(size: Int, singleLine: Boolean): Boolean {
            val probe = TextPaint(paint).apply { textSize = size.toFloat() }
            val candidate = StaticLayout.Builder.obtain(text, 0, text.length, probe, contentWidth)
                .setIncludePad(includeFontPadding)
                .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
                .setBreakStrategy(breakStrategy)
                .setHyphenationFrequency(hyphenationFrequency)
                .build()
            return candidate.height <= contentHeight && candidate.lineCount <= (if(singleLine) 1 else maxLines) &&
                (0 until candidate.lineCount).all { candidate.getLineWidth(it) <= contentWidth }
        }
        // Button labels keep one line when a complete readable fit exists.
        val single = if(adaptiveBoundsConstrained && sourceLineBudget==1)
            adaptiveLargestFittingTextSizePx(range.minPx,range.targetPx) { fits(it,true) } else null
        val fit = if(single!=null && fits(single.sizePx,true)) single else
            adaptiveLargestFittingTextSizePx(range.minPx,range.targetPx) { fits(it,false) }
        if (textSize != fit.sizePx.toFloat()) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fit.sizePx.toFloat())
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (sourceGlyphHeightPx > 0f && w != oldw) updateAdaptiveGlyphRange(text)
    }
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

    override fun draw(canvas: Canvas) {
        (background as? AdaptiveEraseDrawable)?.renderOffsetYPx = adaptiveBackgroundOffsetYPx
        (background as? AdaptiveEraseDrawable)?.renderOffsetXPx = adaptiveBackgroundOffsetXPx
        super.draw(canvas)
    }

    private fun targetLayoutHeight(widthPx: Int): Int {
        val maximum = adaptiveGlyphRange?.targetPx ?: return layout?.height ?: 0
        if (widthPx <= 0) return layout?.height ?: 0
        val targetPaint = TextPaint(paint).apply { textSize = maximum.toFloat() }
        return StaticLayout.Builder.obtain(text, 0, text.length, targetPaint, widthPx)
            .setAlignment(layout?.alignment ?: Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(includeFontPadding)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setBreakStrategy(breakStrategy)
            .setHyphenationFrequency(hyphenationFrequency)
            .build().height
    }

    private fun preferredLayoutWidth(widthPx: Int): Int {
        if (adaptiveBoundsConstrained) return widthPx
        val range = measureTranslationGlyphRange(paint, text, sourceGlyphHeightPx) ?: return widthPx
        if (widthPx <= 0 || text.isEmpty()) return widthPx
        val targetPaint = TextPaint(paint).apply { textSize = range.targetPx.toFloat() }
        fun linesAt(width: Int) = StaticLayout.Builder.obtain(text, 0, text.length, targetPaint, width)
            .setIncludePad(false).setBreakStrategy(breakStrategy)
            .setHyphenationFrequency(hyphenationFrequency).build().lineCount
        if (linesAt(widthPx) <= sourceLineBudget) return widthPx
        var low = widthPx
        var high = maxOf(widthPx, kotlin.math.ceil(Layout.getDesiredWidth(text, targetPaint).toDouble())
            .toInt().coerceAtMost(resources.displayMetrics.widthPixels.coerceAtLeast(widthPx)))
        while (low < high) {
            val middle = low + (high - low) / 2
            if (linesAt(middle) <= sourceLineBudget) high = middle else low = middle + 1
        }
        return low
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
            autoSizeMinTextSizePx = adaptiveGlyphRange?.minPx ?: autoSizeMinTextSize,
            autoSizeMaxTextSizePx = adaptiveGlyphRange?.targetPx ?: autoSizeMaxTextSize,
            measuredWidthPx = measuredWidth,
            measuredHeightPx = measuredHeight,
            leftPx = left,
            topPx = top,
            contentWidthPx = contentWidth,
            contentHeightPx = contentHeight,
            layoutHeightPx = textLayout.height,
            targetLayoutHeightPx = targetLayoutHeight(contentWidth),
            targetLayoutWidthPx = preferredLayoutWidth(contentWidth),
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
