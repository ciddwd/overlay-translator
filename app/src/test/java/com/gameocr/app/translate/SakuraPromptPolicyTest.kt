package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeDialogueTurn
import com.gameocr.app.data.RuntimeGlossaryTerm
import com.gameocr.app.data.RuntimeTranslationPromptContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SakuraPromptPolicyTest {
    @Test
    fun build_tableDriven_usesOnlyOfficialSakuraFormats() {
        data class Case(
            val name: String,
            val context: RuntimeTranslationPromptContext,
            val expected: String,
        )

        listOf(
            Case(
                name = "plain official prompt",
                context = RuntimeTranslationPromptContext(),
                expected = SakuraPromptPolicy.BASIC_INSTRUCTION + "現在の台詞",
            ),
            Case(
                name = "official glossary prompt",
                context = RuntimeTranslationPromptContext(
                    glossary = listOf(RuntimeGlossaryTerm("アリス\n", " 爱丽丝 ")),
                ),
                expected = "${SakuraPromptPolicy.GLOSSARY_HEADER}\nアリス->爱丽丝\n" +
                    "${SakuraPromptPolicy.GLOSSARY_INSTRUCTION}現在の台詞",
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expected, SakuraPromptPolicy.build("現在の台詞", case.context))
        }
    }

    @Test
    fun build_usesSuccessfulPreviousTurnsAsOfficialSakuraReferencesOnly() {
        val prompt = SakuraPromptPolicy.build(
            source = "current-source",
            context = RuntimeTranslationPromptContext(
                currentApplication = "Game",
                currentPage = listOf("other-current-source"),
                previousFrame = listOf(
                    RuntimeDialogueTurn("previous-success", "previous-translation"),
                    RuntimeDialogueTurn("previous-failure", null),
                ),
            ),
        )

        assertTrue(prompt.endsWith("current-source"))
        assertTrue(prompt.contains("previous-success->previous-translation"))
        assertFalse(prompt.contains("Game"))
        assertFalse(prompt.contains("other-current-source"))
        assertFalse(prompt.contains("previous-failure"))
        assertFalse(prompt.contains("dialogue_context_json"))
    }

    @Test
    fun build_tableDriven_manualGlossaryWinsAndDuplicateHistoryIsStable() {
        data class Case(
            val name: String,
            val context: RuntimeTranslationPromptContext,
            val expectedPair: String,
            val unexpectedPair: String,
        )
        listOf(
            Case(
                name = "manual glossary wins",
                context = RuntimeTranslationPromptContext(
                    glossary = listOf(RuntimeGlossaryTerm("name", "manual")),
                    previousFrame = listOf(RuntimeDialogueTurn("name", "history")),
                ),
                expectedPair = "name->manual",
                unexpectedPair = "name->history",
            ),
            Case(
                name = "first previous translation wins",
                context = RuntimeTranslationPromptContext(
                    previousFrame = listOf(
                        RuntimeDialogueTurn("line", "first"),
                        RuntimeDialogueTurn("line", "second"),
                    ),
                ),
                expectedPair = "line->first",
                unexpectedPair = "line->second",
            ),
        ).forEach { case ->
            val prompt = SakuraPromptPolicy.build("current", case.context)
            assertTrue(case.name, prompt.contains(case.expectedPair))
            assertFalse(case.name, prompt.contains(case.unexpectedPair))
        }
    }
}
