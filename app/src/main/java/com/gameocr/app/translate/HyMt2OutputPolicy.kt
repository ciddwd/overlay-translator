package com.gameocr.app.translate

import com.gameocr.app.llm.LlamaMultiSequence
import java.text.Normalizer

/** Rejects Hy-MT2 prompt/context leakage before it can be displayed or cached. */
internal object HyMt2OutputPolicy {

    enum class Reason {
        MULTI_SECTION_ECHO,
        BACKGROUND_HEADER_ECHO,
        SOURCE_HEADER_ECHO,
        TRANSLATION_INSTRUCTION_ECHO,
        MULTI_ITEM_OUTPUT,
        UNEXPECTED_ITEM_PREFIX,
        PUNCTUATION_SOURCE_EXPANSION,
        SHORT_SOURCE_CONTEXT_EXPANSION,
        NATIVE_LINE_LIMIT,
    }

    sealed interface Decision {
        data object Accept : Decision
        data class Reject(val reason: Reason) : Decision
    }

    /**
     * Removes a harmless list marker that Hy-MT2 occasionally copies from contextual background.
     * Only a single-line result is eligible, and the stripped result must still pass every output
     * safety check. Multi-line output and list-like source text are intentionally left untouched.
     */
    fun normalizeHarmlessSingleItemPrefix(
        source: String,
        output: String,
        requestHadBackground: Boolean,
    ): String {
        if (!requestHadBackground) return output
        val sourceLines = unicodeNormalize(source).nonEmptyLines()
        if (sourceLines.any(::hasItemPrefix)) return output

        val outputLines = output.nonEmptyLines()
        if (outputLines.size != 1) return output
        val stripped = singleItemPrefix.matchEntire(outputLines.single())
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return output
        return if (inspect(source, stripped, requestHadBackground) == Decision.Accept) {
            stripped
        } else {
            output
        }
    }

    fun inspect(
        source: String,
        output: String,
        requestHadBackground: Boolean,
    ): Decision {
        if (output.isBlank()) return Decision.Accept
        if (
            output.contains(LlamaMultiSequence.LINE_LIMIT_SENTINEL) &&
            !source.contains(LlamaMultiSequence.LINE_LIMIT_SENTINEL)
        ) {
            return Decision.Reject(Reason.NATIVE_LINE_LIMIT)
        }

        val normalizedSource = unicodeNormalize(source)
        val normalizedOutput = unicodeNormalize(output)
        val outputHasBackgroundHeader = backgroundHeader.containsMatchIn(normalizedOutput)
        val outputHasSourceHeader = sourceHeader.containsMatchIn(normalizedOutput)
        val sourceHasBackgroundHeader = backgroundHeader.containsMatchIn(normalizedSource)
        val sourceHasSourceHeader = sourceHeader.containsMatchIn(normalizedSource)
        val unexpectedBackgroundHeader = outputHasBackgroundHeader && !sourceHasBackgroundHeader
        val unexpectedSourceHeader = outputHasSourceHeader && !sourceHasSourceHeader

        if (
            requestHadBackground &&
            outputHasBackgroundHeader &&
            outputHasSourceHeader &&
            (unexpectedBackgroundHeader || unexpectedSourceHeader)
        ) {
            return Decision.Reject(Reason.MULTI_SECTION_ECHO)
        }
        if (unexpectedBackgroundHeader) {
            return Decision.Reject(Reason.BACKGROUND_HEADER_ECHO)
        }
        if (unexpectedSourceHeader) {
            return Decision.Reject(Reason.SOURCE_HEADER_ECHO)
        }
        if (
            translationInstruction.containsMatchIn(normalizedOutput) &&
            !translationInstruction.containsMatchIn(normalizedSource)
        ) {
            return Decision.Reject(Reason.TRANSLATION_INSTRUCTION_ECHO)
        }
        if (requestHadBackground) {
            inspectContextShape(normalizedSource, normalizedOutput)?.let { return it }
        }
        return Decision.Accept
    }

    private fun inspectContextShape(source: String, output: String): Decision.Reject? {
        val sourceLines = source.nonEmptyLines()
        val outputLines = output.nonEmptyLines()
        val sourceItemPrefixes = sourceLines.count(::hasItemPrefix)
        val outputItemPrefixes = outputLines.count(::hasItemPrefix)

        if (
            outputItemPrefixes >= 2 &&
            outputItemPrefixes > sourceItemPrefixes
        ) {
            return Decision.Reject(Reason.MULTI_ITEM_OUTPUT)
        }
        if (
            sourceLines.size == 1 &&
            outputLines.size >= 3 &&
            outputLines.size > sourceLines.size * 2
        ) {
            return Decision.Reject(Reason.MULTI_ITEM_OUTPUT)
        }
        if (
            outputItemPrefixes == 1 &&
            sourceItemPrefixes == 0 &&
            outputLines.firstOrNull()?.let(::hasItemPrefix) == true
        ) {
            return Decision.Reject(Reason.UNEXPECTED_ITEM_PREFIX)
        }
        if (
            source.isPunctuationOnly() &&
            output.any(Char::isLetterOrDigit)
        ) {
            return Decision.Reject(Reason.PUNCTUATION_SOURCE_EXPANSION)
        }

        val sourceCodePoints = source.codePointLength()
        val outputCodePoints = output.codePointLength()
        if (
            sourceCodePoints in 1..2 &&
            outputCodePoints > sourceCodePoints * SHORT_SOURCE_EXPANSION_MULTIPLIER +
                SHORT_SOURCE_EXPANSION_ALLOWANCE
        ) {
            return Decision.Reject(Reason.SHORT_SOURCE_CONTEXT_EXPANSION)
        }
        return null
    }

    private fun unicodeNormalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)

    private fun String.nonEmptyLines(): List<String> =
        lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

    private fun hasItemPrefix(line: String): Boolean = itemPrefix.containsMatchIn(line)

    private fun String.isPunctuationOnly(): Boolean =
        isNotBlank() && none(Char::isLetterOrDigit)

    private fun String.codePointLength(): Int = codePointCount(0, length)

    private const val SHORT_SOURCE_EXPANSION_MULTIPLIER = 8
    private const val SHORT_SOURCE_EXPANSION_ALLOWANCE = 8

    private val itemPrefix = Regex(
        pattern = "^\\s*(?:\\d{1,3}[.)、．:：]|[-*•])\\s+",
    )

    private val singleItemPrefix = Regex(
        pattern = "^\\s*(?:[0-9０-９]{1,3}[.)）、．:：]|[-*•])\\s+(.+?)\\s*$",
    )

    private val backgroundHeader = Regex(
        pattern = "(?:\\[\\s*background\\s+information\\s*]|[\\[【〖]\\s*(?:背景信息|背景资料|上下文信息)\\s*[\\]】〗])",
        option = RegexOption.IGNORE_CASE,
    )

    private val sourceHeader = Regex(
        pattern = "(?:\\[\\s*source\\s+text\\s*]|[\\[【〖]\\s*(?:待翻译文本|原文文本|源文本)\\s*[\\]】〗])",
        option = RegexOption.IGNORE_CASE,
    )

    private val translationInstruction = Regex(
        pattern = "(?:" +
            "please\\s+translate\\s+the\\s+following\\s+text\\s+into.{1,80}" +
            "taking\\s+the\\s+provided\\s+background\\s+information\\s+into\\s+consideration" +
            "|translate\\s+the\\s+following\\s+text\\s+into.{1,80}" +
            "note\\s+that\\s+you\\s+(?:should|must)\\s+only\\s+output\\s+the\\s+translated\\s+result" +
            "|请结合背景信息将以下文本翻译为.{1,30}" +
            "|将以下文本翻译(?:为|成).{1,30}[，,。.]?\\s*注意.{0,20}只(?:需要|需)输出翻译后的结果" +
            ")",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
}
