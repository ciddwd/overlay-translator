package com.gameocr.app.overlay

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.gameocr.app.data.FloatingWindowContentMode

internal enum class FloatingWindowTextRole {
    SOURCE,
    TRANSLATION,
    SEPARATOR,
}

internal data class FloatingWindowTextSegment(
    val text: String,
    val role: FloatingWindowTextRole,
    val pairIndex: Int,
)

internal fun floatingWindowTextSegments(
    pairs: List<Pair<String, String>>,
    mode: FloatingWindowContentMode,
): List<FloatingWindowTextSegment> = buildList {
    pairs.forEachIndexed { index, (source, translation) ->
        if (mode == FloatingWindowContentMode.SRC_AND_DST) {
            add(FloatingWindowTextSegment("・$source\n", FloatingWindowTextRole.SOURCE, index))
        }
        add(FloatingWindowTextSegment(translation, FloatingWindowTextRole.TRANSLATION, index))
        if (index < pairs.lastIndex) {
            add(FloatingWindowTextSegment("\n\n", FloatingWindowTextRole.SEPARATOR, index))
        }
    }
}

internal fun styledFloatingWindowText(
    pairs: List<Pair<String, String>>,
    mode: FloatingWindowContentMode,
    textSizeSp: Float,
    foregroundColor: Int,
    mutedColor: Int,
): CharSequence {
    val result = SpannableStringBuilder()
    val sourceScale = (textSizeSp - 1f).coerceAtLeast(10f) / textSizeSp.coerceAtLeast(1f)
    floatingWindowTextSegments(pairs, mode).forEach { segment ->
        val start = result.length
        result.append(segment.text)
        val end = result.length
        if (start == end) return@forEach
        when (segment.role) {
            FloatingWindowTextRole.SOURCE -> {
                result.setSpan(
                    ForegroundColorSpan(mutedColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                result.setSpan(
                    RelativeSizeSpan(sourceScale),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            FloatingWindowTextRole.TRANSLATION -> {
                result.setSpan(
                    ForegroundColorSpan(foregroundColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            FloatingWindowTextRole.SEPARATOR -> {
                result.setSpan(
                    ForegroundColorSpan(mutedColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }
    return result
}

internal fun floatingWindowTranslationIndexForSelection(
    pairs: List<Pair<String, String>>,
    mode: FloatingWindowContentMode,
    selectionStart: Int,
    selectionEnd: Int,
): Int? {
    if (selectionStart < 0 || selectionEnd < 0 || selectionStart == selectionEnd) return null
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    var offset = 0
    val touched = mutableListOf<FloatingWindowTextSegment>()
    floatingWindowTextSegments(pairs, mode).forEach { segment ->
        val segmentStart = offset
        val segmentEnd = offset + segment.text.length
        if (start < segmentEnd && end > segmentStart) touched += segment
        offset = segmentEnd
    }
    if (end > offset || touched.isEmpty()) return null
    if (touched.any { it.role != FloatingWindowTextRole.TRANSLATION }) return null
    return touched.map(FloatingWindowTextSegment::pairIndex).distinct().singleOrNull()
}

internal fun hasSelectableFloatingWindowContent(
    pairs: List<Pair<String, String>>,
    mode: FloatingWindowContentMode,
): Boolean = pairs.any { (source, translation) ->
    (mode == FloatingWindowContentMode.SRC_AND_DST &&
        isTranslationBlockTextActionable(source)) ||
        isTranslationBlockTextActionable(translation)
}
