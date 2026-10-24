package com.gameocr.app.asr

import org.junit.Assert.*
import org.junit.Test

class SpeechReadinessTest {
    @Test fun waitsForCaptureAndRemoteInEitherOrder_tableDriven() {
        listOf(true, false).forEach { captureFirst ->
            val gate = SpeechReadiness()
            gate.begin(1, remote = true)
            assertFalse(if (captureFirst) gate.captureStarted(1) else gate.engineConfirmed(1))
            assertTrue(if (captureFirst) gate.engineConfirmed(1) else gate.captureStarted(1))
            assertFalse(gate.captureStarted(1))
            assertFalse(gate.engineConfirmed(1))
        }
    }

    @Test fun localWaitsForCaptureAndOldCallbacksCannotReadyResumedSession() {
        val gate = SpeechReadiness()
        gate.begin(1, remote = false)
        assertFalse(gate.engineConfirmed(1))
        assertTrue(gate.captureStarted(1))
        gate.invalidate()
        assertFalse(gate.captureStarted(1))
        gate.begin(3, remote = true)
        assertFalse(gate.engineConfirmed(1))
        assertFalse(gate.captureStarted(3))
        assertTrue(gate.engineConfirmed(3))
    }
}
