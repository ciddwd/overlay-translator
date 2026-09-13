package com.gameocr.app.translate

internal data class SakuraGenerationBudget(
    val sourceTokens: Int,
    val configuredMaxNewTokens: Int,
    val effectiveMaxNewTokens: Int,
) {
    val adaptive: Boolean
        get() = effectiveMaxNewTokens < configuredMaxNewTokens
}

/**
 * Caps multi-line Sakura generation before a structurally invalid response can consume the full
 * user limit. Single-line retries retain the configured limit, so adaptive rejection always has a
 * full-budget fallback after the existing binary split.
 */
internal object SakuraGenerationBudgetPolicy {
    private const val MIN_MULTI_LINE_BUDGET = 64
    private const val FIXED_HEADROOM_TOKENS = 16
    private const val PER_LINE_HEADROOM_TOKENS = 4

    fun decide(
        configuredMaxNewTokens: Int,
        sourceTokens: Int,
        lineCount: Int,
    ): SakuraGenerationBudget {
        val configured = configuredMaxNewTokens.coerceAtLeast(1)
        val normalizedSourceTokens = sourceTokens.coerceAtLeast(0)
        if (lineCount <= 1) {
            return SakuraGenerationBudget(
                sourceTokens = normalizedSourceTokens,
                configuredMaxNewTokens = configured,
                effectiveMaxNewTokens = configured,
            )
        }

        val estimated = estimatedRequiredTokens(
            sourceTokens = normalizedSourceTokens,
            lineCount = lineCount,
        )
        val minimum = minOf(configured, MIN_MULTI_LINE_BUDGET)
        val effective = estimated
            .coerceAtLeast(minimum.toLong())
            .coerceAtMost(configured.toLong())
            .toInt()
        return SakuraGenerationBudget(
            sourceTokens = normalizedSourceTokens,
            configuredMaxNewTokens = configured,
            effectiveMaxNewTokens = effective,
        )
    }

    /**
     * A configured output limit must remain a hard upper bound. If a multi-line group is already
     * estimated to exceed it, split before inference instead of spending a full generation that
     * can only end in a truncated, structurally invalid response. This is capacity planning, not
     * a retry, so it deliberately does not depend on the user's failure-retry preference.
     */
    fun requiresPreSplit(
        configuredMaxNewTokens: Int,
        sourceTokens: Int,
        lineCount: Int,
    ): Boolean {
        if (lineCount <= 1) return false
        val configured = configuredMaxNewTokens.coerceAtLeast(1).toLong()
        return estimatedRequiredTokens(
            sourceTokens = sourceTokens.coerceAtLeast(0),
            lineCount = lineCount,
        ) > configured
    }

    private fun estimatedRequiredTokens(sourceTokens: Int, lineCount: Int): Long {
        val source = sourceTokens.coerceAtLeast(0).toLong()
        val proportionalHeadroom = (source + 1L) / 2L
        return source +
            proportionalHeadroom +
            lineCount.coerceAtLeast(0).toLong() * PER_LINE_HEADROOM_TOKENS +
            FIXED_HEADROOM_TOKENS
    }
}
