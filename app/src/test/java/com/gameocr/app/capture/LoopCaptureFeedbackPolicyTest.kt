package com.gameocr.app.capture

import org.junit.Assert.*
import org.junit.Test

class LoopCaptureFeedbackPolicyTest {
    @Test fun feedbackIsOptionalAndOnlyOncePerProcessingCapture_tableDriven() {
        for (enabled in listOf(false, true)) for (loopActive in listOf(false, true)) {
            val policy = LoopCaptureFeedbackPolicy()
            val expected = enabled && loopActive
            assertEquals("$enabled/$loopActive", expected, policy.accept(enabled, loopActive, 1L))
            assertFalse("same capture must not repeat", policy.accept(enabled, loopActive, 1L))
            assertEquals("new capture", expected, policy.accept(enabled, loopActive, 2L))
        }
    }

    @Test fun disabledActionsDoNotConsumeFeedbackAndNewSessionResetsIt() {
        val policy = LoopCaptureFeedbackPolicy()
        assertFalse(policy.accept(false, true, 1L))
        assertFalse(policy.accept(true, false, 1L))
        assertTrue(policy.accept(true, true, 1L))
        assertFalse(policy.accept(true, true, 1L))
        policy.reset()
        assertTrue(policy.accept(true, true, 1L))
    }
}
