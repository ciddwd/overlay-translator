package com.gameocr.app.translate

import com.gameocr.app.data.TranslatorEngine
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

    @Test
    fun structuredDictionaryTermOrNull_tableDriven_requiresOpenAiAndEligibleText() {
        data class Case(
            val name: String,
            val text: String,
            val sourceLang: String,
            val engine: TranslatorEngine,
            val expected: String?,
        )

        val cases = buildList {
            add(Case("OpenAI English word", "update", "en", TranslatorEngine.OPENAI, "update"))
            add(Case("OpenAI English phrase", "release notes", "en", TranslatorEngine.OPENAI, "release notes"))
            add(Case("OpenAI Japanese term", "更新", "ja", TranslatorEngine.OPENAI, "更新"))
            add(Case("OpenAI sentence", "please update this", "en", TranslatorEngine.OPENAI, null))
            TranslatorEngine.entries
                .filterNot { it == TranslatorEngine.OPENAI }
                .forEach { engine ->
                    add(Case("$engine has no structured dictionary", "update", "en", engine, null))
                }
        }

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                WordHeuristic.structuredDictionaryTermOrNull(
                    text = case.text,
                    sourceLang = case.sourceLang,
                    translatorEngine = case.engine,
                ),
            )
        }
    }

    @Test
    fun manuallySelectedDictionaryTermOrNull_tableDriven_acceptsDeliberateLongerTerms() {
        data class Case(
            val name: String,
            val text: String,
            val sourceLang: String,
            val engine: TranslatorEngine = TranslatorEngine.OPENAI,
            val expected: String?,
        )

        val cases = listOf(
            Case("long Japanese word", "引き受けられる", "ja", expected = "引き受けられる"),
            Case("Japanese fixed phrase", "お世話になりました", "ja", expected = "お世話になりました"),
            Case("four word English phrase", "take it for granted", "en", expected = "take it for granted"),
            Case("five word English phrase", "get out of the way", "en", expected = "get out of the way"),
            Case("six word sentence", "please get out of the way", "en", expected = null),
            Case("long CJK sentence", "这是一个超过限制的完整句子", "zh-CN", expected = null),
            Case("internal sentence punctuation", "hello.world", "en", expected = null),
            Case(
                "non OpenAI remains ineligible",
                "take it for granted",
                "en",
                TranslatorEngine.DEEPL,
                null,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                WordHeuristic.manuallySelectedDictionaryTermOrNull(
                    text = case.text,
                    sourceLang = case.sourceLang,
                    translatorEngine = case.engine,
                ),
            )
        }
        assertEquals(
            "OCR heuristic remains conservative",
            null,
            WordHeuristic.structuredDictionaryTermOrNull(
                "引き受けられる",
                "ja",
                TranslatorEngine.OPENAI,
            ),
        )
    }
}
