package com.gameocr.app.ocr

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class MangaMixedScriptPaddleLine(
    val text: String,
    val confidence: Float,
)

internal enum class MangaMixedScriptOutcome {
    KEEP_MANGA,
    CORRECTED_WITH_PADDLE,
    PRESERVE_ORIGINAL_IMAGE,
}

internal data class MangaMixedScriptDecision(
    val outcome: MangaMixedScriptOutcome,
    val outputText: String?,
    val reason: String,
    val mangaRuns: List<String>,
    val paddleRuns: List<String>,
)

/**
 * Keeps manga-ocr as the Japanese recognizer while allowing a high-confidence Paddle line result
 * to repair only Latin words. Whole-line Paddle replacement is deliberately avoided because its
 * detected members may include furigana or small neighbouring glyphs that manga-ocr correctly
 * suppresses.
 */
internal object MangaMixedScriptCorrectionPolicy {
    private const val MIN_LATIN_LETTERS = 3
    private const val MIN_PADDLE_CONFIDENCE = 0.90f
    private const val MIN_LENGTH_RATIO = 0.50f

    fun requiresPaddleComparison(mangaText: String): Boolean =
        latinRuns(mangaText).isNotEmpty()

    fun decide(
        mangaText: String,
        paddleLines: List<MangaMixedScriptPaddleLine>,
    ): MangaMixedScriptDecision {
        val mangaSpans = latinRuns(mangaText)
        if (mangaSpans.isEmpty()) {
            return MangaMixedScriptDecision(
                outcome = MangaMixedScriptOutcome.KEEP_MANGA,
                outputText = mangaText,
                reason = "no_latin_run",
                mangaRuns = emptyList(),
                paddleRuns = emptyList(),
            )
        }

        val paddleCandidates = paddleLines.flatMap { line ->
            if (!line.confidence.isFinite() || line.confidence < MIN_PADDLE_CONFIDENCE) {
                emptyList()
            } else {
                latinRuns(line.text).map { span ->
                    PaddleRun(
                        anchorText = span.text,
                        replacementText = trailingLexicalEnvelope(line.text, span).text,
                        confidence = line.confidence,
                    )
                }
            }
        }.toMutableList()
        if (paddleCandidates.size < mangaSpans.size) {
            return preserveOriginal(
                reason = "missing_reliable_paddle_run",
                mangaSpans = mangaSpans,
                paddleCandidates = paddleCandidates,
            )
        }

        val replacements = mutableListOf<Replacement>()
        for (mangaSpan in mangaSpans) {
            val ranked = paddleCandidates
                .filter { candidate -> comparableLengths(mangaSpan.text, candidate.anchorText) }
                .map { candidate -> candidate to matchCost(mangaSpan.text, candidate.anchorText) }
                .sortedBy { (_, cost) -> cost }
            val best = ranked.firstOrNull()
                ?: return preserveOriginal(
                    reason = "unmatched_latin_run",
                    mangaSpans = mangaSpans,
                    paddleCandidates = paddleCandidates,
                )
            val equallyGoodDifferentCandidate = ranked.drop(1).firstOrNull { (candidate, cost) ->
                abs(cost - best.second) < 0.0001f &&
                    normalizeLatin(candidate.anchorText) != normalizeLatin(best.first.anchorText)
            }
            if (equallyGoodDifferentCandidate != null) {
                return preserveOriginal(
                    reason = "ambiguous_paddle_run",
                    mangaSpans = mangaSpans,
                    paddleCandidates = paddleCandidates,
                )
            }
            replacements += Replacement(
                mangaSpan = mangaSpan,
                mangaEnvelope = trailingLexicalEnvelope(mangaText, mangaSpan),
                paddleRun = best.first,
            )
            paddleCandidates.remove(best.first)
        }

        val changed = replacements.filterNot { replacement ->
            normalizeLatin(replacement.mangaSpan.text) ==
                normalizeLatin(replacement.paddleRun.anchorText)
        }
        if (changed.isEmpty()) {
            return MangaMixedScriptDecision(
                outcome = MangaMixedScriptOutcome.KEEP_MANGA,
                outputText = mangaText,
                reason = "cross_model_agreement",
                mangaRuns = mangaSpans.map(LatinSpan::text),
                paddleRuns = replacements.map { replacement ->
                    replacement.paddleRun.replacementText
                },
            )
        }

        val corrected = StringBuilder(mangaText).also { output ->
            changed.sortedByDescending { replacement -> replacement.mangaEnvelope.start }
                .forEach { replacement ->
                    output.replace(
                        replacement.mangaEnvelope.start,
                        replacement.mangaEnvelope.endExclusive,
                        replacement.paddleRun.replacementText,
                    )
                }
            }
            .toString()
        return MangaMixedScriptDecision(
            outcome = MangaMixedScriptOutcome.CORRECTED_WITH_PADDLE,
            outputText = corrected,
            reason = "high_confidence_latin_repair",
            mangaRuns = mangaSpans.map(LatinSpan::text),
            paddleRuns = replacements.map { replacement ->
                replacement.paddleRun.replacementText
            },
        )
    }

    private fun preserveOriginal(
        reason: String,
        mangaSpans: List<LatinSpan>,
        paddleCandidates: List<PaddleRun>,
    ): MangaMixedScriptDecision = MangaMixedScriptDecision(
        outcome = MangaMixedScriptOutcome.PRESERVE_ORIGINAL_IMAGE,
        outputText = null,
        reason = reason,
        mangaRuns = mangaSpans.map(LatinSpan::text),
        paddleRuns = paddleCandidates.map(PaddleRun::replacementText),
    )

    private fun comparableLengths(first: String, second: String): Boolean {
        val shorter = minOf(first.length, second.length).toFloat()
        val longer = maxOf(first.length, second.length).toFloat()
        return longer > 0f && shorter / longer >= MIN_LENGTH_RATIO
    }

    private fun matchCost(first: String, second: String): Float {
        val normalizedFirst = normalizeLatin(first)
        val normalizedSecond = normalizeLatin(second)
        val longest = maxOf(normalizedFirst.length, normalizedSecond.length).coerceAtLeast(1)
        val editCost = levenshteinDistance(normalizedFirst, normalizedSecond).toFloat() / longest
        val lengthCost = abs(normalizedFirst.length - normalizedSecond.length).toFloat() / longest
        return editCost + lengthCost * 0.25f
    }

    private fun normalizeLatin(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /**
     * Includes only connector + short-Latin fragments immediately following a long Latin anchor.
     * Sentence punctuation stays outside the envelope unless a one- or two-letter fragment follows
     * it. This lets a corrected anchor remove OCR residue such as `.e` while preserving the final
     * `!` in `armosprer.e!`. Cross-model anchor agreement never rewrites the envelope.
     */
    private fun trailingLexicalEnvelope(text: String, anchor: LatinSpan): LexicalEnvelope {
        var endExclusive = anchor.endExclusive
        while (endExclusive < text.length && isInternalLatinConnector(text[endExclusive])) {
            var cursor = endExclusive + 1
            var shortRunLetters = 0
            while (cursor < text.length && isLatinLetter(text[cursor])) {
                shortRunLetters++
                cursor++
            }
            if (shortRunLetters !in 1 until MIN_LATIN_LETTERS) break
            endExclusive = cursor
        }
        return LexicalEnvelope(
            start = anchor.start,
            endExclusive = endExclusive,
            text = text.substring(anchor.start, endExclusive),
        )
    }

    private fun isInternalLatinConnector(char: Char): Boolean = when (char) {
        '.', '．', '_', '-', '‐', '‑', '‒', '–', '—', '\'', '’' -> true
        else -> false
    }

    private fun isLatinLetter(char: Char): Boolean =
        Character.isLetter(char) &&
            Character.UnicodeScript.of(char.code) == Character.UnicodeScript.LATIN

    private fun latinRuns(text: String): List<LatinSpan> {
        val result = mutableListOf<LatinSpan>()
        var start = -1
        var letters = 0
        fun finish(endExclusive: Int) {
            if (start >= 0 && letters >= MIN_LATIN_LETTERS) {
                result += LatinSpan(start, endExclusive, text.substring(start, endExclusive))
            }
            start = -1
            letters = 0
        }

        text.forEachIndexed { index, char ->
            if (isLatinLetter(char)) {
                if (start < 0) start = index
                letters++
            } else {
                finish(index)
            }
        }
        finish(text.length)
        return result
    }

    private fun levenshteinDistance(first: String, second: String): Int {
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length
        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)
        for (firstIndex in first.indices) {
            current[0] = firstIndex + 1
            for (secondIndex in second.indices) {
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + if (first[firstIndex] == second[secondIndex]) 0 else 1,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }

    private data class LatinSpan(
        val start: Int,
        val endExclusive: Int,
        val text: String,
    )

    private data class LexicalEnvelope(
        val start: Int,
        val endExclusive: Int,
        val text: String,
    )

    private data class Replacement(
        val mangaSpan: LatinSpan,
        val mangaEnvelope: LexicalEnvelope,
        val paddleRun: PaddleRun,
    )

    private data class PaddleRun(
        val anchorText: String,
        val replacementText: String,
        val confidence: Float,
    )
}
