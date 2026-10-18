package com.gameocr.app.overlay

import com.gameocr.app.ocr.TextOrientation

/** Keep the same number of columns while avoiding a full column plus a one-glyph tail. */
internal fun balancedVerticalColumnCapacity(glyphCount:Int,capacity:Int):Int {
    val limit=capacity.coerceAtLeast(1)
    if(glyphCount<=0)return limit
    val columns=(glyphCount-1)/limit+1
    return (glyphCount-1)/columns+1
}

internal fun resolveOverlayBlockOrientation(
    pageOrientation: TextOrientation,
    blockOrientation: TextOrientation?,
    followRecognition: Boolean,
): TextOrientation =
    blockOrientation
        ?.takeIf { followRecognition && it != TextOrientation.UNKNOWN }
        ?: pageOrientation

internal fun normalizeVerticalOverlayText(raw: String): String {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    if ('\n' !in normalized) return normalized

    val out = StringBuilder(normalized.length)
    normalized.split('\n').forEach { line ->
        val part = line.trim()
        if (part.isEmpty()) return@forEach
        if (out.isNotEmpty() && needsAsciiWordSeparator(out.last(), part.first())) {
            out.append(' ')
        }
        out.append(part)
    }
    return out.toString()
}

internal fun verticalTextReadableMinSizePx(
    originalTextSizePx: Float,
    minReadableTextSizePx: Float,
    minimumOriginalSizeRatio: Float = 0.86f,
): Float {
    val ratioFloor = originalTextSizePx * minimumOriginalSizeRatio.coerceIn(0f, 1f)
    return minOf(originalTextSizePx, maxOf(ratioFloor, minReadableTextSizePx))
}

private fun needsAsciiWordSeparator(left: Char, right: Char): Boolean =
    left.isAsciiWordChar() && right.isAsciiWordChar()

private fun Char.isAsciiWordChar(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
