package com.gameocr.app.translate

internal data class SakuraContextBatchPlan(
    val sourceLines: List<String>,
    val joinedSource: String,
)

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
        if (output == null || expectedCount < 2) return null
        val lines = output
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
            .split('\n')
            .map(String::trim)
        if (lines.size != expectedCount || lines.any(String::isBlank)) return null
        return lines
    }

    private fun normalizeRegionLine(source: String): String = source
        .replace("\r\n", " ")
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
}
