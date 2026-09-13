package com.gameocr.app.tts

import com.gameocr.app.ocr.OcrTextLanguageIdentifier
import javax.inject.Inject
import javax.inject.Singleton

/** Text and language from this OCR result only; not persisted with settings or presets. */
data class TtsLanguageEvidence(val text: String, val language: String?)

internal object SourceTtsLanguagePolicy {
    private fun compact(text: String) = text.filterNot(Char::isWhitespace)

    fun reusedLanguage(text: String, evidence: List<TtsLanguageEvidence>): String? {
        val selected = compact(text)
        if (selected.isEmpty()) return null
        val parts = evidence.filter { compact(it.text).let { part -> part.isNotEmpty() && part in selected } }
        // Never apply a page-wide language to a different substring, corrected text or mixed-language selection.
        if (parts.joinToString("") { compact(it.text) } != selected) return null
        val languages = parts.map { spokenTtsLanguageTag(it.language.orEmpty()) }
        return languages.distinct().singleOrNull()?.takeUnless { it == "auto" || it.equals("und", true) }
    }

    suspend fun resolve(
        text: String,
        evidence: List<TtsLanguageEvidence>,
        fallback: String,
        identify: suspend (String) -> String?,
    ): String = reusedLanguage(text, evidence)
        ?: identify(text)?.let(::spokenTtsLanguageTag)?.takeUnless { it == "auto" || it.equals("und", true) }
        ?: resolvedSpokenTtsLanguageTag(text, fallback)
}

@Singleton
class SourceTtsLanguageResolver @Inject constructor(private val identifier: OcrTextLanguageIdentifier) {
    suspend fun resolve(text: String, evidence: List<TtsLanguageEvidence>, fallback: String): String =
        SourceTtsLanguagePolicy.resolve(text, evidence, fallback, identifier::identify)
}
