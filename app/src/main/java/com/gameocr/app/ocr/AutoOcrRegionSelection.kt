package com.gameocr.app.ocr

/** Compete only at overlapping locations; never discard a whole recognizer's page. */
internal fun <T> selectAutoOcrRegions(
    candidates: List<T>,
    evidenceOf: (T) -> AutoOcrEvidence,
    overlaps: (T, T) -> Boolean,
): List<T> {
    val ranked = candidates.filter { evidenceOf(it).text.any(Char::isLetterOrDigit) }
        .sortedByDescending { AutoOcrLanguagePolicy.score(listOf(evidenceOf(it))) }
    val selected = mutableListOf<T>()
    for (candidate in ranked) {
        if (selected.none { overlaps(candidate, it) }) selected += candidate
    }
    return selected
}
