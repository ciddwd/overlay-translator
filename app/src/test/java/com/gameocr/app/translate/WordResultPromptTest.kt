package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WordResultPromptTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun difficultyContractSupportsLegacyAndCurrentPrompts() {
        data class Case(
            val name: String,
            val prompt: String,
            val shouldReuseInstance: Boolean
        )

        val cases = listOf(
            Case("legacy custom prompt", "Return dictionary JSON.", false),
            Case("current default prompt", Settings.DEFAULT_DICTIONARY_PROMPT, true)
        )

        cases.forEach { case ->
            val resolved = case.prompt.withDifficultyNotesContract("Simplified Chinese")
            assertTrue(case.name, resolved.contains("\"difficulty_notes\""))
            assertEquals(case.name, 1, Regex("\"difficulty_notes\"").findAll(resolved).count())
            if (case.shouldReuseInstance) assertSame(case.name, case.prompt, resolved)
        }
    }

    @Test
    fun parsesDifficultyNotesAndKeepsLegacyJsonCompatible() {
        data class Case(
            val name: String,
            val raw: String,
            val expectedNotes: List<String>
        )

        val cases = listOf(
            Case(
                "new schema",
                """```json
                    {"phonetic":"/kjuː/","pos":["n."],"definitions":["队列"],"difficulty_notes":["计算机领域中指先进先出的数据结构"],"examples":[]}
                    ```""".trimIndent(),
                listOf("计算机领域中指先进先出的数据结构")
            ),
            Case(
                "legacy schema",
                """{"phonetic":"","pos":[],"definitions":["队列"],"examples":[]}""",
                emptyList()
            )
        )

        cases.forEach { case ->
            val result = parseWordResult(case.raw, json)
            assertEquals(case.name, case.expectedNotes, result?.difficultyNotes)
            assertEquals(case.name, listOf("队列"), result?.definitions)
        }
    }

    @Test
    fun rejectsBlankOrStructurallyEmptyResults() {
        listOf(
            "",
            "not json",
            """{"phonetic":"","pos":[],"definitions":[],"difficulty_notes":[],"examples":[]}"""
        ).forEach { raw ->
            assertNull(raw, parseWordResult(raw, json))
        }
    }
}
