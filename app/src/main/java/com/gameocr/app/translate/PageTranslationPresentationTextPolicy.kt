package com.gameocr.app.translate

import com.gameocr.app.data.RenderMode

/** Keeps renderer-specific text shaping outside OCR grouping and translation protocol code. */
internal object PageTranslationPresentationTextPolicy {
    fun normalize(
        presentation: RenderMode,
        text: String,
    ): String = when (presentation) {
        RenderMode.BLOCKS -> text
        RenderMode.FLOATING_WINDOW -> FloatingWindowSingleLineTextPolicy.normalize(text)
    }

    fun normalizeHistory(
        presentation: RenderMode,
        frame: DialogueContextFrame?,
    ): DialogueContextFrame? {
        if (frame == null || presentation == RenderMode.BLOCKS) return frame
        return frame.copy(
            items = frame.items.map { item ->
                item.copy(
                    source = normalize(presentation, item.source),
                    translation = item.translation?.let { normalize(presentation, it) },
                    reusableOutput = item.reusableOutput?.let { normalize(presentation, it) },
                )
            }
        )
    }
}

/** Converts one floating-window row to one visual and request line without changing Blocks text. */
internal object FloatingWindowSingleLineTextPolicy {
    private val lineBreak = Regex("\\r\\n|[\\r\\n]")

    fun normalize(text: String): String {
        if ('\r' !in text && '\n' !in text) return text
        val segments = text.split(lineBreak)
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (segments.isEmpty()) return ""
        return buildString(text.length) {
            append(segments.first())
            segments.drop(1).forEach { segment ->
                if (needsWordSeparator(lastCodePoint(), segment.firstCodePoint())) append(' ')
                append(segment)
            }
        }
    }

    private fun CharSequence.firstCodePoint(): Int = Character.codePointAt(this, 0)

    private fun CharSequence.lastCodePoint(): Int = Character.codePointBefore(this, length)

    private fun needsWordSeparator(left: Int, right: Int): Boolean =
        isSpacingWordCharacter(right) &&
            (isSpacingWordCharacter(left) || isTrailingPunctuation(left))

    private fun isTrailingPunctuation(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() ->
            Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN
        else -> false
    }

    private fun isSpacingWordCharacter(codePoint: Int): Boolean {
        if (!Character.isLetterOrDigit(codePoint)) return false
        return when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA -> false
            else -> true
        }
    }
}
