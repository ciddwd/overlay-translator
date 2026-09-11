package com.gameocr.app.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TilePostClickStatePolicyTest {

    @Test
    fun postClickState_tableDriven_neverDowngradesOnATimeout() {
        data class Case(
            val name: String,
            val serviceRunning: Boolean,
            val stoppedWithoutRunningSinceClick: Boolean,
            val expected: CaptureTileState?,
        )

        listOf(
            Case("service came up", true, false, CaptureTileState.ACTIVE),
            Case("the start gave up", false, true, CaptureTileState.INACTIVE),
            Case("still starting", false, false, null),
            Case("running outranks an older instance stopping", true, true, CaptureTileState.ACTIVE),
        ).forEach { case ->
            assertEquals(
                "${case.name}: running=${case.serviceRunning} stopped=${case.stoppedWithoutRunningSinceClick}",
                case.expected,
                resolveTilePostClickState(case.serviceRunning, case.stoppedWithoutRunningSinceClick),
            )
        }
    }

    @Test
    fun anUnknownOutcomeLeavesTheOptimisticValueAlone() {
        assertNull(
            "writing INACTIVE here would be worse than leaving a wrong ACTIVE: the next tap would be " +
                "routed to STOP and kill a service that is merely still coming up",
            resolveTilePostClickState(
                serviceRunning = false,
                stoppedWithoutRunningSinceClick = false,
            ),
        )
    }

    @Test
    fun onlyTheTwoStartRoutesHaveAnOutcomeToWaitFor() {
        val watched = CaptureStartRoute.entries.filter(::routeStartsCaptureAttempt)
        assertEquals(
            "stop and the permission/availability branches never change the running state, so the tile " +
                "has nothing to settle after them",
            listOf(CaptureStartRoute.DIRECT_FGS, CaptureStartRoute.TRAMPOLINE),
            watched,
        )
    }
}
