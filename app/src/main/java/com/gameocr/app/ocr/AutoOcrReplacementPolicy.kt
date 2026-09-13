package com.gameocr.app.ocr

/** Keep unrelated languages and uncovered short text when a language-specific OCR refines a page. */
internal fun <T> replaceAutoOcrRegions(
    original: List<T>,
    refined: List<T>,
    language: String,
    languageOf: (T) -> String?,
    overlaps: (T, T) -> Boolean,
): List<T> {
    val targets = original.filter { languageOf(it) == language }
    val otherLanguages = original.filter { languageOf(it) != null && languageOf(it) != language }
    val accepted = refined.filter { candidate ->
        (targets.any { overlaps(candidate, it) } || languageOf(candidate) == language) &&
            otherLanguages.none { overlaps(candidate, it) }
    }
    if (accepted.isEmpty()) return original
    return original.filterNot { old -> accepted.any { overlaps(it, old) } } + accepted
}
