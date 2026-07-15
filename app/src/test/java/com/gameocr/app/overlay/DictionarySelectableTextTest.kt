package com.gameocr.app.overlay

import com.gameocr.app.translate.ExamplePair
import com.gameocr.app.translate.WordResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionarySelectableTextTest {

    private val labels = DictionaryTextLabels(
        phonetic = "Phonetic",
        partOfSpeech = "Part of speech",
        definitions = "Definitions",
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
                    difficultyNotes = listOf("note"),
                    examples = listOf(ExamplePair("source", "target")),
                ),
                "Phonetic  /all/\nPart of speech  adj.\n\n" +
                    "Definitions\n1. definition\n\n" +
                    "Notes\n・note\n\n" +
                    "Examples\n・source\n  target",
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
                difficultyNotes = listOf("note"),
                examples = listOf(ExamplePair("source", "target")),
            ),
            labels,
        ), segments.joinToString(separator = "") { it.text })
    }
}
