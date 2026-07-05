package com.gameocr.app.overlay

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
    minReadableTextSizePx: Float
): Float {
    val ratioFloor = originalTextSizePx * 0.86f
    return minOf(originalTextSizePx, maxOf(ratioFloor, minReadableTextSizePx))
}

private fun needsAsciiWordSeparator(left: Char, right: Char): Boolean =
    left.isAsciiWordChar() && right.isAsciiWordChar()

private fun Char.isAsciiWordChar(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
