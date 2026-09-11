package com.gameocr.app.tile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileTriggerThrottlePolicyTest {

    @Test
    fun firstTriggerIsAlwaysAccepted() {
        assertTrue(shouldAcceptTileTrigger(lastAcceptedAtMs = null, nowMs = 0L))
        assertTrue(shouldAcceptTileTrigger(lastAcceptedAtMs = null, nowMs = 1_700_000_000_000L))
    }

    @Test
    fun rapidRepeatsAreSuppressed() {
        val t0 = 1_000_000L
        assertFalse("same millisecond", shouldAcceptTileTrigger(t0, t0))
        assertFalse("+1ms", shouldAcceptTileTrigger(t0, t0 + 1))
        assertFalse("+100ms", shouldAcceptTileTrigger(t0, t0 + 100))
        assertFalse(
            "just inside the window",
            shouldAcceptTileTrigger(t0, t0 + TILE_TRIGGER_MIN_INTERVAL_MS - 1),
        )
    }

    @Test
    fun acceptedAtWindowBoundaryAndBeyond() {
        val t0 = 1_000_000L
        assertTrue(
            "exactly at the window boundary",
            shouldAcceptTileTrigger(t0, t0 + TILE_TRIGGER_MIN_INTERVAL_MS),
        )
        assertTrue(
            "well past the window",
            shouldAcceptTileTrigger(t0, t0 + TILE_TRIGGER_MIN_INTERVAL_MS * 10),
        )
    }

    @Test
    fun clockGoingBackwardsDoesNotLockOutForever() {
        assertTrue(shouldAcceptTileTrigger(lastAcceptedAtMs = 2_000_000L, nowMs = 1_000_000L))
    }
}
