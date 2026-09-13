package com.gameocr.app.translate

internal data class SakuraOutputBudgetGroupPlan(
    val originalGroupCount: Int,
    val groups: List<SakuraContextGroup>,
) {
    val preSplit: Boolean get() = groups.size > originalGroupCount
}

internal object SakuraOutputBudgetGroupingPolicy {
    fun plan(
        groups: List<SakuraContextGroup>,
        configuredMaxNewTokens: Int,
        sourceTokenCount: (String) -> Int,
    ): SakuraOutputBudgetGroupPlan {
        val planned = mutableListOf<SakuraContextGroup>()

        fun append(group: SakuraContextGroup) {
            val shouldSplit = SakuraGenerationBudgetPolicy.requiresPreSplit(
                configuredMaxNewTokens = configuredMaxNewTokens,
                sourceTokens = sourceTokenCount(group.joinedSource),
                lineCount = group.sourceLines.size,
            )
            if (!shouldSplit || group.sourceLines.size <= 1) {
                planned += group
                return
            }

            val splitAt = group.sourceLines.size / 2
            append(
                SakuraContextGroup(
                    startIndex = group.startIndex,
                    sourceLines = group.sourceLines.subList(0, splitAt),
                )
            )
            append(
                SakuraContextGroup(
                    startIndex = group.startIndex + splitAt,
                    sourceLines = group.sourceLines.subList(splitAt, group.sourceLines.size),
                )
            )
        }

        groups.forEach(::append)
        return SakuraOutputBudgetGroupPlan(
            originalGroupCount = groups.size,
            groups = planned,
        )
    }
}
