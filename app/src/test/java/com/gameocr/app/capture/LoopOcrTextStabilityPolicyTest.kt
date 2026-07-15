package com.gameocr.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopOcrTextStabilityPolicyTest {

    @Test
    fun observe_coversStreamingOcrJitterAndRealChanges() {
        data class Case(
            val name: String,
            val previous: String?,
            val current: String,
            val maxObservedLength: Int,
            val expectedRelation: LoopOcrTextRelation,
            val expectedReset: Boolean,
        )

        val stableDialogue = "A Dark Forest\nMan with Red Eyes\nYou're in for one painful ride, Dante."
        listOf(
            Case("first observation starts timer", null, "Hello", 0,
                LoopOcrTextRelation.FIRST_OBSERVATION, true),
            Case("exact text keeps timer", "Hello", "Hello", 5,
                LoopOcrTextRelation.EXACT, false),
            Case("case and block whitespace are OCR jitter", "Man with Red\nEyes", "man with red eyes", 14,
                LoopOcrTextRelation.EXACT, false),
            Case("one recognition substitution is tolerated", stableDialogue,
                stableDialogue.replace("Dante", "Dant0"), stableDialogue.length,
                LoopOcrTextRelation.OCR_JITTER, false),
            Case("returning from a temporary omission is tolerated", stableDialogue.dropLast(2),
                stableDialogue, stableDialogue.length,
                LoopOcrTextRelation.OCR_JITTER, false),
            Case("new characters beyond previous maximum reset timer", "The train is", "The train is coming", 10,
                LoopOcrTextRelation.GROWING, true),
            Case("unrelated text resets timer", "The train is coming", "Welcome to the forest", 19,
                LoopOcrTextRelation.CHANGED, true),
            Case("empty to text is a real change", "", "Hello", 0,
                LoopOcrTextRelation.CHANGED, true),
        ).forEach { case ->
            val result = LoopOcrTextStabilityPolicy.observe(
                previousText = case.previous,
                currentText = case.current,
                maxObservedLength = case.maxObservedLength,
            )
            assertEquals(case.name, case.expectedRelation, result.relation)
            assertEquals(case.name, case.expectedReset, result.shouldResetStableTimer)
            assertTrue(case.name, result.maxObservedLength >= case.current.filterNot(Char::isWhitespace).length)
        }
    }

    @Test
    fun processedTextComparison_skipsOcrNoiseButNotGrowingOrChangedDialogue() {
        data class Case(
            val name: String,
            val processed: String,
            val current: String,
            val expectedRelation: LoopOcrTextRelation,
            val expectedSame: Boolean,
        )

        val processed = "A Dark Forest\n???\nMan with Red Eyes\n0\n" +
            "You're in for one hell of a painful ride from here on out, Dante.\nSKIP D>"
        listOf(
            Case(
                name = "extra one-character OCR block is duplicate noise",
                processed = processed,
                current = processed.replace("\n0\n", "\n이\n0\n"),
                expectedRelation = LoopOcrTextRelation.OCR_JITTER,
                expectedSame = true,
            ),
            Case(
                name = "continued subtitle output is not a duplicate",
                processed = "The train is",
                current = "The train is coming",
                expectedRelation = LoopOcrTextRelation.GROWING,
                expectedSame = false,
            ),
            Case(
                name = "next dialogue is not a duplicate",
                processed = "You will suffer from here on out, Dante.",
                current = "Where are we going now, Vergilius?",
                expectedRelation = LoopOcrTextRelation.CHANGED,
                expectedSame = false,
            ),
        ).forEach { case ->
            val result = LoopOcrTextStabilityPolicy.compareToProcessedText(
                processedText = case.processed,
                currentText = case.current,
            )
            assertEquals(case.name, case.expectedRelation, result.relation)
            assertEquals(
                case.name,
                case.expectedSame,
                LoopOcrTextStabilityPolicy.isLikelySameProcessedText(result),
            )
        }
    }
}
