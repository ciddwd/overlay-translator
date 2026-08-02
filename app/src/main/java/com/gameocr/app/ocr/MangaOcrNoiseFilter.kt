package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect

internal fun shouldDropMangaOcrNoise(
    text: String,
    rect: IntRect,
    imgW: Int,
    imgH: Int
): Boolean {
    val normalized = text.trim().filterNot { it.isWhitespace() }
    if (normalized.isEmpty()) return false
    if (isTinyPunctuationOnly(normalized, rect, imgW, imgH)) return true
    if (normalized.codePointCount(0, normalized.length) != 1) return false

    val codePoint = normalized.codePointAt(0)
    if (isAsciiLetterOrDigit(codePoint)) return false
    if (!isAmbiguousSingleGlyph(codePoint)) return false

    val safeW = imgW.coerceAtLeast(1)
    val safeH = imgH.coerceAtLeast(1)
    val minDim = minOf(safeW, safeH)
    val edgeMargin = maxOf(12, minDim / 64)
    val nearEdge =
        rect.left <= edgeMargin ||
            rect.top <= edgeMargin ||
            safeW - rect.right <= edgeMargin ||
            safeH - rect.bottom <= edgeMargin
    if (!nearEdge) return false

    val width = rect.width.coerceAtLeast(0)
    val height = rect.height.coerceAtLeast(0)
    val shortSide = minOf(width, height)
    val longSide = maxOf(width, height)
    val smallByShape =
        shortSide <= maxOf(64, minDim / 18) &&
            longSide <= maxOf(96, minDim / 10)
    val imageArea = safeW.toLong() * safeH.toLong()
    val rectArea = width.toLong() * height.toLong()
    val smallByArea = rectArea <= imageArea * 0.0025f
    return smallByShape || smallByArea
}

private fun isTinyPunctuationOnly(
    text: String,
    rect: IntRect,
    imgW: Int,
    imgH: Int,
): Boolean {
    if (text.codePointCount(0, text.length) > MAX_TINY_PUNCTUATION_CODE_POINTS) return false
    var offset = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        if (!isPunctuation(codePoint)) return false
        offset += Character.charCount(codePoint)
    }
    val safeW = imgW.coerceAtLeast(1)
    val safeH = imgH.coerceAtLeast(1)
    val minDim = minOf(safeW, safeH)
    val width = rect.width.coerceAtLeast(0)
    val height = rect.height.coerceAtLeast(0)
    val shortSide = minOf(width, height)
    val longSide = maxOf(width, height)
    val imageArea = safeW.toLong() * safeH
    val rectArea = width.toLong() * height
    return shortSide <= maxOf(24, minDim / 64) &&
        longSide <= maxOf(48, minDim / 32) &&
        rectArea <= imageArea * MAX_TINY_PUNCTUATION_AREA_RATIO
}

private fun isPunctuation(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
    -> true
    else -> false
}

private fun isAsciiLetterOrDigit(codePoint: Int): Boolean =
    codePoint in '0'.code..'9'.code ||
        codePoint in 'A'.code..'Z'.code ||
        codePoint in 'a'.code..'z'.code

private fun isAmbiguousSingleGlyph(codePoint: Int): Boolean =
    isCjkIdeograph(codePoint) || codePoint in ambiguousLineLikeGlyphs

private fun isCjkIdeograph(codePoint: Int): Boolean =
    codePoint in 0x3400..0x4DBF ||
        codePoint in 0x4E00..0x9FFF ||
        codePoint in 0xF900..0xFAFF

private val ambiguousLineLikeGlyphs = setOf(
    '-'.code,
    '|'.code,
    0x30FC,
    0x2015,
    0x2014,
    0xFF5C,
    0x30FB,
    0xFF65,
    0x3002,
    0x3001,
    0x2026
)

private const val MAX_TINY_PUNCTUATION_CODE_POINTS = 3
private const val MAX_TINY_PUNCTUATION_AREA_RATIO = 0.0005f
