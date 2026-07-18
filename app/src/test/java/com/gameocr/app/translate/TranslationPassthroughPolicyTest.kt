package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationPassthroughPolicyTest {

    @Test
    fun shouldPassthroughNumericTranslation_tableDriven_handlesUnicodeAndMixedText() {
        data class Case(val name: String, val source: String, val expected: Boolean)

        val cases = listOf(
            Case("ascii integer", "189", true),
            Case("surrounding whitespace", "  189\n", true),
            Case("fullwidth digits", "１２３", true),
            Case("decimal percentage", "-12.5%", true),
            Case("time", "12:30", true),
            Case("page marker", "#35", true),
            Case("currency", "\$1,299.00", true),
            Case("roman numeral", "Ⅳ", true),
            Case("enclosed number", "⑩", true),
            Case("empty", "", false),
            Case("punctuation only", "...", false),
            Case("Japanese counter", "7号", false),
            Case("chapter label", "第10話", false),
            Case("unit suffix", "10kg", false),
            Case("Chinese number words", "一百八十九", false),
            Case("number plus letter", "①章", false),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, shouldPassthroughNumericTranslation(case.source))
        }
    }

    @Test
    fun planNumericTranslationPassthrough_tableDriven_partitionsAndRestoresIndexes() {
        data class Case(
            val name: String,
            val sources: List<String>,
            val expectedTranslatableIndexes: List<Int>,
            val translated: List<String?>,
            val expectedMerged: List<String?>,
        )

        val cases = listOf(
            Case("empty", emptyList(), emptyList(), emptyList(), emptyList()),
            Case("all passthrough", listOf("189", "12:30"), emptyList(), emptyList(), listOf("189", "12:30")),
            Case(
                "mixed preserves original indexes",
                listOf("189", "第10話", "12:30", "こんにちは"),
                listOf(1, 3),
                listOf("第十话", "你好"),
                listOf("189", "第十话", "12:30", "你好"),
            ),
            Case(
                "missing translated output remains null",
                listOf("35", "翻訳", "本文"),
                listOf(1, 2),
                listOf("翻译"),
                listOf("35", "翻译", null),
            ),
        )

        cases.forEach { case ->
            val plan = planNumericTranslationPassthrough(case.sources)
            assertEquals(case.name, case.expectedTranslatableIndexes, plan.translatableIndexes)
            assertEquals(case.name, case.expectedMerged, plan.merge(case.translated))
            plan.translatableIndexes.forEachIndexed { translatedIndex, sourceIndex ->
                assertEquals(case.name, sourceIndex, plan.originalIndexFor(translatedIndex))
            }
            assertEquals(case.name, null, plan.originalIndexFor(plan.translatableIndexes.size))
        }
    }
}
