package com.gameocr.app.asr

import com.gameocr.app.data.RuntimeDialogueTurn
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTranslationContextTest {
    @Test fun historyKeepsNewestEarlierTurnsWithUsableTranslationsOnly() {
        val history = SpeechTranslationHistory(maxTurns = 2)
        history.add(1, " one ")
        history.add(2, "two")
        history.add(3, "three")
        history.add(4, "four")
        history.complete(2, "二")
        history.complete(3, "three")
        // Record 4 is still waiting: only earlier records are context, newest last.
        assertEquals(
            listOf(RuntimeDialogueTurn("two", "二"), RuntimeDialogueTurn("three")),
            history.before(4, "k"),
        )
        assertEquals(listOf(RuntimeDialogueTurn("one")), history.before(2, "k"))
    }

    @Test fun historyDropsTurnsWrittenForOtherSettingsAndOnClear() {
        val history = SpeechTranslationHistory()
        history.add(1, "old")
        history.add(2, "current")
        assertEquals(listOf("old"), history.before(2, "a").map { it.source })
        // A new key drops what was spoken before this record; this record stays for the next one.
        assertTrue(history.before(2, "b").isEmpty())
        history.add(3, "next")
        assertEquals(listOf("current"), history.before(3, "b").map { it.source })
        history.clear()
        assertTrue(history.before(3, "b").isEmpty())
    }

    @Test fun historyCapacityEvictsOldestRecords() {
        val history = SpeechTranslationHistory(maxTurns = 10, capacity = 3)
        (1L..5L).forEach { history.add(it, "s$it") }
        assertEquals(listOf("s3", "s4"), history.before(5, "k").map { it.source })
    }

    @Test fun policyFollowsContextModeAndEngineSupport_tableDriven() {
        val turns = listOf(RuntimeDialogueTurn("Where is Ken?", "肯在哪？"))
        data class Case(val engine: TranslatorEngine, val mode: TranslationContextMode, val contextual: Boolean)
        listOf(
            Case(TranslatorEngine.OPENAI, TranslationContextMode.FAST_PER_SEGMENT, false),
            Case(TranslatorEngine.OPENAI, TranslationContextMode.PAGE_CONTEXT, true),
            Case(TranslatorEngine.OPENAI, TranslationContextMode.CONTINUOUS_CONTEXT, true),
            Case(TranslatorEngine.DEEPL, TranslationContextMode.CONTINUOUS_CONTEXT, false),
        ).forEach { case ->
            val settings = Settings(translatorEngine = case.engine, translationContextMode = case.mode)
            val result = SpeechTranslationContextPolicy.contextualize(settings, "He left.", turns)
            if (case.contextual) {
                val context = result.runtimeTranslationPromptContext
                assertEquals(case.toString(), TranslationContextMode.CONTINUOUS_CONTEXT, result.translationContextMode)
                assertEquals(case.toString(), turns, context.previousFrame)
                assertEquals(case.toString(), listOf("He left."), context.currentPage)
                assertTrue(case.toString(), result.runtimeTranslationContext.contains("Where is Ken?"))
            } else {
                assertSame(case.toString(), settings, result)
            }
        }
    }

    @Test fun policyLeavesFirstSegmentUnchanged() {
        val settings = Settings(translationContextMode = TranslationContextMode.CONTINUOUS_CONTEXT)
        assertSame(settings, SpeechTranslationContextPolicy.contextualize(settings, "Hi", emptyList()))
    }
}
