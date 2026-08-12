package com.gameocr.app.translate

internal enum class SakuraRetryStage {
    INITIAL,
    SALVAGE,
}

internal data class SakuraRetryPlan(
    val salvageGroups: List<SakuraContextGroup> = emptyList(),
    val isolatedIndexes: List<Int> = emptyList(),
) {
    companion object {
        val NONE = SakuraRetryPlan()
    }
}

/**
 * Plans a bounded Sakura recovery without inspecting source content.
 *
 * A structurally invalid initial group gets one context-preserving split. Any unresolved
 * subgroup or rejected line then moves to one final, independently mapped recovery batch.
 */
internal object SakuraRetryPlanPolicy {

    fun structuralFailure(
        group: SakuraContextGroup,
        stage: SakuraRetryStage,
        retryEnabled: Boolean,
    ): SakuraRetryPlan {
        if (!retryEnabled) return SakuraRetryPlan.NONE
        if (stage == SakuraRetryStage.INITIAL && group.sourceLines.size > 1) {
            val splitAt = group.sourceLines.size / 2
            return SakuraRetryPlan(
                salvageGroups = listOf(
                    SakuraContextGroup(
                        startIndex = group.startIndex,
                        sourceLines = group.sourceLines.subList(0, splitAt),
                    ),
                    SakuraContextGroup(
                        startIndex = group.startIndex + splitAt,
                        sourceLines = group.sourceLines.subList(splitAt, group.sourceLines.size),
                    ),
                )
            )
        }
        return SakuraRetryPlan(isolatedIndexes = group.resultIndexes())
    }

    fun rejectedLines(
        group: SakuraContextGroup,
        rejectedLocalIndexes: List<Int>,
        retryEnabled: Boolean,
    ): SakuraRetryPlan {
        if (!retryEnabled) return SakuraRetryPlan.NONE
        return SakuraRetryPlan(
            isolatedIndexes = rejectedLocalIndexes
                .asSequence()
                .filter { it in group.sourceLines.indices }
                .distinct()
                .map { group.startIndex + it }
                .toList()
        )
    }

    private fun SakuraContextGroup.resultIndexes(): List<Int> =
        sourceLines.indices.map { startIndex + it }
}
