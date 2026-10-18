package com.gameocr.app.llm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LlamaEngineLifecycleGateTest {

    @Test
    fun lifecycleOperations_areMutuallyExclusive() = runBlocking {
        data class Case(
            val name: String,
            val firstOperation: String,
            val secondOperation: String,
        )

        val cases = listOf(
            Case("prompt metrics block unload", "prompt-metrics", "unload"),
            Case("inference blocks model switch", "inference", "model-switch"),
            Case("unload blocks the next inference", "unload", "inference"),
        )

        cases.forEach { case ->
            val gate = LlamaEngineLifecycleGate()
            val releaseFirst = CompletableDeferred<Unit>()
            val firstEntered = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()

            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withSession {
                    events += "${case.firstOperation}:enter"
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    events += "${case.firstOperation}:exit"
                }
            }
            firstEntered.await()

            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withSession {
                    events += "${case.secondOperation}:enter"
                    secondEntered.complete(Unit)
                    events += "${case.secondOperation}:exit"
                }
            }

            assertFalse(case.name, secondEntered.isCompleted)
            releaseFirst.complete(Unit)
            joinAll(first, second)

            assertEquals(
                case.name,
                listOf(
                    "${case.firstOperation}:enter",
                    "${case.firstOperation}:exit",
                    "${case.secondOperation}:enter",
                    "${case.secondOperation}:exit",
                ),
                events,
            )
        }
    }

    @Test
    fun failedSession_releasesTheGate() = runBlocking {
        val gate = LlamaEngineLifecycleGate()
        val failure = runCatching {
            gate.withSession<Unit> { error("expected") }
        }

        assertEquals("expected", failure.exceptionOrNull()?.message)
        assertEquals("next", gate.withSession { "next" })
    }

    @Test
    fun queuedIdleCheck_observesCompletionTimeBeforeGateIsReleased() = runBlocking {
        listOf("success", "failure", "cancellation").forEach { outcome ->
            val gate = LlamaEngineLifecycleGate()
            val release = CompletableDeferred<Unit>()
            var now = 100L
            var lastUse = now
            var shouldUnload = true
            val inference = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    gate.withSession {
                        try {
                            release.await()
                            if (outcome == "failure") error("expected")
                        } finally {
                            // Matches the holder: record completion while still owning the gate.
                            lastUse = now
                        }
                    }
                } catch (_: CancellationException) {
                    // Cancellation is the expected outcome for this case.
                } catch (failure: IllegalStateException) {
                    assertEquals("expected", failure.message)
                }
            }
            val idleCheck = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withSession { shouldUnload = now - lastUse >= 1_000L }
            }
            now = 10_000L // Translation took longer than the configured idle timeout.
            if (outcome == "cancellation") inference.cancel() else release.complete(Unit)
            joinAll(inference, idleCheck)
            assertFalse(outcome, shouldUnload)
            assertEquals(outcome, now, lastUse)
        }
    }
}
