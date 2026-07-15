package com.gameocr.app.ui

import com.gameocr.app.glossary.GlossaryTermCategory
import com.gameocr.app.glossary.GlossaryTermEntity
import java.text.Normalizer
import java.util.Locale

internal enum class GlossaryStatusFilter {
    ALL,
    ENABLED,
    DISABLED,
}

internal data class GlossaryListFilter(
    val query: String = "",
    val categories: Set<GlossaryTermCategory> = emptySet(),
    val status: GlossaryStatusFilter = GlossaryStatusFilter.ALL,
) {
    val isActive: Boolean
        get() = query.isNotBlank() || categories.isNotEmpty() || status != GlossaryStatusFilter.ALL
}

internal object GlossaryListFilterPolicy {
    fun filter(
        terms: List<GlossaryTermEntity>,
        filter: GlossaryListFilter,
        categoryLabels: Map<GlossaryTermCategory, String>,
        globalScopeLabel: String,
    ): List<GlossaryTermEntity> {
        val tokens = normalized(filter.query)
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)

        return terms.filter { term ->
            val categoryMatches = filter.categories.isEmpty() || term.category in filter.categories
            val statusMatches = when (filter.status) {
                GlossaryStatusFilter.ALL -> true
                GlossaryStatusFilter.ENABLED -> term.enabled
                GlossaryStatusFilter.DISABLED -> !term.enabled
            }
            val scopeText = if (term.scopePackage.isBlank()) {
                globalScopeLabel
            } else {
                "${term.appLabel} ${term.scopePackage}"
            }
            val searchable = normalized(
                listOf(
                    term.sourceTerm,
                    term.targetTerm,
                    scopeText,
                    term.category.name,
                    categoryLabels[term.category].orEmpty(),
                ).joinToString("\n")
            )
            categoryMatches && statusMatches && tokens.all(searchable::contains)
        }
    }

    private fun normalized(value: String): String = Normalizer
        .normalize(value.trim(), Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
}
