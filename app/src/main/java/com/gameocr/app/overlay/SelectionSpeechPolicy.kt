package com.gameocr.app.overlay

import kotlin.math.max
import kotlin.math.min

internal fun selectedTextForSpeech(
    text: CharSequence,
    selectionStart: Int,
    selectionEnd: Int,
): String? {
    if (selectionStart < 0 || selectionEnd < 0 || text.isEmpty()) return null
    val start = min(selectionStart, selectionEnd).coerceAtMost(text.length)
    val end = max(selectionStart, selectionEnd).coerceAtMost(text.length)
    if (start >= end) return null
    return text.subSequence(start, end).toString().trim().takeIf(String::isNotEmpty)
}
