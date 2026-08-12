package com.gameocr.app.ocr

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaMixedScriptCorrectionWiringTest {
    @Test
    fun targetedCorrection_isReadOnlyOutsideSuspiciousLatinCrops_tableDriven() {
        val manga = source("app/src/main/java/com/gameocr/app/ocr/MangaOcrEngine.kt")
        val paddle = source("app/src/main/java/com/gameocr/app/ocr/PaddleOcrEngine.kt")
        val policy = source(
            "app/src/main/java/com/gameocr/app/ocr/MangaMixedScriptCorrectionPolicy.kt"
        )

        data class Case(val name: String, val content: String, val marker: String)
        listOf(
            Case(
                "Paddle runs only after the mixed-script trigger",
                manga,
                "MangaMixedScriptCorrectionPolicy.requiresPaddleComparison(mangaText)",
            ),
            Case(
                "only crop members enter targeted Paddle recognition",
                manga,
                "memberIndices = missingMembers",
            ),
            Case(
                "member recognition is cached across overlapping crops",
                manga,
                "val paddleMixedScriptCache = mutableMapOf<Int, PaddleMangaLineRecognition>()",
            ),
            Case(
                "unreliable mixed crop preserves the original image",
                manga,
                "decision.outputText ?: continue",
            ),
            Case(
                "visible chunks receive only the policy output",
                manga,
                "recognizedChunks[plan.sourceBubbleIndex] += text",
            ),
            Case(
                "Paddle reuses the already detected quad",
                paddle,
                "quads.getOrNull(memberIndex)",
            ),
            Case(
                "Paddle does line recognition instead of whole-bubble CTC",
                paddle,
                "internal fun recognizeMangaMembers(",
            ),
            Case(
                "whole-line Paddle replacement is forbidden by policy",
                policy,
                "Whole-line Paddle replacement is deliberately avoided",
            ),
            Case(
                "correction replaces only the matched Latin lexical envelope",
                policy,
                "replacement.mangaEnvelope.endExclusive",
            ),
        ).forEach { case -> assertTrue(case.name, case.content.contains(case.marker)) }

        assertFalse("full-page A/B inference was removed", manga.contains("abDiagnostics"))
        assertFalse("old diagnostic tag was removed", manga.contains("MangaPaddleAB"))
        assertFalse(
            "raw Paddle lines never directly enter visible chunks",
            manga.contains("recognizedChunks[plan.sourceBubbleIndex] += line.text"),
        )
    }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
