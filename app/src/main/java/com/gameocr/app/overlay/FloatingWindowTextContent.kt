package com.gameocr.app.overlay

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
