package com.gameocr.app.ui

import com.gameocr.app.translate.TranslationMemoryEntity
import java.text.Normalizer
import java.util.Locale

internal object TranslationMemoryListFilterPolicy {
    fun filter(
        entries: List<TranslationMemoryEntity>,
        query: String,
    ): List<TranslationMemoryEntity> {
        val tokens = normalized(query)
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
        if (tokens.isEmpty()) return entries

        return entries.filter { entry ->
            val searchable = normalized(
                listOf(
                    entry.observedSource,
                    entry.correctedSource,
                    entry.correctedTranslation,
                    entry.appLabel,
                    entry.scopePackage,
                    entry.sourceLang,
                    entry.targetLang,
                ).joinToString("\n")
            )
            tokens.all(searchable::contains)
        }
    }

    private fun normalized(value: String): String = Normalizer
        .normalize(value.trim(), Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
}
