package com.gameocr.app.llm

import kotlinx.coroutines.CompletableDeferred
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
}
