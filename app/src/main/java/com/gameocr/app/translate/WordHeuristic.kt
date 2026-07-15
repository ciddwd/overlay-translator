package com.gameocr.app.translate

/** Normalizes short OCR selections and decides whether dictionary mode should be attempted. */
object WordHeuristic {
    private val sentencePunctuation = setOf(
        '.', '!', '?', ';',
        '\u3002', '\uff01', '\uff1f', '\uff1b',
    )

    private val boundaryPunctuation = sentencePunctuation + setOf(
        ',', ':', '"', '\'', '`', '~', '|', '/', '\\',
        '(', ')', '[', ']', '{', '}', '<', '>',
        '\u00ab', '\u00bb', '\u2018', '\u2019', '\u201c', '\u201d',
        '\u2022', '\u2026', '\u3001', '\u3008', '\u3009', '\u300a', '\u300b',
        '\u300c', '\u300d', '\u300e', '\u300f', '\u3010', '\u3011',
        '\uff0c', '\uff1a', '\uff08', '\uff09',
    )

    fun isWord(text: String, sourceLang: String): Boolean =
        dictionaryTermOrNull(text, sourceLang) != null

    fun dictionaryTermOrNull(text: String, sourceLang: String): String? {
        val singleLine = text.trim()
        if (singleLine.isEmpty() || singleLine.contains('\n') || singleLine.contains('\r')) {
            return null
        }

        val unwrapped = singleLine.trim { it.isWhitespace() || it in boundaryPunctuation }
        if (unwrapped.isEmpty()) return null

        val isCjk = sourceLang.startsWith("zh", ignoreCase = true) ||
            sourceLang.equals("ja", ignoreCase = true) ||
            sourceLang.equals("ko", ignoreCase = true) ||
            (sourceLang.equals("auto", ignoreCase = true) && unwrapped.all(::isCjkOrPunct))

        if (isCjk) {
            val normalized = unwrapped.filter {
                !it.isWhitespace() && it !in boundaryPunctuation
            }
            return normalized.takeIf { it.length in 1..4 }
        }

        val normalized = unwrapped.replace(Regex("\\s+"), " ")
        if (normalized.any { it in sentencePunctuation }) return null
        val tokens = normalized.split(' ').filter(String::isNotBlank)
        return normalized.takeIf {
            tokens.size in 1..2 && tokens.all { token -> token.length <= 32 }
        }
    }

    private fun isCjkOrPunct(char: Char): Boolean {
        if (char.isWhitespace() || char in boundaryPunctuation) return true
        val block = Character.UnicodeBlock.of(char) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO ||
            block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }
}
