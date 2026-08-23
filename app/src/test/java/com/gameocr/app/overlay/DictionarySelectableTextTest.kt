package com.gameocr.app.overlay

import com.gameocr.app.translate.ExamplePair
import com.gameocr.app.translate.WordResult
import com.gameocr.app.translate.WordSense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionarySelectableTextTest {

    private val labels = DictionaryTextLabels(
        phonetic = "Phonetic",
        partOfSpeech = "Part of speech",
        definitions = "Definitions",
        inflections = "Inflections",
        synonyms = "Synonyms",
        difficultyNotes = "Notes",
        examples = "Examples",
    )

    @Test
    fun dictionaryPlainText_keepsEverySectionInOneContinuousRange() {
        data class Case(
            val name: String,
            val result: WordResult,
            val expected: String,
        )

        listOf(
            Case("empty", WordResult(), ""),
            Case(
                "metadata",
                WordResult(phonetic = "/test/", pos = listOf("n.", "v.")),
                "Phonetic  /test/\nPart of speech  n. / v.",
            ),
            Case(
                "multiple definitions",
                WordResult(definitions = listOf("first meaning", "second meaning")),
                "Definitions\n1. first meaning\n2. second meaning",
            ),
            Case(
                "multiple inflections",
                WordResult(inflections = listOf("past: went", "past participle: gone")),
                "Inflections\n・past: went\n・past participle: gone",
            ),
            Case(
                "multiple synonyms",
                WordResult(synonyms = listOf("move", "travel", "proceed")),
                "Synonyms\nmove / travel / proceed",
            ),
            Case(
                "multiple difficulty notes",
                WordResult(difficultyNotes = listOf("rare usage", "technical usage")),
                "Notes\n・rare usage\n・technical usage",
            ),
            Case(
                "multiple bilingual examples",
                WordResult(
                    examples = listOf(
                        ExamplePair("source one", "target one"),
                        ExamplePair("source two", "target two"),
                    )
                ),
                "Examples\n・source one\n  target one\n・source two\n  target two",
            ),
            Case(
                "all sections",
                WordResult(
                    phonetic = "/all/",
                    pos = listOf("adj."),
                    definitions = listOf("definition"),
                    inflections = listOf("comparative: better"),
                    synonyms = listOf("fine", "excellent"),
                    difficultyNotes = listOf("note"),
                    examples = listOf(ExamplePair("source", "target")),
                ),
                "Phonetic  /all/\n\nadj.\n1. definition\n\n" +
                    "Inflections\n・comparative: better\n\n" +
                    "Synonyms\nfine / excellent\n\n" +
                    "Notes\n・note\n\n" +
                    "Examples\n・source\n  target",
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expected, dictionaryPlainText(case.result, labels))
        }
    }

    @Test
    fun groupedSenses_tableDriven_keepDefinitionsUnderTheCorrectPartOfSpeech() {
        data class Case(val name: String, val result: WordResult, val expected: String)

        listOf(
            Case(
                "displayed adjective and verb",
                WordResult(
                    lemma = "display",
                    senses = listOf(
                        WordSense("adj.", listOf("显示的")),
                        WordSense(
                            "v.",
                            listOf("表现", "展示", "陈列"),
                            "display 的过去式和过去分词",
                        ),
                    ),
                ),
                "adj.\n1. 显示的\n\nv.\n1. 表现\n2. 展示\n3. 陈列\nInflections  display 的过去式和过去分词",
            ),
            Case(
                "legacy single POS safely groups all meanings",
                WordResult(pos = listOf("v."), definitions = listOf("更新", "升级")),
                "v.\n1. 更新\n2. 升级",
            ),
            Case(
                "legacy multiple POS remains ungrouped",
                WordResult(pos = listOf("n.", "v."), definitions = listOf("记录", "记下")),
                "Part of speech  n. / v.\n\nDefinitions\n1. 记录\n2. 记下",
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expected, dictionaryPlainText(case.result, labels))
        }
    }

    @Test
    fun fullDictionaryText_preservesAllVisualRolesInsideTheSameText() {
        val segments = dictionaryTextSegments(
            WordResult(
                phonetic = "/role/",
                pos = listOf("n."),
                definitions = listOf("definition"),
                inflections = listOf("plural: roles"),
                synonyms = listOf("function"),
                difficultyNotes = listOf("note"),
                examples = listOf(ExamplePair("source", "target")),
            ),
            labels,
        )

        DictionaryTextRole.entries.forEach { role ->
            assertTrue("missing $role", segments.any { it.role == role })
        }
        assertEquals(dictionaryPlainText(
            WordResult(
                phonetic = "/role/",
                pos = listOf("n."),
                definitions = listOf("definition"),
                inflections = listOf("plural: roles"),
                synonyms = listOf("function"),
                difficultyNotes = listOf("note"),
                examples = listOf(ExamplePair("source", "target")),
            ),
            labels,
        ), segments.joinToString(separator = "") { it.text })
    }
}
