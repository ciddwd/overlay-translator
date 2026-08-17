package com.gameocr.app.overlay

import java.text.BreakIterator
import java.util.Locale

internal data class FloatingEnglishWordHit(
    val word: String,
    val start: Int,
    val end: Int,
)

internal data class FloatingWordPreviewContent(
    val word: String,
    val translation: String,
    val partOfSpeech: String,
)

/** Resolves only deliberate taps on Latin letters; nearby whitespace never selects a word. */
internal fun floatingEnglishWordAt(
    text: CharSequence,
    offset: Int,
): FloatingEnglishWordHit? {
    val source = text.toString()
    if (source.isEmpty() || offset !in source.indices || !source[offset].isAsciiLatinLetter()) {
        return null
    }

    val iterator = BreakIterator.getWordInstance(Locale.ENGLISH).apply { setText(source) }
    var start = iterator.preceding(offset + 1).takeUnless { it == BreakIterator.DONE } ?: return null
    var end = iterator.following(offset).takeUnless { it == BreakIterator.DONE } ?: return null

    // ICU intentionally treats a hyphen as its own boundary. Join adjacent Latin components so
    // tapping state-of-the-art opens the complete dictionary term instead of only one component.
    while (start >= 2 && source[start - 1].isEnglishWordJoiner() && source[start - 2].isAsciiLatinLetter()) {
        start = iterator.preceding(start - 1).takeUnless { it == BreakIterator.DONE } ?: break
    }
    while (
        end + 1 < source.length &&
        source[end].isEnglishWordJoiner() &&
        source[end + 1].isAsciiLatinLetter()
    ) {
        end = iterator.following(end + 1).takeUnless { it == BreakIterator.DONE } ?: break
    }

    if (start !in 0 until end || end > source.length) return null
    val candidate = source.substring(start, end)
    return candidate.takeIf(ENGLISH_WORD_PATTERN::matches)?.let {
        FloatingEnglishWordHit(word = it, start = start, end = end)
    }
}

internal fun floatingWordPreviewContent(
    word: String,
    translation: String?,
    partsOfSpeech: List<String>,
    loading: Boolean,
    failed: Boolean,
    loadingLabel: String,
    failedLabel: String,
): FloatingWordPreviewContent = FloatingWordPreviewContent(
    word = word,
    translation = when {
        loading -> loadingLabel
        !translation.isNullOrBlank() -> translation.trim()
        failed -> failedLabel
        else -> ""
    },
    partOfSpeech = partsOfSpeech
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(" / "),
)

private fun Char.isAsciiLatinLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

private fun Char.isEnglishWordJoiner(): Boolean =
    this == '\'' || this == '\u2019' || this == '-' || this == '\u2010' || this == '\u2011'

private val ENGLISH_WORD_PATTERN =
    Regex("[A-Za-z]+(?:['\u2019][A-Za-z]+)*(?:[-\u2010\u2011][A-Za-z]+(?:['\u2019][A-Za-z]+)*)*")
