package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class WordHeuristicTest {

    @Test
    fun dictionaryTermOrNull_normalizesBoundaryNoiseAndRejectsSentences() {
        data class Case(
            val name: String,
            val text: String,
            val sourceLang: String,
            val expected: String?,
        )

        val cases = listOf(
            Case("trailing exclamation", "please!", "en", "please"),
            Case("OCR trailing arrow", "announcement >", "en", "announcement"),
            Case("curly quotes", "“update”", "auto", "update"),
            Case("two-word phrase", "release notes!", "en", "release notes"),
            Case("apostrophe preserved", "don't", "en", "don't"),
            Case("hyphens preserved", "state-of-the-art", "en", "state-of-the-art"),
            Case("professional symbol preserved", "C++", "en", "C++"),
            Case("three-word sentence", "please update now!", "en", null),
            Case("internal sentence punctuation", "hello.world", "en", null),
            Case("multiline OCR", "hello\nworld", "en", null),
            Case("CJK brackets", "「更新」", "ja", "更新"),
            Case("short Chinese term", "翻译器。", "zh-CN", "翻译器"),
            Case("long Chinese phrase", "这是一个完整句子。", "zh-CN", null),
            Case("punctuation only", "...", "auto", null),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                WordHeuristic.dictionaryTermOrNull(case.text, case.sourceLang),
            )
            assertEquals(case.name, case.expected != null, WordHeuristic.isWord(case.text, case.sourceLang))
        }
    }
}
