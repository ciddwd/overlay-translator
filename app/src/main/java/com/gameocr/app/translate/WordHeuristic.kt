package com.gameocr.app.translate

/**
 * 判断一段 OCR 文本是「单个词 / 短语」还是「整句」。划词翻译用这个决定要不要走 LLM 词典 prompt
 * （仅 OpenAI 兼容引擎），其他翻译引擎不读这个值（永远走纯翻译）。
 *
 * 规则按源语言粗分两类：
 *  - **拉丁 / 西文（含 BCP-47 "auto"，把空白视为分词依据）**：trim 后按空白分词，token ≤ 2 且
 *    不含句末标点（. ! ? ;）→ 单词。
 *  - **CJK（zh* / ja / ko）**：去掉空白和中日韩 ASCII 标点后字符数 ≤ 4 → 单词。
 *
 * 行内换行直接归为句子（OCR 跨行通常意味着段落，不是单词）。
 */
object WordHeuristic {
    private val SENTENCE_PUNCT = setOf(
        '.', '!', '?', ';',
        '。', '！', '？', '；', '，', '、'
    )

    fun isWord(text: String, sourceLang: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.contains('\n')) return false

        val isCjk = sourceLang.startsWith("zh", ignoreCase = true) ||
            sourceLang.equals("ja", ignoreCase = true) ||
            sourceLang.equals("ko", ignoreCase = true) ||
            // auto 模式下也按字符判别 —— 如果整段全是 CJK，按 CJK 规则
            (sourceLang.equals("auto", ignoreCase = true) && t.all { isCjkOrPunct(it) })

        return if (isCjk) {
            val cleaned = t.filter { !it.isWhitespace() && it !in SENTENCE_PUNCT }
            cleaned.length in 1..4
        } else {
            // 含句末标点 → 句子
            if (t.any { it in SENTENCE_PUNCT }) return false
            val tokens = t.split(Regex("\\s+")).filter { it.isNotBlank() }
            tokens.size <= 2 && tokens.all { it.length <= 32 }
        }
    }

    private fun isCjkOrPunct(c: Char): Boolean {
        if (c.isWhitespace()) return true
        if (c in SENTENCE_PUNCT) return true
        val block = Character.UnicodeBlock.of(c) ?: return false
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
