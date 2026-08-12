package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredOutputCapabilityPolicyTest {
    @Test
    fun tracker_tableDriven_requiresTwoConsecutiveFailuresAndRecoversAfterCooldown() {
        var now = 1_000L
        val tracker = StructuredOutputCapabilityTracker(
            failureThreshold = 2,
            cooldownMs = 500L,
            nowMs = { now },
        )
        data class Step(
            val name: String,
            val observed: Boolean? = null,
            val advanceMs: Long = 0L,
            val expectedAttempt: Boolean,
        )
        listOf(
            Step("unknown tries structured", expectedAttempt = true),
            Step("one incomplete batch is tolerated", observed = false, expectedAttempt = true),
            Step("second consecutive incomplete batch enters cooldown", observed = false, expectedAttempt = false),
            Step("cooldown still active", advanceMs = 499L, expectedAttempt = false),
            Step("cooldown expires", advanceMs = 1L, expectedAttempt = true),
            Step("a complete structured batch clears failures", observed = true, expectedAttempt = true),
        ).forEach { step ->
            now += step.advanceMs
            step.observed?.let { tracker.record("provider/model", it) }
            assertEquals(step.name, step.expectedAttempt, tracker.shouldAttemptStructured("provider/model"))
        }
    }

    @Test
    fun tracker_isolatedByEndpointAndModel() {
        val tracker = StructuredOutputCapabilityTracker(failureThreshold = 1)
        tracker.record("bad/model", completeStructuredBatchObserved = false)

        assertEquals(false, tracker.shouldAttemptStructured("bad/model"))
        assertEquals(true, tracker.shouldAttemptStructured("good/model"))
    }

    @Test
    fun individualFallback_tableDriven_preservesOrderAndLimitsPeakConcurrency() = runBlocking {
        data class Case(
            val name: String,
            val sourceCount: Int,
            val maxConcurrency: Int,
            val failedIndex: Int? = null,
        )

        listOf(
            Case("empty", sourceCount = 0, maxConcurrency = 4),
            Case("single", sourceCount = 1, maxConcurrency = 4),
            Case("page capped at three", sourceCount = 9, maxConcurrency = 3),
            Case("non-positive limit coerces to one", sourceCount = 4, maxConcurrency = 0),
            Case("failure releases permit", sourceCount = 8, maxConcurrency = 4, failedIndex = 2),
        ).forEach { case ->
            val sources = List(case.sourceCount) { index -> "source-$index" }
            val active = AtomicInteger(0)
            val peak = AtomicInteger(0)
            val updatedIndexes = mutableListOf<Int>()
            val results = translateStructuredFallbackIndividually(
                sources = sources,
                settings = Settings(),
                onUpdate = { update -> synchronized(updatedIndexes) { updatedIndexes += update.index } },
                maxConcurrency = case.maxConcurrency,
            ) { source, _ ->
                val current = active.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    delay(20)
                    val index = source.substringAfterLast('-').toInt()
                    if (index == case.failedIndex) error("expected test failure")
                    "translated-$index"
                } finally {
                    active.decrementAndGet()
                }
            }

            val effectiveLimit = case.maxConcurrency.coerceAtLeast(1)
            assertEquals(case.name, minOf(case.sourceCount, effectiveLimit), peak.get())
            assertEquals(case.name, sources.indices.toList(), synchronized(updatedIndexes) { updatedIndexes.sorted() })
            assertEquals(
                case.name,
                sources.indices.map { index ->
                    if (index == case.failedIndex) null else "translated-$index"
                },
                results,
            )
        }
    }
}
