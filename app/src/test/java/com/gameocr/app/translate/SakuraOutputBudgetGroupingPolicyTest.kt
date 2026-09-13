package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SakuraOutputBudgetGroupingPolicyTest {

    @Test
    fun plan_tableDriven_splitsOnlyGroupsThatCannotFit() {
        data class Case(
            val name: String,
            val lineLengths: List<Int>,
            val configuredMax: Int,
            val expectedSizes: List<Int>,
            val expectedPreSplit: Boolean,
        )

        listOf(
            Case("observed fourteen-line shape becomes two halves", List(14) { 10 }, 256, listOf(7, 7), true),
            Case("small page remains one request", List(6) { 5 }, 256, listOf(6), false),
            Case("large page splits recursively", List(32) { 10 }, 128, listOf(4, 4, 4, 4, 4, 4, 4, 4), true),
            Case("oversized single line remains addressable", listOf(500), 64, listOf(1), false),
        ).forEach { case ->
            val sources = case.lineLengths.mapIndexed { index, length ->
                index.toString().padEnd(length, 'x')
            }
            val plan = SakuraOutputBudgetGroupingPolicy.plan(
                groups = listOf(SakuraContextGroup(startIndex = 0, sourceLines = sources)),
                configuredMaxNewTokens = case.configuredMax,
                sourceTokenCount = { text -> text.replace("\n", "").length },
            )

            assertEquals(case.name, case.expectedSizes, plan.groups.map { it.sourceLines.size })
            assertEquals(case.name, case.expectedPreSplit, plan.preSplit)
            assertEquals(case.name, sources, plan.groups.flatMap { it.sourceLines })
            assertEquals(case.name, sources.indices.toList(), plan.groups.flatMap { group ->
                group.sourceLines.indices.map { group.startIndex + it }
            })
        }
    }

    @Test
    fun plan_preservesExistingPromptGroupsAndOffsets() {
        val groups = listOf(
            SakuraContextGroup(startIndex = 0, sourceLines = listOf("a", "b")),
            SakuraContextGroup(startIndex = 2, sourceLines = listOf("c", "d")),
        )
        val plan = SakuraOutputBudgetGroupingPolicy.plan(
            groups = groups,
            configuredMaxNewTokens = 256,
            sourceTokenCount = String::length,
        )

        assertFalse(plan.preSplit)
        assertEquals(listOf(0, 2), plan.groups.map { it.startIndex })
        assertTrue(plan.groups.zip(groups).all { (actual, expected) -> actual == expected })
    }
}
