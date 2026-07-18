package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.gameocr.app.data.OverlayTextStyle
import kotlin.math.roundToInt

internal data class AdaptiveVerticalTextFitSnapshot(
    val phase: AdaptiveTextLayoutPhase,
    val textHash: Int,
    val textLength: Int,
    val initialTextSizePx: Float,
    val finalTextSizePx: Float,
    val minTextSizePx: Int,
    val maxTextSizePx: Int,
    val probes: Int,
    val requiredWidthPx: Int,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val nextSizeFits: Boolean?,
    val overflow: Boolean,
)

/**
 * 竖排（tategaki）文本 View。**包装 [VerticalTextDrawer]**——`measure` 走 Drawer.measure，
 * `onDraw` 走 Drawer.draw。架构上是 [android.widget.TextView] 的竖排平替，专门给
 * [OverlayManager] 在 VERTICAL_RTL / VERTICAL_LTR 场景使用。
 *
 * 暴露的属性与 TextView 在屏译场景下用到的子集对齐：
 *  - [text] - 单段译文文本
 *  - [setTextSizeSp] - 字号（sp，会乘 density 转 px）
 *  - [setTextColor] - 前景色
 *  - [setLeftToRight] - VERTICAL_LTR=true / VERTICAL_RTL=false（默认 RTL，日漫常态）
 *
 * 背景仍通过标准 [View.setBackground] 用 [GradientDrawable]——和 TextView 同款主题代码可复用。
 */
class VerticalTextView(context: Context) : View(context) {

    private val paint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp2px(14f)
    }
    private var textColor: Int = Color.WHITE
    private var textStyle: OverlayTextStyle = OverlayTextStyle()
    internal var minimumReadableTextSizeSp: Float = 12f
    internal var minimumReadableTextSizeRatio: Float = 0.86f
    internal var adaptiveTextFitEnabled: Boolean = false
    internal var adaptiveMaximumTextSizeSp: Float = ADAPTIVE_MAX_TEXT_SIZE_SP.toFloat()
    internal var adaptiveTextLayoutPhase: AdaptiveTextLayoutPhase = AdaptiveTextLayoutPhase.FINAL
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }
    internal var onAdaptiveTextFitResolved: ((AdaptiveVerticalTextFitSnapshot) -> Unit)? = null
    private var lastAdaptiveTextFitSnapshot: AdaptiveVerticalTextFitSnapshot? = null

    /**
     * 当前文本。setter 会触发 [requestLayout]（重算尺寸）+ [invalidate]（重绘）。
     */
    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /** VERTICAL_LTR=true（蒙古文 / 古籍）；默认 false 走 RTL（日漫常态）。 */
    var leftToRight: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 与 TextView.setPadding 行为一致：上下左右 padding 累加到 measured 尺寸里。 */
    var typeface: Typeface? = null
        set(value) {
            if (field == value) return
            field = value
            applyTypefaceStyle()
            requestLayout()
            invalidate()
        }

    private var padL = 0
    private var padT = 0
    private var padR = 0
    private var padB = 0

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        padL = left; padT = top; padR = right; padB = bottom
        super.setPadding(left, top, right, bottom)
        requestLayout()
    }

    fun setTextSizeSp(sp: Float) {
        paint.textSize = sp2px(sp)
        requestLayout()
        invalidate()
    }

    fun setTextColor(color: Int) {
        textColor = color
        paint.color = color
        invalidate()
    }

    fun applyTextStyle(style: OverlayTextStyle) {
        textStyle = style.normalized()
        applyTypefaceStyle()
        paint.isUnderlineText = textStyle.underline
        applyShadowStyle()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)

        // 计算可用绘制区域上限（去掉 padding）
        val maxContentH = when (hMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> (hSize - padT - padB).coerceAtLeast(0)
            else -> 0  // UNSPECIFIED：传 0 给 measure 走"无限高"
        }

        val layoutText = normalizeVerticalOverlayText(text)
        val (contentW, contentH) = VerticalTextDrawer.measure(
            layoutText,
            paint,
            maxContentH,
            letterSpacingEm = textStyle.letterSpacingEm,
            lineSpacingMultiplier = textStyle.lineSpacingMultiplier
        )
        val desiredW = contentW + padL + padR
        val desiredH = contentH + padT + padB

        val finalW = when (wMode) {
            MeasureSpec.EXACTLY -> wSize
            MeasureSpec.AT_MOST -> desiredW.coerceAtMost(wSize)
            else -> desiredW
        }
        val finalH = when (hMode) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> desiredH.coerceAtMost(hSize)
            else -> desiredH
        }
        setMeasuredDimension(finalW.coerceAtLeast(1), finalH.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)  // 让 background drawable 画完
        val contentW = (width - padL - padR).toFloat()
        val contentH = (height - padT - padB).toFloat()
        if (contentW <= 0f || contentH <= 0f) return
        val originalTextSize = paint.textSize
        val layoutText = normalizeVerticalOverlayText(text)
        val fitSnapshot = if (
            adaptiveTextFitEnabled && shouldFitAdaptiveFinalBounds(adaptiveTextLayoutPhase)
        ) {
            fitAdaptiveTextToBounds(layoutText, contentW, contentH, originalTextSize)
        } else {
            shrinkTextToFit(layoutText, contentW, contentH, originalTextSize)
        }
        if (
            shouldReportAdaptiveTextLayout(adaptiveTextLayoutPhase) &&
            fitSnapshot != lastAdaptiveTextFitSnapshot
        ) {
            lastAdaptiveTextFitSnapshot = fitSnapshot
            onAdaptiveTextFitResolved?.invoke(fitSnapshot)
        }
        canvas.save()
        canvas.clipRect(padL, padT, width - padR, height - padB)
        canvas.translate(padL.toFloat(), padT.toFloat())
        if (textStyle.strokeEnabled) {
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = textStyle.strokeWidthDp * resources.displayMetrics.density
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = textStyle.strokeColor
            drawVerticalText(canvas, layoutText, contentW, contentH)
        }
        paint.style = Paint.Style.FILL
        paint.color = textColor
        applyShadowStyle()
        drawVerticalText(canvas, layoutText, contentW, contentH)
        canvas.restore()
        paint.textSize = originalTextSize
    }

    private fun drawVerticalText(canvas: Canvas, layoutText: String, contentW: Float, contentH: Float) {
        VerticalTextDrawer.draw(
            canvas = canvas,
            text = layoutText,
            paint = paint,
            boundsW = contentW,
            boundsH = contentH,
            leftToRight = leftToRight,
            letterSpacingEm = textStyle.letterSpacingEm,
            lineSpacingMultiplier = textStyle.lineSpacingMultiplier,
            alignment = textStyle.alignment
        )
    }

    private fun applyTypefaceStyle() {
        val style = when {
            textStyle.bold && textStyle.italic -> Typeface.BOLD_ITALIC
            textStyle.bold -> Typeface.BOLD
            textStyle.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        paint.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, style)
    }

    private fun applyShadowStyle() {
        if (textStyle.shadowEnabled) {
            val density = resources.displayMetrics.density
            paint.setShadowLayer(
                textStyle.shadowRadiusDp * density,
                textStyle.shadowOffsetXDp * density,
                textStyle.shadowOffsetYDp * density,
                textStyle.shadowColor
            )
        } else {
            paint.clearShadowLayer()
        }
    }

    private fun sp2px(sp: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp,
            context.resources.displayMetrics
        )

    private fun shrinkTextToFit(
        layoutText: String,
        contentW: Float,
        contentH: Float,
        originalTextSize: Float
    ): AdaptiveVerticalTextFitSnapshot {
        if (layoutText.isEmpty()) {
            return AdaptiveVerticalTextFitSnapshot(
                phase = adaptiveTextLayoutPhase,
                textHash = text.hashCode(),
                textLength = text.length,
                initialTextSizePx = originalTextSize,
                finalTextSizePx = originalTextSize,
                minTextSizePx = originalTextSize.roundToInt(),
                maxTextSizePx = originalTextSize.roundToInt(),
                probes = 0,
                requiredWidthPx = 0,
                contentWidthPx = contentW.toInt(),
                contentHeightPx = contentH.toInt(),
                nextSizeFits = null,
                overflow = false,
            )
        }
        val minTextSize = verticalTextReadableMinSizePx(
            originalTextSizePx = originalTextSize,
            minReadableTextSizePx = sp2px(minimumReadableTextSizeSp),
            minimumOriginalSizeRatio = minimumReadableTextSizeRatio,
        )
        var iterations = 0
        var requiredWidth = VerticalTextDrawer.measure(
            layoutText,
            paint,
            contentH.toInt(),
            letterSpacingEm = textStyle.letterSpacingEm,
            lineSpacingMultiplier = textStyle.lineSpacingMultiplier
        ).first
        while (iterations < 8 && requiredWidth > contentW && paint.textSize > minTextSize) {
            val ratio = (contentW / requiredWidth).coerceIn(0.65f, 0.97f)
            val nextSize = (paint.textSize * ratio).coerceAtLeast(minTextSize)
            if (nextSize >= paint.textSize) break
            paint.textSize = nextSize
            iterations++
            requiredWidth = VerticalTextDrawer.measure(
                layoutText,
                paint,
                contentH.toInt(),
                letterSpacingEm = textStyle.letterSpacingEm,
                lineSpacingMultiplier = textStyle.lineSpacingMultiplier
            ).first
        }
        return AdaptiveVerticalTextFitSnapshot(
            phase = adaptiveTextLayoutPhase,
            textHash = text.hashCode(),
            textLength = text.length,
            initialTextSizePx = originalTextSize,
            finalTextSizePx = paint.textSize,
            minTextSizePx = minTextSize.roundToInt(),
            maxTextSizePx = originalTextSize.roundToInt(),
            probes = iterations,
            requiredWidthPx = requiredWidth,
            contentWidthPx = contentW.toInt(),
            contentHeightPx = contentH.toInt(),
            nextSizeFits = null,
            overflow = requiredWidth > contentW,
        )
    }

    private fun fitAdaptiveTextToBounds(
        layoutText: String,
        contentW: Float,
        contentH: Float,
        originalTextSize: Float,
    ): AdaptiveVerticalTextFitSnapshot {
        val minTextSizePx = sp2px(minimumReadableTextSizeSp).roundToInt().coerceAtLeast(1)
        val maxTextSizePx = sp2px(adaptiveMaximumTextSizeSp)
            .roundToInt()
            .coerceAtLeast(minTextSizePx)
        if (layoutText.isEmpty()) {
            paint.textSize = maxTextSizePx.toFloat()
            return AdaptiveVerticalTextFitSnapshot(
                phase = adaptiveTextLayoutPhase,
                textHash = text.hashCode(),
                textLength = text.length,
                initialTextSizePx = originalTextSize,
                finalTextSizePx = paint.textSize,
                minTextSizePx = minTextSizePx,
                maxTextSizePx = maxTextSizePx,
                probes = 0,
                requiredWidthPx = 0,
                contentWidthPx = contentW.toInt(),
                contentHeightPx = contentH.toInt(),
                nextSizeFits = null,
                overflow = false,
            )
        }

        fun requiredWidthAt(sizePx: Int): Int {
            paint.textSize = sizePx.toFloat()
            return VerticalTextDrawer.measure(
                layoutText,
                paint,
                contentH.toInt(),
                letterSpacingEm = textStyle.letterSpacingEm,
                lineSpacingMultiplier = textStyle.lineSpacingMultiplier,
            ).first
        }

        val search = adaptiveLargestFittingTextSizePx(
            minSizePx = minTextSizePx,
            maxSizePx = maxTextSizePx,
        ) { candidatePx ->
            requiredWidthAt(candidatePx) <= contentW
        }
        paint.textSize = search.sizePx.toFloat()
        val requiredWidth = requiredWidthAt(search.sizePx)
        return AdaptiveVerticalTextFitSnapshot(
            phase = adaptiveTextLayoutPhase,
            textHash = text.hashCode(),
            textLength = text.length,
            initialTextSizePx = originalTextSize,
            finalTextSizePx = paint.textSize,
            minTextSizePx = minTextSizePx,
            maxTextSizePx = maxTextSizePx,
            probes = search.probes,
            requiredWidthPx = requiredWidth,
            contentWidthPx = contentW.toInt(),
            contentHeightPx = contentH.toInt(),
            nextSizeFits = search.nextSizeFits,
            overflow = requiredWidth > contentW,
        )
    }
}
