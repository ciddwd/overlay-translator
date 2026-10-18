package com.gameocr.app.asr

import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChunkQueueTest {
    private fun chunk(size: Int, value: Float) = FloatArray(size) { value }

    @Test fun overflowDropsOldestAndMarksTheGap_tableDriven() {
        data class Case(
            val name: String,
            val sizes: List<Int>,
            val max: Int,
            val expectedFirsts: List<Float>,
            val expectedGaps: List<Boolean>,
            val dropped: Long,
        )
        listOf(
            Case("fits", listOf(4, 4), 8, listOf(0f, 1f), listOf(false, false), 0),
            // The gap sits before the oldest remaining chunk, not before the newest.
            Case("drops oldest", listOf(4, 4, 4), 8, listOf(1f, 2f), listOf(true, false), 4),
            Case("drops several", listOf(2, 2, 2, 6), 8, listOf(2f, 3f), listOf(true, false), 4),
            // A single chunk above the bound is still delivered, after a gap.
            Case("oversized chunk", listOf(4, 10), 8, listOf(1f), listOf(true), 4),
        ).forEach { case ->
            val queue = AudioChunkQueue(case.max)
            case.sizes.forEachIndexed { index, size -> queue.offer(chunk(size, index.toFloat()), index.toLong()) }
            val taken = generateSequence { queue.take(0) }.toList()
            assertEquals(case.name, case.expectedFirsts, taken.map { it.samples.first() })
            assertEquals(case.name, case.expectedGaps, taken.map { it.discontinuity })
            assertEquals(case.name, case.dropped, queue.droppedSamples)
            assertEquals(case.name, 0, queue.backlogSamples)
        }
    }

    @Test fun clearAndMarkGapMakeTheNextChunkDiscontinuous_tableDriven() {
        data class Case(val name: String, val action: (AudioChunkQueue) -> Unit, val gap: Boolean)
        listOf(
            Case("nothing", {}, false),
            Case("clear with audio", { it.offer(chunk(2, 9f), 0) ; it.clear() }, true),
            Case("clear when empty", { it.clear() }, false),
            Case("own audio skipped", { it.markGap() }, true),
        ).forEach { case ->
            val queue = AudioChunkQueue(100)
            case.action(queue)
            queue.offer(chunk(2, 1f), 1)
            val next = queue.take(0)!!
            assertEquals(case.name, 1f, next.samples.first())
            assertEquals(case.name, case.gap, next.discontinuity)
        }
    }

    @Test fun takeTimesOutAndCloseWakesABlockedReader() {
        val queue = AudioChunkQueue(100)
        assertNull(queue.take(20))
        var result: AudioChunkQueue.Chunk? = AudioChunkQueue.Chunk(FloatArray(1), 0, false)
        val reader = thread { result = queue.take(5_000) }
        Thread.sleep(50)
        queue.close()
        reader.join(2_000)
        assertFalse(reader.isAlive)
        assertNull(result)
        assertEquals(0, queue.offer(chunk(2, 1f), 0))
        assertNull(queue.take(0))
    }

    @Test fun partialDecodesStretchWithCostAndStopWhileBehind_tableDriven() {
        data class Case(val name: String, val lastCostMs: Long?, val since: Int, val backlog: Int, val decode: Boolean)
        val base = 8_000 // 0.5 s at 16 kHz
        listOf(
            Case("first partial at base interval", null, 8_000, 0, true),
            Case("before base interval", null, 7_999, 0, false),
            // 300 ms per pass at a 30% budget means one pass per second of audio.
            Case("expensive pass waits", 300, 8_000, 0, false),
            Case("expensive pass after budget", 300, 16_000, 0, true),
            Case("cheap pass keeps base", 50, 8_000, 0, true),
            Case("behind playback skips", null, 8_000, 8_001, false),
            Case("small backlog allowed", null, 8_000, 8_000, true),
        ).forEach { case ->
            val policy = PartialDecodePolicy(base, maxBacklogSamples = 8_000)
            case.lastCostMs?.let(policy::recordCost)
            assertEquals(case.name, case.decode, policy.shouldDecode(case.since, case.backlog))
        }
    }

    @Test fun partialPolicyResetsForANewUtteranceAndCanBeDisabled() {
        val policy = PartialDecodePolicy(8_000, 8_000)
        policy.recordCost(1_000)
        assertTrue(policy.intervalSamples > 8_000)
        policy.reset()
        assertEquals(8_000, policy.intervalSamples)
        assertFalse(PartialDecodePolicy(0, 8_000).shouldDecode(Int.MAX_VALUE, 0))
    }
}
