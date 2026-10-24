package com.gameocr.app.asr

import com.gameocr.app.overlay.SpeechBallState
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSessionGuardsTest {
    @Test fun repeatedTapsDuringPreparationStartOnce() {
        val guard = SpeechStartGuard()
        val first = guard.begin()
        assertNotNull(first)
        // Taps while checks and Shizuku/root preparation run are ignored.
        repeat(5) { assertNull(guard.begin()) }
        assertTrue(guard.isActive)
        assertTrue(guard.finish(first!!))
        assertFalse(guard.isActive)
        val second = guard.begin()
        assertNotNull(second)
        assertTrue(second != first)
    }

    @Test fun stoppedRequestCannotHandOffLater_tableDriven() {
        data class Case(val name: String, val act: (SpeechStartGuard, Long) -> Unit, val current: Boolean, val finishes: Boolean)
        listOf(
            Case("untouched", { _, _ -> }, current = true, finishes = true),
            Case("session stopped meanwhile", { g, _ -> g.invalidate() }, current = false, finishes = false),
            // A newer tap after a stop owns the guard; the old request must not end it.
            Case("superseded by a new tap", { g, _ -> g.invalidate(); g.begin() }, current = false, finishes = false),
            Case("already finished", { g, t -> g.finish(t) }, current = false, finishes = false),
        ).forEach { case ->
            val guard = SpeechStartGuard()
            val token = guard.begin()!!
            case.act(guard, token)
            assertEquals(case.name, case.current, guard.isCurrent(token))
            assertEquals(case.name, case.finishes, guard.finish(token))
        }
    }

    @Test fun onlyTheLoadedEngineMayEndTheSession_tableDriven() {
        data class Case(val name: String, val state: SpeechBallState, val loaded: Boolean, val failed: Long, val current: Long, val accepted: Boolean)
        listOf(
            Case("current engine while recognizing", SpeechBallState.RECOGNIZING, true, 3, 3, true),
            // Pause/resume bump the generation but keep the engine and its instance.
            Case("current engine while paused", SpeechBallState.PAUSED, true, 3, 3, true),
            Case("late error from an older session", SpeechBallState.RECOGNIZING, true, 2, 3, false),
            Case("late error while a new session prepares", SpeechBallState.PREPARING, true, 2, 3, false),
            Case("session already idle", SpeechBallState.IDLE, false, 3, 3, false),
            Case("engine already released", SpeechBallState.RECOGNIZING, false, 3, 3, false),
        ).forEach { case ->
            assertEquals(case.name, case.accepted,
                SpeechFailurePolicy.acceptsCloudFailure(case.state, case.loaded, case.failed, case.current))
        }
    }

    @Test fun workerLoopFailuresGoToCleanupAndCancellationPropagates_tableDriven() {
        data class Case(val name: String, val error: Exception?, val routed: Boolean)
        listOf(
            Case("normal end", null, false),
            // AudioRecord.startRecording throws IllegalStateException when the recorder cannot start.
            Case("recorder start failure", IllegalStateException("startRecording() called on an uninitialized AudioRecord."), true),
            Case("runtime failure", RuntimeException("native"), true),
        ).forEach { case ->
            val routed = mutableListOf<Exception>()
            runWorkerLoop(onFailure = { routed += it }) { case.error?.let { throw it } }
            assertEquals(case.name, if (case.routed) listOf(case.error) else emptyList<Exception>(), routed)
        }
        assertThrows(CancellationException::class.java) {
            runWorkerLoop(onFailure = { error("cancellation is not a failure") }) { throw CancellationException("stop") }
        }
    }
}
