package com.gameocr.app.tts

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPreparationGateTest {
    @Test
    fun pendingIdentification_tableDriven_pauseResumeCloseAndReplacement() = runBlocking {
        for (backend in TtsPlaybackBackend.entries) {
            for (action in listOf("resume", "close", "replace")) {
                val coordinator = TtsPlaybackCoordinator()
                val gate = TtsPreparationGate(coordinator)
                val token = coordinator.begin("source", backend)
                gate.begin(token)
                assertTrue(gate.pause())
                val ready = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitReady(token) }
                assertFalse("Must not speak while identification is paused", ready.isCompleted)
                when (action) {
                    "resume" -> assertTrue(gate.resume())
                    "close" -> { gate.clear(); coordinator.clear() }
                    "replace" -> gate.begin(coordinator.begin("translation", backend))
                }
                if (action == "resume") assertTrue(ready.await()) else assertFalse(ready.await())
            }
        }
    }

    @Test
    fun finish_tableDriven_neverClearsAnotherRequestAndWorksBeyondBoxedLongCache() = runBlocking {
        for (count in listOf(1, 128, 257)) {
            val coordinator = TtsPlaybackCoordinator()
            val gate = TtsPreparationGate(coordinator)
            var token = 0L
            repeat(count) { token = coordinator.begin("source", TtsPlaybackBackend.SYSTEM) }
            gate.begin(token)
            assertTrue(gate.awaitReady(token))
            gate.finish(token)
            assertFalse(gate.pause())
            assertFalse(gate.resume())
            val next = coordinator.begin("next", TtsPlaybackBackend.SYSTEM)
            gate.begin(next)
            gate.finish(token)
            assertTrue(gate.pause())
            assertTrue(gate.resume())
            assertFalse(gate.awaitReady(token))
            assertTrue(gate.awaitReady(next))
        }
    }
}
