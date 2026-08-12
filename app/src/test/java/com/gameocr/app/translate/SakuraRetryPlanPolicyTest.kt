package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class SakuraRetryPlanPolicyTest {

    @Test
    fun structuralFailure_tableDriven_isBoundedAndPreservesIndexes() {
        data class Case(
            val name: String,
            val startIndex: Int,
            val lineCount: Int,
            val stage: SakuraRetryStage,
            val retryEnabled: Boolean,
            val expectedSalvageStarts: List<Int>,
            val expectedSalvageSizes: List<Int>,
            val expectedIsolatedIndexes: List<Int>,
        )

        listOf(
            Case("disabled initial", 3, 5, SakuraRetryStage.INITIAL, false, emptyList(), emptyList(), emptyList()),
            Case("initial even split", 0, 6, SakuraRetryStage.INITIAL, true, listOf(0, 3), listOf(3, 3), emptyList()),
            Case("initial odd split", 4, 7, SakuraRetryStage.INITIAL, true, listOf(4, 7), listOf(3, 4), emptyList()),
            Case("initial singleton", 8, 1, SakuraRetryStage.INITIAL, true, emptyList(), emptyList(), listOf(8)),
            Case("salvage group goes isolated", 6, 4, SakuraRetryStage.SALVAGE, true, emptyList(), emptyList(), listOf(6, 7, 8, 9)),
            Case("salvage singleton goes isolated", 12, 1, SakuraRetryStage.SALVAGE, true, emptyList(), emptyList(), listOf(12)),
        ).forEach { case ->
            val actual = SakuraRetryPlanPolicy.structuralFailure(
                group = SakuraContextGroup(
                    startIndex = case.startIndex,
                    sourceLines = List(case.lineCount) { "line-$it" },
                ),
                stage = case.stage,
                retryEnabled = case.retryEnabled,
            )
            assertEquals(case.name, case.expectedSalvageStarts, actual.salvageGroups.map { it.startIndex })
            assertEquals(case.name, case.expectedSalvageSizes, actual.salvageGroups.map { it.sourceLines.size })
            assertEquals(case.name, case.expectedIsolatedIndexes, actual.isolatedIndexes)
        }
    }

    @Test
    fun rejectedLines_tableDriven_retriesOnlyValidRejectedIndexes() {
        data class Case(
            val name: String,
            val retryEnabled: Boolean,
            val rejectedLocalIndexes: List<Int>,
            val expectedIsolatedIndexes: List<Int>,
        )

        val group = SakuraContextGroup(
            startIndex = 10,
            sourceLines = listOf("a", "b", "c", "d"),
        )
        listOf(
            Case("disabled", false, listOf(0, 2), emptyList()),
            Case("one rejected line", true, listOf(2), listOf(12)),
            Case("multiple rejected lines retain order", true, listOf(3, 0, 2), listOf(13, 10, 12)),
            Case("duplicates are removed", true, listOf(1, 1, 3), listOf(11, 13)),
            Case("out of range indexes are ignored", true, listOf(-1, 0, 4), listOf(10)),
            Case("no rejected lines", true, emptyList(), emptyList()),
        ).forEach { case ->
            val actual = SakuraRetryPlanPolicy.rejectedLines(
                group = group,
                rejectedLocalIndexes = case.rejectedLocalIndexes,
                retryEnabled = case.retryEnabled,
            )
            assertEquals(case.name, case.expectedIsolatedIndexes, actual.isolatedIndexes)
            assertEquals(case.name, emptyList<SakuraContextGroup>(), actual.salvageGroups)
        }
    }
}
