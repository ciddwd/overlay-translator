package com.gameocr.app.overlay

/** Reflow only a recognized prose paragraph, never independent labels or a structured list. */
internal fun reflowsHorizontalOcrParagraph(source: String, sourceLineCount: Int): Boolean {
    if (sourceLineCount < 2) return false
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').trim().lines()
    return lines.size >= 2 && lines.none { it.isBlank() || isStructuredTranslationLine(it) } &&
        lines.count { it.trim().codePointCount(0, it.trim().length) >= 24 } >= 2
}

/** Render-only: retain the raw translation for copying, speech, history, and subsequent updates. */
internal fun horizontalTranslationDisplayText(text: String, reflowParagraph: Boolean): String {
    if (!reflowParagraph || text.isBlank()) return text
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    if (normalized.lines().any(::isStructuredTranslationLine)) return text
    return normalized.split(Regex("\n[\t 　]*\n(?:[\t 　]*\n)*")).joinToString("\n\n") { paragraph ->
        val out = StringBuilder(paragraph.length)
        var index = 0
        while (index < paragraph.length) {
            val point = Character.codePointAt(paragraph, index)
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) {
                out.appendCodePoint(point)
                index += Character.charCount(point)
                continue
            }
            val start = index
            while (index < paragraph.length) {
                val next = Character.codePointAt(paragraph, index)
                if (!Character.isWhitespace(next) && !Character.isSpaceChar(next)) break
                index += Character.charCount(next)
            }
            if (out.isEmpty() || index == paragraph.length) continue
            val left = Character.codePointBefore(out, out.length)
            val right = Character.codePointAt(paragraph, index)
            val softBreak = paragraph.substring(start, index).contains('\n')
            val unspacedBoundary = if (softBreak) left.isUnspacedTypography() || right.isUnspacedTypography()
                else left.isUnspacedTypography() && right.isUnspacedTypography()
            if (!unspacedBoundary) out.append(' ')
        }
        out.toString()
    }
}

private fun isStructuredTranslationLine(line: String): Boolean =
    Regex("^(?:[-*•●]|[0-9]+[.)、]|[A-Za-z][.)])\\s+.*").matches(line.trimStart())

private fun Int.isUnspacedScript(): Boolean = when (Character.UnicodeScript.of(this)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
    else -> false
}

private fun Int.isUnspacedTypography(): Boolean = isUnspacedScript() ||
    (this <= Char.MAX_VALUE.code && toChar() in "，。！？；：、…「」『』（）【】《》")
