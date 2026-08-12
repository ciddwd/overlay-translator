package com.gameocr.app.translate

import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.RuntimeTranslationPromptContext

internal object SakuraBatchPromptScopePolicy {
    fun resolve(mode: TranslationContextMode): BatchPromptScope = when (mode) {
        TranslationContextMode.FAST_PER_SEGMENT -> BatchPromptScope.ISOLATED_ITEMS
        TranslationContextMode.PAGE_CONTEXT,
        TranslationContextMode.CONTINUOUS_CONTEXT,
        -> BatchPromptScope.SHARED_PAGE
    }

    fun promptContext(
        mode: TranslationContextMode,
        context: RuntimeTranslationPromptContext,
    ): RuntimeTranslationPromptContext = if (resolve(mode) == BatchPromptScope.ISOLATED_ITEMS) {
        context.copy(
            currentPage = emptyList(),
            previousFrame = emptyList(),
        )
    } else {
        context
    }
}

internal data class SakuraContextBatchPlan(
    val sourceLines: List<String>,
    val joinedSource: String,
)

internal data class SakuraContextGroup(
    val startIndex: Int,
    val sourceLines: List<String>,
) {
    val joinedSource: String = sourceLines.joinToString("\n")
}

internal object SakuraContextBatchPolicy {
    fun plan(sources: List<String>): SakuraContextBatchPlan? {
        if (sources.size < 2) return null
        val sourceLines = sources.map(::normalizeRegionLine)
        if (sourceLines.any(String::isBlank)) return null
        return SakuraContextBatchPlan(
            sourceLines = sourceLines,
            joinedSource = sourceLines.joinToString("\n"),
        )
    }

    fun parse(output: String?, expectedCount: Int): List<String>? {
        if (output == null || expectedCount < 1) return null
        val lines = output
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
            .split('\n')
            .map(String::trim)
        if (lines.size != expectedCount || lines.any(String::isBlank)) return null
        return lines
    }

    fun groups(
        sources: List<String>,
        maxPromptTokens: Int,
        promptTokenCount: (String) -> Int,
    ): List<SakuraContextGroup>? {
        require(maxPromptTokens > 0)
        if (sources.isEmpty()) return emptyList()
        val normalized = sources.map(::normalizeRegionLine)
        if (normalized.any(String::isBlank)) return null

        val groups = mutableListOf<SakuraContextGroup>()
        var startIndex = 0
        var pending = mutableListOf<String>()
        normalized.forEachIndexed { index, line ->
            val candidate = pending + line
            val exceedsTokenBudget = pending.isNotEmpty() &&
                promptTokenCount(candidate.joinToString("\n")) > maxPromptTokens
            if (exceedsTokenBudget) {
                groups += SakuraContextGroup(startIndex = startIndex, sourceLines = pending)
                startIndex = index
                pending = mutableListOf(line)
            } else {
                pending += line
            }
        }
        if (pending.isNotEmpty()) {
            groups += SakuraContextGroup(startIndex = startIndex, sourceLines = pending)
        }
        return groups
    }

    private fun normalizeRegionLine(source: String): String = source
        .replace("\r\n", " ")
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
}
