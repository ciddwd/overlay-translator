package com.gameocr.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.gameocr.app.R
import com.gameocr.app.data.Settings

internal data class EnglishWordPreviewPalette(
    val background: Int,
    val foreground: Int,
    val muted: Int,
    val accent: Int,
    val typeface: Typeface?,
)

/** Shared word selection and dictionary preview inside result windows. */
internal class EnglishWordPreview(
    private val context: Context,
    private val root: () -> FrameLayout?,
    private val palette: (Settings) -> EnglishWordPreviewPalette,
) {
    private var wordPreviewView: View? = null
    private var sourceWordHighlight: BackgroundColorSpan? = null
    private var highlightedTextView: TextView? = null
    var requestId: Long = 0L
        private set

    fun isShown(): Boolean = root() != null

    fun show(
        anchorInWindow: Rect,
        content: FloatingWordPreviewContent,
        settings: Settings,
        onSpeak: (() -> Unit)?,
        onOpenDetails: (() -> Unit)?,
    ) {
        val layer = root() ?: return
        wordPreviewView?.let(layer::removeView)
        val density = context.resources.displayMetrics.density
        val colors = palette(settings)
        val foreground = colors.foreground
        val muted = colors.muted
        val accent = colors.accent
        val backgroundColor = colors.background
        val typeface = colors.typeface
        val textSizeSp = settings.overlayTextSizeSp.coerceIn(10, 32).toFloat()

        fun oneLine(value: String, sizeSp: Float, color: Int, bold: Boolean = false) =
            StyledTranslationTextView(context).apply {
                text = value
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                applyOverlayTextStyle(
                    settings.overlayTextStyle.copy(bold = settings.overlayTextStyle.bold || bold),
                    typeface,
                )
                maxLines = 1
                minLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setHorizontallyScrolling(false)
            }

        val openDetails = onOpenDetails?.let { action ->
            {
                dismiss()
                action()
            }
        }
        val preview = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 8 * density
            val horizontal = (12 * density).toInt()
            val vertical = (8 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(backgroundColor)
                setStroke((1 * density).toInt().coerceAtLeast(1), muted)
            }
            if (openDetails != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { openDetails() }
            }
        }
        val wordRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wordRow.addView(
            oneLine(content.word, textSizeSp + 1f, foreground, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        onSpeak?.let { speak ->
            val metrics = translationCardSpeechButtonMetrics(density)
            wordRow.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_volume_up)
                    imageTintList = ColorStateList.valueOf(accent)
                    setPadding(metrics.paddingPx, metrics.paddingPx, metrics.paddingPx, metrics.paddingPx)
                    isClickable = true
                    isFocusable = true
                    contentDescription = context.getString(R.string.word_card_speak_source)
                    setOnClickListener { speak() }
                },
                LinearLayout.LayoutParams(metrics.sizePx, metrics.sizePx),
            )
        }
        preview.addView(wordRow)
        content.lines.forEachIndexed { index, line ->
            preview.addView(oneLine(
                value = line,
                sizeSp = if (index == 0) textSizeSp else (textSizeSp - 1f).coerceAtLeast(10f),
                color = foreground,
            ))
        }
        openDetails?.let { action ->
            preview.addView(TextView(context).apply {
                text = context.getString(R.string.floating_word_view_details)
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, (textSizeSp - 1f).coerceAtLeast(11f))
                maxLines = 1
                gravity = Gravity.END
                isClickable = true
                isFocusable = true
                setPadding(0, (5 * density).toInt(), 0, 0)
                setOnClickListener { action() }
            })
        }

        val margin = (10 * density).toInt()
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = margin
            rightMargin = margin
        }
        layer.addView(preview, params)
        wordPreviewView = preview
        preview.post {
            if (wordPreviewView !== preview || layer.height <= 0) return@post
            val location = IntArray(2)
            layer.getLocationInWindow(location)
            val gap = (6 * density).toInt()
            val minimumTop = (8 * density).toInt()
            val maximumTop = (layer.height - preview.height - minimumTop).coerceAtLeast(minimumTop)
            val above = anchorInWindow.top - location[1] - preview.height - gap
            val below = anchorInWindow.bottom - location[1] + gap
            params.topMargin = (if (above >= minimumTop) above else below)
                .coerceIn(minimumTop, maximumTop)
            preview.layoutParams = params
        }
    }

    fun dismiss() {
        requestId++
        wordPreviewView?.let { preview ->
            (preview.parent as? FrameLayout)?.removeView(preview)
        }
        wordPreviewView = null
        clearSourceWordHighlight()
    }

    fun attach(
        textView: TextView,
        onTapped: (word: String, anchorInWindow: Rect) -> Unit,
        markerColor: Int,
        isEnabled: () -> Boolean,
    ) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        textView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (!isEnabled()) return@setOnTouchListener false
                    if (
                        kotlin.math.abs(event.x - downX) <= slop &&
                        kotlin.math.abs(event.y - downY) <= slop &&
                        event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()
                    ) {
                        val textView = view as TextView
                        val offset = textView.getOffsetForPosition(event.x, event.y)
                        val hit = floatingEnglishWordAt(textView.text, offset)
                        val layout = textView.layout
                        if (hit != null && layout != null) {
                            val text = textView.text as? Spannable
                            val highlight = sourceWordHighlight
                            if (highlight != null &&
                                text?.getSpanStart(highlight) == hit.start &&
                                text.getSpanEnd(highlight) == hit.end
                            ) {
                                dismiss()
                                return@setOnTouchListener false
                            }
                            if (textView.selectionStart != textView.selectionEnd) return@setOnTouchListener false
                            requestId++
                            showSourceWordHighlight(textView, hit, markerColor)
                            val line = layout.getLineForOffset(hit.start.coerceIn(0, textView.text.length))
                            val start = maxOf(hit.start, layout.getLineStart(line))
                            val end = minOf(hit.end, layout.getLineEnd(line))
                            val location = IntArray(2)
                            textView.getLocationInWindow(location)
                            val left = minOf(layout.getPrimaryHorizontal(start), layout.getPrimaryHorizontal(end))
                            val right = maxOf(layout.getPrimaryHorizontal(start), layout.getPrimaryHorizontal(end))
                            val contentLeft = location[0] + textView.totalPaddingLeft - textView.scrollX
                            val contentTop = location[1] + textView.totalPaddingTop - textView.scrollY
                            onTapped(
                                hit.word,
                                Rect(
                                    contentLeft + left.toInt(),
                                    contentTop + layout.getLineTop(line),
                                    contentLeft + right.toInt(),
                                    contentTop + layout.getLineBottom(line),
                                ),
                            )
                        }
                    }
                }
            }
            false
        }
    }

    private fun showSourceWordHighlight(
        textView: TextView,
        hit: FloatingEnglishWordHit,
        markerColor: Int,
    ) {
        clearSourceWordHighlight()
        val spannable = (textView.text as? Spannable) ?: SpannableStringBuilder(textView.text).also {
            textView.text = it
        }
        val span = BackgroundColorSpan(markerColor)
        spannable.setSpan(span, hit.start, hit.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sourceWordHighlight = span
        highlightedTextView = textView
    }

    private fun clearSourceWordHighlight() {
        val span = sourceWordHighlight ?: return
        sourceWordHighlight = null
        (highlightedTextView?.text as? Spannable)?.removeSpan(span)
        highlightedTextView?.invalidate()
        highlightedTextView = null
    }

}
