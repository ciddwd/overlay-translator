package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestFrameUpdateCoalescerTest {

    @Test
    fun latestFrameUpdateCoalescer_tableDriven_keepsLatestAndNeverOverwritesFinal() {
        data class Step(val value: String? = null, val runFrame: Boolean = false, val discard: Boolean = false)
        data class Case(
            val name: String,
            val steps: List<Step>,
            val expectedPublished: List<String>,
            val expectedScheduledFrames: Int,
            val expectedStats: FrameUpdateStats,
        )

        val cases = listOf(
            Case(
                name = "burst publishes only latest partial",
                steps = listOf(Step("a"), Step("ab"), Step("abc"), Step(runFrame = true)),
                expectedPublished = listOf("abc"),
                expectedScheduledFrames = 1,
                expectedStats = FrameUpdateStats(received = 3, published = 1),
            ),
            Case(
                name = "next burst schedules the next frame",
                steps = listOf(
                    Step("a"), Step(runFrame = true),
                    Step("ab"), Step("abc"), Step(runFrame = true),
                ),
                expectedPublished = listOf("a", "abc"),
                expectedScheduledFrames = 2,
                expectedStats = FrameUpdateStats(received = 3, published = 2),
            ),
            Case(
                name = "final supersedes pending partial before queued frame",
                steps = listOf(Step("partial"), Step(discard = true), Step(runFrame = true)),
                expectedPublished = emptyList(),
                expectedScheduledFrames = 1,
                expectedStats = FrameUpdateStats(received = 1, published = 0),
            ),
            Case(
                name = "discard is safe with no pending frame",
                steps = listOf(Step(discard = true), Step("done"), Step(runFrame = true)),
                expectedPublished = listOf("done"),
                expectedScheduledFrames = 1,
                expectedStats = FrameUpdateStats(received = 1, published = 1),
            ),
        )

        cases.forEach { case ->
            val scheduled = ArrayDeque<Runnable>()
            val published = mutableListOf<String>()
            var scheduledCount = 0
            val coalescer = LatestFrameUpdateCoalescer<String>(
                scheduleFrame = {
                    scheduledCount += 1
                    scheduled.addLast(it)
                },
                publish = published::add,
            )

            case.steps.forEach { step ->
                step.value?.let(coalescer::submit)
                if (step.discard) coalescer.discardPending()
                if (step.runFrame) scheduled.removeFirst().run()
            }

            assertEquals("${case.name} values", case.expectedPublished, published)
            assertEquals("${case.name} schedules", case.expectedScheduledFrames, scheduledCount)
            assertEquals("${case.name} stats", case.expectedStats, coalescer.stats())
            assertEquals(
                "${case.name} coalesced",
                case.expectedStats.received - case.expectedStats.published,
                coalescer.stats().coalesced,
            )
        }
    }
}
