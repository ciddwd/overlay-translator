package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaMixedScriptCorrectionPolicyTest {
    @Test
    fun `mixed script decisions cover correction agreement and safe fallback table`() {
        data class Case(
            val name: String,
            val manga: String,
            val paddle: List<MangaMixedScriptPaddleLine>,
            val expectedOutcome: MangaMixedScriptOutcome,
            val expectedText: String?,
            val expectedReason: String,
        )

        val cases = listOf(
            Case(
                name = "observed hallucinated Latin word is repaired",
                manga = "次は~armosprer.",
                paddle = listOf(
                    line("atmosphere-", 0.9846f),
                    line("次は〜", 0.7020f),
                ),
                expectedOutcome = MangaMixedScriptOutcome.CORRECTED_WITH_PADDLE,
                expectedText = "次は~atmosphere.",
                expectedReason = "high_confidence_latin_repair",
            ),
            Case(
                name = "observed trailing short Latin residue is absorbed but exclamation remains",
                manga = "次は~armosprer.e!",
                paddle = listOf(line("atmosphere-", 0.9846f)),
                expectedOutcome = MangaMixedScriptOutcome.CORRECTED_WITH_PADDLE,
                expectedText = "次は~atmosphere!",
                expectedReason = "high_confidence_latin_repair",
            ),
            Case(
                name = "terminal period without a following short fragment remains",
                manga = "次は~armosprer.",
                paddle = listOf(line("atmosphere", 0.99f)),
                expectedOutcome = MangaMixedScriptOutcome.CORRECTED_WITH_PADDLE,
                expectedText = "次は~atmosphere.",
                expectedReason = "high_confidence_latin_repair",
            ),
            Case(
                name = "matching version suffix from Paddle is retained during anchor repair",
                manga = "versiom.x!",
                paddle = listOf(line("version.x", 0.99f)),
                expectedOutcome = MangaMixedScriptOutcome.CORRECTED_WITH_PADDLE,
                expectedText = "version.x!",
                expectedReason = "high_confidence_latin_repair",
            ),
            Case(
                name = "cross-model anchor agreement never removes a short suffix",
                manga = "version.x",
                paddle = listOf(line("version", 0.99f)),
                expectedOutcome = MangaMixedScriptOutcome.KEEP_MANGA,
                expectedText = "version.x",
                expectedReason = "cross_model_agreement",
            ),
            Case(
                name = "repeated internal connectors and short fragments stay in one envelope",
                manga = "armosprer.e_x?",
                paddle = listOf(line("atmosphere", 0.99f)),
                expectedOutcome = MangaMixedScriptOutcome.CORRECTED_WITH_PADDLE,
                expectedText = "atmosphere?",
                expectedReason = "high_confidence_latin_repair",
            ),
            Case(
                name = "whitespace stops the short-fragment envelope",
                manga = "armosprer e!",
                paddle = listOf(line("atmosphere", 0.99f)),
                expectedOutcome = MangaMixedScriptOutcome.CORRECTED_WITH_PADDLE,
                expectedText = "atmosphere e!",
                expectedReason = "high_confidence_latin_repair",
            ),
            Case(
                name = "Japanese boundary stops the short-fragment envelope",
                manga = "armosprerのA",
                paddle = listOf(line("atmosphere", 0.99f)),
                expectedOutcome = MangaMixedScriptOutcome.CORRECTED_WITH_PADDLE,
                expectedText = "atmosphereのA",
                expectedReason = "high_confidence_latin_repair",
            ),
            Case(
                name = "pure Japanese avoids Paddle comparison",
                manga = "本当にただ授業の練習をしてるだけ!?",
                paddle = emptyList(),
                expectedOutcome = MangaMixedScriptOutcome.KEEP_MANGA,
                expectedText = "本当にただ授業の練習をしてるだけ!?",
                expectedReason = "no_latin_run",
            ),
            Case(
                name = "short Latin label is left alone",
                manga = "A級",
                paddle = emptyList(),
                expectedOutcome = MangaMixedScriptOutcome.KEEP_MANGA,
                expectedText = "A級",
                expectedReason = "no_latin_run",
            ),
            Case(
                name = "cross-model agreement preserves Manga punctuation",
                manga = "次は~Atmosphere.",
                paddle = listOf(line("atmosphere-", 0.98f)),
                expectedOutcome = MangaMixedScriptOutcome.KEEP_MANGA,
                expectedText = "次は~Atmosphere.",
                expectedReason = "cross_model_agreement",
            ),
            Case(
                name = "low Paddle confidence keeps the original image",
                manga = "次は~armosprer.",
                paddle = listOf(line("atmosphere-", 0.89f)),
                expectedOutcome = MangaMixedScriptOutcome.PRESERVE_ORIGINAL_IMAGE,
                expectedText = null,
                expectedReason = "missing_reliable_paddle_run",
            ),
            Case(
                name = "unavailable Paddle confidence keeps the original image",
                manga = "次は~armosprer.",
                paddle = listOf(line("atmosphere-", Float.NaN)),
                expectedOutcome = MangaMixedScriptOutcome.PRESERVE_ORIGINAL_IMAGE,
                expectedText = null,
                expectedReason = "missing_reliable_paddle_run",
            ),
            Case(
                name = "Japanese furigana lines do not enter replacement",
                manga = "ABC実際の授業",
                paddle = listOf(
                    line("ABC", 0.99f),
                    line("じっさい", 0.99f),
                    line("じゅぎょう", 0.99f),
                ),
                expectedOutcome = MangaMixedScriptOutcome.KEEP_MANGA,
                expectedText = "ABC実際の授業",
                expectedReason = "cross_model_agreement",
            ),
            Case(
                name = "multiple Latin runs map independently of Paddle line order",
                manga = "HELLOとwurld",
                paddle = listOf(
                    line("world", 0.99f),
                    line("HELLO", 0.98f),
                ),
                expectedOutcome = MangaMixedScriptOutcome.CORRECTED_WITH_PADDLE,
                expectedText = "HELLOとworld",
                expectedReason = "high_confidence_latin_repair",
            ),
            Case(
                name = "unmatched candidate length keeps the original image",
                manga = "atmosphere",
                paddle = listOf(line("OCR", 0.99f)),
                expectedOutcome = MangaMixedScriptOutcome.PRESERVE_ORIGINAL_IMAGE,
                expectedText = null,
                expectedReason = "unmatched_latin_run",
            ),
            Case(
                name = "equally plausible different Paddle candidates preserve the original image",
                manga = "cat",
                paddle = listOf(
                    line("bat", 0.99f),
                    line("cut", 0.99f),
                ),
                expectedOutcome = MangaMixedScriptOutcome.PRESERVE_ORIGINAL_IMAGE,
                expectedText = null,
                expectedReason = "ambiguous_paddle_run",
            ),
            Case(
                name = "full width Latin is normalized before agreement check",
                manga = "ＡＢＣです",
                paddle = listOf(line("abc", 0.99f)),
                expectedOutcome = MangaMixedScriptOutcome.KEEP_MANGA,
                expectedText = "ＡＢＣです",
                expectedReason = "cross_model_agreement",
            ),
        )

        cases.forEach { case ->
            val decision = MangaMixedScriptCorrectionPolicy.decide(case.manga, case.paddle)
            assertEquals(case.name, case.expectedOutcome, decision.outcome)
            assertEquals(case.name, case.expectedText, decision.outputText)
            assertEquals(case.name, case.expectedReason, decision.reason)
        }
    }

    @Test
    fun `comparison trigger follows the same Latin run boundary table`() {
        data class Case(val text: String, val expected: Boolean)
        listOf(
            Case("日本語だけ", false),
            Case("A級", false),
            Case("ABテスト", false),
            Case("ABCテスト", true),
            Case("次は~armosprer.", true),
            Case("１２３", false),
        ).forEach { case ->
            assertEquals(
                case.text,
                case.expected,
                MangaMixedScriptCorrectionPolicy.requiresPaddleComparison(case.text),
            )
        }
    }

    private fun line(text: String, confidence: Float) =
        MangaMixedScriptPaddleLine(text, confidence)
}
