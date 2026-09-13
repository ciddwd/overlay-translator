package com.gameocr.app.ocr

import org.junit.Assert.*
import org.junit.Test

class AutoOcrLanguagePolicyTest {
    @Test fun languageConfidenceAndShortText_tableDriven() {
        data class Case(val text: String, val candidates: List<LanguageScore>, val expected: String?)
        listOf(
            Case("OK", listOf(LanguageScore("en", .99f)), null),
            Case("12345", listOf(LanguageScore("en", .99f)), null),
            Case("成功", listOf(LanguageScore("zh", .99f)), null),
            Case("こんにちは", listOf(LanguageScore("ja", .95f)), "ja"),
            Case("안녕하세요", listOf(LanguageScore("ko", .95f)), "ko"),
            Case("这是中文测试", listOf(LanguageScore("zh-Hans", .95f)), "zh"),
            Case("中文识别", listOf(LanguageScore("zh", .7f)), null),
            Case("Good morning", listOf(LanguageScore("en", .95f)), "en"),
            Case("Good morning", listOf(LanguageScore("en", .6f)), null),
            Case("similar word", listOf(LanguageScore("en", .7f), LanguageScore("fr", .6f)), null),
            Case("こんにちは", listOf(LanguageScore("zh", .95f)), null),
            Case("안녕하세요", listOf(LanguageScore("ja", .95f)), null),
            Case("Good morning", listOf(LanguageScore("und", 1f)), null),
            Case("Good morning", listOf(LanguageScore("en", Float.NaN)), null),
        ).forEach { case -> assertEquals(case.text, case.expected, AutoOcrLanguagePolicy.choose(case.text, case.candidates)) }
    }

    @Test fun strayUiTextDoesNotChooseWholePageLanguage() {
        val ja = AutoOcrEvidence("これは日本語の文章です。今日はとても良い天気ですね。", "ja", .9f)
        val en = AutoOcrEvidence("Good morning everyone, welcome to this page.", "en", .9f)
        assertEquals("ja", AutoOcrLanguagePolicy.dominant(listOf(ja, AutoOcrEvidence("OK", null, 1f))))
        assertNull(AutoOcrLanguagePolicy.dominant(listOf(AutoOcrEvidence("OK", null, 1f))))
        assertNull(AutoOcrLanguagePolicy.dominant(listOf(ja, en)))
        // No previous-page state can turn a new ambiguous token into a confident language.
        assertNull(AutoOcrLanguagePolicy.dominant(listOf(AutoOcrEvidence("成功", null, 1f))))
    }

    @Test fun refinementsPreserveOtherLanguagesAndUncoveredText_tableDriven() {
        data class Box(val start: Int, val end: Int, val lang: String?)
        val originals = listOf(Box(0, 10, "ja"), Box(10, 20, null), Box(50, 60, "en"), Box(80, 90, null))
        val mergedJa = Box(0, 20, "ja")
        val crossing = Box(0, 60, "ja")
        listOf(
            emptyList<Box>() to originals,
            listOf(mergedJa) to listOf(originals[2], originals[3], mergedJa),
            listOf(crossing) to originals,
        ).forEach { (refined, expected) ->
            assertEquals(expected, replaceAutoOcrRegions(originals, refined, "ja", { it.lang }) { a, b ->
                minOf(a.end, b.end) > maxOf(a.start, b.start)
            })
        }
    }
}
