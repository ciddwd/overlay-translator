package com.gameocr.app.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TileStatePolicyTest {

    @Test
    fun state_tableDriven_matchesTheServiceAndSetupState() {
        val cases = listOf(
            Triple(true, true, CaptureTileState.ACTIVE),
            Triple(true, false, CaptureTileState.INACTIVE),
            Triple(false, true, CaptureTileState.UNAVAILABLE),
            Triple(false, false, CaptureTileState.UNAVAILABLE),
        )
        cases.forEach { (setupCompleted, running, expected) ->
            assertEquals(
                "setupCompleted=$setupCompleted running=$running",
                expected,
                resolveCaptureTileState(setupCompleted, running),
            )
        }
    }

    @Test
    fun shizukuReadinessMustNotMakeTileUnavailable() {
        assertNotEquals(
            CaptureTileState.UNAVAILABLE,
            resolveCaptureTileState(setupCompleted = true, serviceRunning = false),
        )
    }
}
