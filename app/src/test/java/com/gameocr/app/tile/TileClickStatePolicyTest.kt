package com.gameocr.app.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileClickStatePolicyTest {

    @Test
    fun clickState_tableDriven_coversEveryRoute() {
        data class Case(
            val route: CaptureStartRoute,
            val directStartSucceeded: Boolean,
            val expected: CaptureTileState?,
        )

        listOf(
            Case(CaptureStartRoute.STOP, false, CaptureTileState.INACTIVE),
            Case(CaptureStartRoute.DIRECT_FGS, true, CaptureTileState.ACTIVE),
            Case(CaptureStartRoute.DIRECT_FGS, false, null),
            Case(CaptureStartRoute.TRAMPOLINE, false, null),
            Case(CaptureStartRoute.BLOCKED_NEED_UNLOCK, false, null),
            Case(CaptureStartRoute.NEEDS_OVERLAY_PERMISSION, false, null),
            Case(CaptureStartRoute.UNAVAILABLE, false, null),
        ).forEach { case ->
            assertEquals(
                "route=${case.route} directStartSucceeded=${case.directStartSucceeded}",
                case.expected,
                resolveTileClickState(case.route, case.directStartSucceeded),
            )
        }
    }

    @Test
    fun clickState_onlyWrittenWhenThePanelStaysOpen() {
        val routesThatWrite = CaptureStartRoute.entries.filter { route ->
            resolveTileClickState(route, directStartSucceeded = true) != null
        }
        assertEquals(
            "a route may only write its own terminal state when the panel survives the click: " +
                "stop and a direct start leave it open, so no refresh can follow; the routes that " +
                "launch an Activity collapse it, and the service's own refresh lands normally",
            listOf(CaptureStartRoute.STOP, CaptureStartRoute.DIRECT_FGS),
            routesThatWrite,
        )
    }

    @Test
    fun stopWritesInactiveRegardlessOfTheDirectStartOutcome() {
        listOf(false, true).forEach { succeeded ->
            assertEquals(
                "stopping is asynchronous, so reading the service back always yields a stale true: " +
                    "directStartSucceeded=$succeeded",
                CaptureTileState.INACTIVE,
                resolveTileClickState(CaptureStartRoute.STOP, succeeded),
            )
        }
    }

    @Test
    fun directStartWritesActiveOnceBecauseNoRefreshCanFollow() {
        assertEquals(
            CaptureTileState.ACTIVE,
            resolveTileClickState(CaptureStartRoute.DIRECT_FGS, directStartSucceeded = true),
        )
        assertNull(
            "falling back to the MediaProjection gateway collapses the panel, so the service's own " +
                "refresh lands and a premature ACTIVE would be wrong if the user denies the capture",
            resolveTileClickState(CaptureStartRoute.DIRECT_FGS, directStartSucceeded = false),
        )
    }
}
