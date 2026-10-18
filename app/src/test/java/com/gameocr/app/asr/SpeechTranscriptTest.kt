package com.gameocr.app.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTranscriptTest {
    private fun SpeechTranscript.pairs() = display().map { it.source to it.translation }

    @Test fun partialsReviseOneRecordAndFinalsKeepItsId_tableDriven() {
        data class Step(val generation: Long, val segment: Long, val text: String, val final: Boolean)
        data class Case(val name: String, val steps: List<Step>, val expected: List<Pair<String, String>>, val distinctIds: Int)
        listOf(
            Case("revise then finish", listOf(Step(1, 0, "a", false), Step(1, 0, "ab", false), Step(1, 0, "abc", true)),
                listOf("abc" to "…"), 1),
            // An abandoned utterance (engine reset, empty final) is replaced, not kept.
            Case("abandoned partial", listOf(Step(1, 0, "lost", false), Step(1, 1, "next", false)),
                listOf("next" to "…"), 1),
            Case("final without partial", listOf(Step(1, 0, "one", true), Step(1, 1, "two", true)),
                listOf("one" to "…", "two" to "…"), 2),
            // Same segment number in a new generation is a different utterance.
            Case("generation splits records", listOf(Step(1, 3, "old", true), Step(2, 3, "new", false)),
                listOf("old" to "…", "new" to "…"), 2),
        ).forEach { case ->
            val transcript = SpeechTranscript()
            case.steps.forEach { step ->
                if (step.final) transcript.onFinal(step.generation, step.segment, step.text)
                else transcript.onPartial(step.generation, step.segment, step.text)
            }
            assertEquals(case.name, case.expected, transcript.pairs())
            assertEquals(case.name, case.distinctIds, transcript.all.map { it.id }.distinct().size)
        }
    }

    @Test fun translationsApplyByIdAndRenderByState_tableDriven() {
        data class Case(val state: SpeechRecordState, val translation: String, val shown: String)
        listOf(
            Case(SpeechRecordState.TRANSLATING, "", "…"),
            Case(SpeechRecordState.TRANSLATING, "par", "par"),
            Case(SpeechRecordState.DONE, "full", "full"),
            Case(SpeechRecordState.FAILED, "", ""),
        ).forEach { case ->
            val transcript = SpeechTranscript()
            val first = transcript.onFinal(1, 0, "first")
            transcript.onFinal(1, 1, "second")
            assertTrue(transcript.applyTranslation(first.id, case.translation, case.state))
            assertEquals(case.toString(), listOf("first" to case.shown, "second" to "…"), transcript.pairs())
        }
    }

    @Test fun generationBoundaryKeepsHistoryAndRejectsLateResults() {
        val transcript = SpeechTranscript()
        val done = transcript.onFinal(1, 0, "done")
        val pending = transcript.onFinal(1, 1, "pending")
        transcript.onPartial(1, 2, "speaking")
        transcript.applyTranslation(done.id, "T", SpeechRecordState.DONE)
        transcript.closeGeneration()
        assertEquals(listOf("done" to "T", "pending" to ""), transcript.pairs())
        assertFalse(transcript.applyTranslation(pending.id, "late", SpeechRecordState.DONE))
        assertEquals(listOf("done" to "T", "pending" to ""), transcript.pairs())
    }

    @Test fun trimKeepsNewestAndForgottenRecordsIgnoreTranslations_tableDriven() {
        data class Case(val count: Int, val max: Int, val kept: List<String>)
        listOf(
            Case(5, 3, listOf("s2", "s3", "s4")),
            Case(2, 3, listOf("s0", "s1")),
            Case(3, 0, listOf("s2")),
        ).forEach { case ->
            val transcript = SpeechTranscript()
            val records = (0 until case.count).map { transcript.onFinal(1, it.toLong(), "s$it") }
            transcript.trim(case.max)
            assertEquals(case.toString(), case.kept, transcript.display().map { it.source })
            val trimmedAway = records.first().source !in case.kept
            assertEquals(case.toString(), !trimmedAway, transcript.applyTranslation(records.first().id, "T", SpeechRecordState.DONE))
        }
    }
}
