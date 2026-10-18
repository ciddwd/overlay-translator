package com.gameocr.app.overlay

import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.text.style.LineHeightSpan

/** Keep TextView measurement, drawing and selection on the same visible-ink line geometry. */
internal class TranslationInkLineHeight : LineHeightSpan.WithDensity {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) = Unit

    override fun chooseHeight(
        text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int,
        fm: Paint.FontMetricsInt, paint: TextPaint,
    ) {
        val bounds = Rect()
        paint.getTextBounds(text.toString(), start, end, bounds)
        if (bounds.height() <= 0) return
        // One pixel on either side keeps antialiased edges inside the measured line.
        fm.ascent = bounds.top - 1
        fm.descent = bounds.bottom + 1
        fm.top = fm.ascent
        fm.bottom = fm.descent
        fm.leading = 0
    }
}
