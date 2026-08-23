package com.gameocr.app.overlay

import java.text.BreakIterator
import java.util.Locale
import com.gameocr.app.translate.WordResult

internal data class FloatingEnglishWordHit(
    val word: String,
    val start: Int,
    val end: Int,
)

internal data class FloatingWordPreviewContent(
    val word: String,
    val lines: List<String>,
)

internal data class FloatingWordDetailsContent(
    val translation: String?,
    val wordResult: WordResult?,
    val loading: Boolean,
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
    wordResult: WordResult?,
    loading: Boolean,
    failed: Boolean,
    loadingLabel: String,
    failedLabel: String,
): FloatingWordPreviewContent {
    val lines = when {
        loading -> listOf(loadingLabel)
        wordResult != null -> wordResult.compactSenseLines().ifEmpty {
            listOfNotNull(translation?.trim()?.takeIf(String::isNotEmpty))
        }
        !translation.isNullOrBlank() -> listOf(translation.trim())
        failed -> listOf(failedLabel)
        else -> emptyList()
    }
    return FloatingWordPreviewContent(word = word, lines = lines)
}

internal fun WordResult.compactSenseLines(): List<String> {
    val grouped = effectiveSenses().map { sense ->
        val meanings = sense.definitions.toMutableList()
        if (sense.formNote.isNotBlank() && meanings.isNotEmpty()) {
            meanings[0] = "${meanings[0]}（${sense.formNote}）"
        }
        listOf(sense.partOfSpeech, meanings.joinToString("；"))
            .filter(String::isNotBlank)
            .joinToString(" ")
    }.filter(String::isNotBlank)
    if (grouped.isNotEmpty()) return grouped

    val meanings = effectiveDefinitions().joinToString("；").takeIf(String::isNotBlank)
    val parts = effectivePartsOfSpeech().joinToString(" / ").takeIf(String::isNotBlank)
    return buildList {
        meanings?.let(::add)
        parts?.let { add("词性：$it") }
        fallbackTranslation?.trim()?.takeIf(String::isNotBlank)?.let { fallback ->
            if (fallback !in this) add(fallback)
        }
    }
}

internal fun shouldShowFloatingWordDetailsAction(
    loading: Boolean,
    hasDetails: Boolean,
): Boolean = !loading && hasDetails

/**
 * The full dictionary card never carries compact preview content across states. While the fresh
 * full lookup is running it shows only its loading state; completion replaces that state with
 * either the full structured result or an explicit failure message.
 */
internal fun floatingWordDetailsContent(
    completed: Boolean,
    wordResult: WordResult?,
    failedLabel: String,
): FloatingWordDetailsContent = when {
    !completed -> FloatingWordDetailsContent(
        translation = null,
        wordResult = null,
        loading = true,
    )
    wordResult != null && !wordResult.isEmpty() -> FloatingWordDetailsContent(
        translation = null,
        wordResult = wordResult,
        loading = false,
    )
    else -> FloatingWordDetailsContent(
        translation = failedLabel,
        wordResult = null,
        loading = false,
    )
}

private fun Char.isAsciiLatinLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

private fun Char.isEnglishWordJoiner(): Boolean =
    this == '\'' || this == '\u2019' || this == '-' || this == '\u2010' || this == '\u2011'

private val ENGLISH_WORD_PATTERN =
    Regex("[A-Za-z]+(?:['\u2019][A-Za-z]+)*(?:[-\u2010\u2011][A-Za-z]+(?:['\u2019][A-Za-z]+)*)*")
