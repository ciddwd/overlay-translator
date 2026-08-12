package com.gameocr.app.translate

internal enum class SakuraOutputRejectionReason {
    TOKEN_LIMIT,
    LINE_COUNT_MISMATCH,
    EMPTY,
    MULTILINE,
    TOO_LONG,
    PROMPT_ECHO,
    SOURCE_COPY,
    JAPANESE_RESIDUE,
    DEGENERATE_REPETITION,
}

internal enum class SakuraOutputDisposition {
    ACCEPTED,
    RETRYABLE,
    REJECTED,
}

internal data class SakuraLineValidation(
    val text: String?,
    val rejectionReason: SakuraOutputRejectionReason? = null,
    val disposition: SakuraOutputDisposition,
) {
    val accepted: Boolean get() = disposition == SakuraOutputDisposition.ACCEPTED
    val retryable: Boolean get() = disposition == SakuraOutputDisposition.RETRYABLE
}

internal data class SakuraGroupValidation(
    val lines: List<SakuraLineValidation>?,
    val rejectionReason: SakuraOutputRejectionReason? = null,
) {
    val accepted: Boolean get() = lines != null && lines.all(SakuraLineValidation::accepted)
}

internal object SakuraOutputPolicy {

    fun validateGroup(
        sources: List<String>,
        output: String?,
        hitTokenLimit: Boolean,
        forbiddenEchoes: List<String>,
    ): SakuraGroupValidation {
        if (hitTokenLimit) {
            return SakuraGroupValidation(lines = null, rejectionReason = SakuraOutputRejectionReason.TOKEN_LIMIT)
        }
        val normalizedOutput = output
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            .orEmpty()
        if (normalizedOutput.isBlank()) {
            return SakuraGroupValidation(lines = null, rejectionReason = SakuraOutputRejectionReason.EMPTY)
        }
        val translated = normalizedOutput.split('\n').map(String::trim)
        if (translated.size != sources.size) {
            return SakuraGroupValidation(
                lines = null,
                rejectionReason = SakuraOutputRejectionReason.LINE_COUNT_MISMATCH,
            )
        }
        return SakuraGroupValidation(
            lines = translated.mapIndexed { index, text ->
                validateLineDetailed(
                    source = sources[index],
                    output = text,
                    forbiddenEchoes = forbiddenEchoes,
                )
            }
        )
    }

    fun validateLine(
        source: String,
        output: String?,
        forbiddenEchoes: List<String>,
    ): Boolean = validateLineDetailed(source, output, forbiddenEchoes).accepted

    fun validateLineDetailed(
        source: String,
        output: String?,
        forbiddenEchoes: List<String>,
    ): SakuraLineValidation {
        val text = output?.trim().orEmpty()
        if (source.isBlank() || text.isBlank()) return rejected(SakuraOutputRejectionReason.EMPTY)
        if (hasLineBreak(text) && !hasLineBreak(source)) {
            return rejected(SakuraOutputRejectionReason.MULTILINE)
        }
        if (looksLikePromptEcho(text, forbiddenEchoes)) {
            return rejected(SakuraOutputRejectionReason.PROMPT_ECHO)
        }
        val sourceProfile = scriptProfile(source)
        val outputProfile = scriptProfile(text)
        val similarity = sourceSimilarity(source, text)
        val sourceCopy = sourceProfile.kanaCount > 0 && looksLikeSourceCopy(source, text, similarity)
        if (sourceCopy) {
            return retryable(text, SakuraOutputRejectionReason.SOURCE_COPY)
        }
        if (
            outputProfile.kanaCount >= MINIMUM_KANA_COUNT &&
            outputProfile.kanaCount * KANA_RATIO_DENOMINATOR >= outputProfile.meaningfulCount &&
            similarity >= MINIMUM_JAPANESE_RESIDUE_SIMILARITY
        ) {
            return retryable(text, SakuraOutputRejectionReason.JAPANESE_RESIDUE)
        }
        val sourceRepetition = repetitionProfile(source)
        val outputRepetition = repetitionProfile(text)
        if (looksLikeDegenerateRepetition(sourceRepetition, outputRepetition)) {
            return rejected(SakuraOutputRejectionReason.DEGENERATE_REPETITION)
        }
        val maximumUnits = maxOf(
            MINIMUM_EMERGENCY_OUTPUT_UNITS,
            sourceRepetition.unitCount * MAXIMUM_EMERGENCY_EXPANSION,
        )
        if (outputRepetition.unitCount > maximumUnits) {
            return rejected(SakuraOutputRejectionReason.TOO_LONG)
        }
        return SakuraLineValidation(
            text = text,
            disposition = SakuraOutputDisposition.ACCEPTED,
        )
    }

    private fun rejected(reason: SakuraOutputRejectionReason): SakuraLineValidation =
        SakuraLineValidation(
            text = null,
            rejectionReason = reason,
            disposition = SakuraOutputDisposition.REJECTED,
        )

    private fun retryable(text: String, reason: SakuraOutputRejectionReason): SakuraLineValidation =
        SakuraLineValidation(
            text = text,
            rejectionReason = reason,
            disposition = SakuraOutputDisposition.RETRYABLE,
        )

    private fun hasLineBreak(text: String): Boolean =
        text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0

    private fun looksLikePromptEcho(text: String, forbiddenEchoes: List<String>): Boolean {
        val normalizedText = significantCodePoints(text)
        if (normalizedText.size < MINIMUM_ECHO_CHARS) return false
        return forbiddenEchoes.asSequence().any { rawEcho ->
            val echo = rawEcho.trim()
            if (echo.length >= MINIMUM_ECHO_CHARS && text.contains(echo)) return@any true
            val normalizedEcho = significantCodePoints(echo)
            if (normalizedEcho.size !in MINIMUM_ECHO_CHARS..MAXIMUM_FUZZY_ECHO_CHARS) {
                return@any false
            }
            val common = longestCommonSubsequenceLength(normalizedText, normalizedEcho)
            common >= MINIMUM_FUZZY_ECHO_CHARS &&
                common.toDouble() / normalizedEcho.size >= MINIMUM_ECHO_TEMPLATE_COVERAGE
        }
    }

    private fun looksLikeSourceCopy(source: String, output: String, similarity: Double): Boolean {
        val normalizedSource = significantCodePoints(source)
        val normalizedOutput = significantCodePoints(output)
        if (normalizedSource.isEmpty() || normalizedOutput.isEmpty()) return false
        if (normalizedSource.contentEquals(normalizedOutput)) return true
        if (minOf(normalizedSource.size, normalizedOutput.size) < MINIMUM_SOURCE_COPY_CHARS) return false
        return similarity >= MINIMUM_SOURCE_COPY_SIMILARITY
    }

    private fun sourceSimilarity(source: String, output: String): Double {
        val normalizedSource = significantCodePoints(source)
        val normalizedOutput = significantCodePoints(output)
        if (normalizedSource.isEmpty() || normalizedOutput.isEmpty()) return 0.0
        val common = longestCommonSubsequenceLength(normalizedSource, normalizedOutput)
        return (2.0 * common) / (normalizedSource.size + normalizedOutput.size)
    }

    private fun looksLikeDegenerateRepetition(
        source: RepetitionProfile,
        output: RepetitionProfile,
    ): Boolean {
        val expansionThreshold = maxOf(
            MINIMUM_DEGENERATE_OUTPUT_UNITS,
            source.unitCount * MINIMUM_DEGENERATE_EXPANSION,
        )
        if (output.unitCount < expansionThreshold) return false
        if (output.repetitionScore < MINIMUM_DEGENERATE_COVERAGE) return false
        if (output.repetitionScore - source.repetitionScore < MINIMUM_REPETITION_SCORE_INCREASE) {
            return false
        }
        return output.longestRun >= MINIMUM_DEGENERATE_RUN ||
            output.periodicCoverage >= MINIMUM_DEGENERATE_COVERAGE
    }

    private fun repetitionProfile(text: String): RepetitionProfile {
        val units = nonWhitespaceCodePoints(text)
        if (units.isEmpty()) return RepetitionProfile.EMPTY
        val frequencies = HashMap<Int, Int>()
        var longestRun = 1
        var currentRun = 1
        units.forEachIndexed { index, codePoint ->
            frequencies[codePoint] = (frequencies[codePoint] ?: 0) + 1
            if (index > 0 && units[index - 1] == codePoint) {
                currentRun += 1
                longestRun = maxOf(longestRun, currentRun)
            } else {
                currentRun = 1
            }
        }
        val dominantCoverage = frequencies.values.maxOrNull()!!.toDouble() / units.size
        val periodicCoverage = repeatedPatternCoverage(units)
        return RepetitionProfile(
            unitCount = units.size,
            longestRun = longestRun,
            periodicCoverage = periodicCoverage,
            repetitionScore = maxOf(dominantCoverage, periodicCoverage),
        )
    }

    private fun repeatedPatternCoverage(units: IntArray): Double {
        if (units.size < MINIMUM_PATTERN_REPEATS) return 0.0
        var bestRepeatedUnits = 0
        val maximumPatternLength = minOf(MAXIMUM_PATTERN_UNITS, units.size / MINIMUM_PATTERN_REPEATS)
        for (patternLength in 1..maximumPatternLength) {
            val lastStart = units.size - patternLength * MINIMUM_PATTERN_REPEATS
            for (start in 0..lastStart) {
                var repeats = 1
                while (
                    start + (repeats + 1) * patternLength <= units.size &&
                    regionsEqual(units, start, start + repeats * patternLength, patternLength)
                ) {
                    repeats += 1
                }
                if (repeats >= MINIMUM_PATTERN_REPEATS) {
                    bestRepeatedUnits = maxOf(bestRepeatedUnits, repeats * patternLength)
                }
            }
        }
        return bestRepeatedUnits.toDouble() / units.size
    }

    private fun regionsEqual(units: IntArray, left: Int, right: Int, length: Int): Boolean {
        for (offset in 0 until length) {
            if (units[left + offset] != units[right + offset]) return false
        }
        return true
    }

    private fun nonWhitespaceCodePoints(text: String): IntArray {
        val points = ArrayList<Int>(text.length)
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (!Character.isWhitespace(codePoint)) points += codePoint
            offset += Character.charCount(codePoint)
        }
        return points.toIntArray()
    }

    private fun scriptProfile(text: String): ScriptProfile {
        var meaningful = 0
        var kana = 0
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (Character.isLetterOrDigit(codePoint)) meaningful += 1
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA -> kana += 1
                else -> Unit
            }
            offset += Character.charCount(codePoint)
        }
        return ScriptProfile(meaningfulCount = meaningful.coerceAtLeast(1), kanaCount = kana)
    }

    private fun significantCodePoints(text: String): IntArray {
        val points = ArrayList<Int>(text.length)
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (Character.isLetterOrDigit(codePoint)) {
                points += Character.toLowerCase(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
        return points.toIntArray()
    }

    private fun longestCommonSubsequenceLength(left: IntArray, right: IntArray): Int {
        val previous = IntArray(right.size + 1)
        val current = IntArray(right.size + 1)
        left.forEach { leftPoint ->
            for (rightIndex in right.indices) {
                current[rightIndex + 1] = if (leftPoint == right[rightIndex]) {
                    previous[rightIndex] + 1
                } else {
                    maxOf(previous[rightIndex + 1], current[rightIndex])
                }
            }
            current.copyInto(previous)
            current.fill(0)
        }
        return previous[right.size]
    }

    private data class ScriptProfile(
        val meaningfulCount: Int,
        val kanaCount: Int,
    )

    private data class RepetitionProfile(
        val unitCount: Int,
        val longestRun: Int,
        val periodicCoverage: Double,
        val repetitionScore: Double,
    ) {
        companion object {
            val EMPTY = RepetitionProfile(0, 0, 0.0, 0.0)
        }
    }

    private const val MINIMUM_ECHO_CHARS = 8
    private const val MINIMUM_FUZZY_ECHO_CHARS = 8
    private const val MAXIMUM_FUZZY_ECHO_CHARS = 64
    private const val MINIMUM_ECHO_TEMPLATE_COVERAGE = 0.68
    private const val MINIMUM_SOURCE_COPY_CHARS = 4
    private const val MINIMUM_SOURCE_COPY_SIMILARITY = 0.82
    private const val MINIMUM_JAPANESE_RESIDUE_SIMILARITY = 0.78
    private const val MINIMUM_KANA_COUNT = 2
    private const val KANA_RATIO_DENOMINATOR = 5
    private const val MINIMUM_DEGENERATE_OUTPUT_UNITS = 12
    private const val MINIMUM_DEGENERATE_EXPANSION = 3
    private const val MINIMUM_DEGENERATE_COVERAGE = 0.70
    private const val MINIMUM_REPETITION_SCORE_INCREASE = 0.35
    private const val MINIMUM_DEGENERATE_RUN = 6
    private const val MINIMUM_PATTERN_REPEATS = 3
    private const val MAXIMUM_PATTERN_UNITS = 4
    private const val MINIMUM_EMERGENCY_OUTPUT_UNITS = 32
    private const val MAXIMUM_EMERGENCY_EXPANSION = 6
}
